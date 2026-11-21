package slanglsp.editor.navigation.cpp

import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.RunManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path

/** Exact project build identity and the IDE selection against which it was resolved. */
internal data class ActiveCppConfiguration(
    val buildDirectory: Path,
    val buildType: String,
    val targetName: String,
    val selection: String,
    val rootsVersion: Long,
    val buildSystem: String = "cmake",
    val platform: String = "",
) {
    val reflectionIdentity get() = ReflectionBuildIdentity(buildSystem, targetName, buildType, platform)

    companion object {
        private val log = Logger.getInstance(ActiveCppConfiguration::class.java)

        fun readCMake(project: Project): ActiveCppConfiguration? = runReadActionBlocking {
            val settings = RunManager.getInstance(project).selectedConfiguration ?: return@runReadActionBlocking null
            val selected = settings.configuration
            val target = ExecutionTargetManager.getActiveTarget(project)
            val selection = "${settings.uniqueID}\n${target.id}"

            try {
                // CMake is optional and not part of the minimum IDEA SDK. Ask the selected
                // run configuration through its own loader; never infer the target from disk.
                val pair = selected.javaClass.methods.firstOrNull {
                    it.name == "getBuildAndRunConfigurations" &&
                        it.parameterTypes.contentEquals(arrayOf(ExecutionTarget::class.java))
                }?.invoke(selected, target) ?: return@runReadActionBlocking null
                val configuration = pair.javaClass.getField("buildConfiguration").get(pair)
                    ?: return@runReadActionBlocking null
                val directory = call(configuration, "getConfigurationGenerationDir") as? File ?: return@runReadActionBlocking null
                val buildType = call(configuration, "getBuildType") as? String
                val cmakeTarget = call(configuration, "getTarget") ?: return@runReadActionBlocking null
                val name = call(cmakeTarget, "getName") as? String ?: return@runReadActionBlocking null
                if (selection != selection(project)) {
                    return@runReadActionBlocking null
                }
                snapshot(project, directory.toPath().toAbsolutePath().normalize(), buildType.orEmpty(), name, selection = selection)
            } catch (failure: InvocationTargetException) {
                val cause = failure.targetException
                if (cause is ProcessCanceledException) {
                    throw cause
                }
                log.debug("Cannot read active CMake configuration", failure)
                null
            } catch (failure: ReflectiveOperationException) {
                log.debug("Active CMake model API is unavailable", failure)
                null
            } catch (failure: LinkageError) {
                log.debug("Active CMake model API is unavailable", failure)
                null
            }
        }

        fun snapshot(
            project: Project,
            directory: Path,
            configuration: String,
            target: String,
            system: String = "cmake",
            platform: String = "",
            selection: String = selection(project),
        ): ActiveCppConfiguration = ActiveCppConfiguration(
            directory, configuration, target, selection, ProjectRootModificationTracker.getInstance(project).modificationCount,
            system, platform,
        )

        fun isCurrent(project: Project, snapshot: ActiveCppConfiguration?): Boolean =
            snapshot == null || (snapshot.selection == selection(project) &&
                snapshot.rootsVersion == ProjectRootModificationTracker.getInstance(project).modificationCount)

        private fun selection(project: Project): String =
            "${RunManager.getInstance(project).selectedConfiguration?.uniqueID}\n${ExecutionTargetManager.getActiveTarget(project).id}"

        private fun call(receiver: Any, name: String): Any? = receiver.javaClass.getMethod(name).invoke(receiver)
    }
}
