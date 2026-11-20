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

/** A snapshot of the selected CLion target/profile, not a guess based on directory names. */
internal data class ActiveCppConfiguration(
    val name: String,
    val buildDirectory: Path,
    val activeRoots: Set<Path>,
    val inactiveRoots: Set<Path>,
    val selection: String = "",
    val rootsVersion: Long = 0,
) {
    fun select(matches: List<ReflectionSearch.Match>): List<ReflectionSearch.Match> {
        fun rank(match: ReflectionSearch.Match): Int {
            val path = Path.of(match.location.file.path).normalize()
            val active = activeRoots.filter { path.startsWith(it) }.maxOfOrNull { it.nameCount } ?: -1
            val inactive = inactiveRoots.filter { path.startsWith(it) }.maxOfOrNull { it.nameCount } ?: -1
            return active.compareTo(inactive)
        }
        val active = matches.filter { rank(it) > 0 }
        return active.ifEmpty { matches.filter { rank(it) >= 0 } }
    }

    companion object {
        private val log = Logger.getInstance(ActiveCppConfiguration::class.java)

        fun read(project: Project): ActiveCppConfiguration? = runReadActionBlocking {
            val selected = RunManager.getInstance(project).selectedConfiguration?.configuration
                ?: return@runReadActionBlocking null
            val target = ExecutionTargetManager.getActiveTarget(project)
            try {
                // CMake is optional and not part of the IDEA SDK. Resolve only these public model
                // accessors through the selected run configuration's owning plugin classloader.
                val pair = selected.javaClass.methods.firstOrNull {
                    it.name == "getBuildAndRunConfigurations" && it.parameterTypes.contentEquals(arrayOf(ExecutionTarget::class.java))
                }?.invoke(selected, target) ?: return@runReadActionBlocking null
                val configuration = pair.javaClass.getField("buildConfiguration").get(pair)
                    ?: return@runReadActionBlocking null
                val profile = call(configuration, "getProfileName") as? String ?: return@runReadActionBlocking null
                val buildType = call(configuration, "getBuildType") as? String
                val build = path(call(configuration, "getConfigurationGenerationDir"))
                    ?: return@runReadActionBlocking null
                val workspaceType = Class.forName(
                    "com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace", false, selected.javaClass.classLoader,
                )
                val workspace = workspaceType.getMethod("getInstance", Project::class.java).invoke(null, project)
                val targets = call(workspace, "getModelTargets") as? Iterable<*> ?: return@runReadActionBlocking null
                val configurations = targets.filterNotNull().flatMap {
                    (call(it, "getConfigurations") as? Iterable<*>)?.filterNotNull().orEmpty()
                }.distinct().take(1024)
                val active = linkedSetOf(build)
                val inactive = linkedSetOf<Path>()
                val budget = SourceBudget()
                val ordered = (listOf(configuration) + configurations).distinct().sortedBy {
                    call(it, "getProfileName") != profile || call(it, "getBuildType") != buildType
                }
                for (item in ordered) {
                    val roots = roots(item, budget)
                    if (call(item, "getProfileName") == profile && call(item, "getBuildType") == buildType) {
                        active.addAll(roots)
                    } else {
                        inactive.addAll(roots)
                    }
                }
                // Common source/include roots don't distinguish profiles.
                val shared = active.intersect(inactive)
                ActiveCppConfiguration(
                    "$profile / ${buildType.orEmpty()} / ${selected.name}", build, active - shared, inactive - shared,
                    selection(project), ProjectRootModificationTracker.getInstance(project).modificationCount,
                )
            } catch (failure: InvocationTargetException) {
                val cause = failure.targetException
                if (cause is ProcessCanceledException) throw cause
                log.debug("Cannot read active CMake configuration", cause)
                null
            } catch (failure: ReflectiveOperationException) {
                log.debug("Active CMake model API is unavailable", failure)
                null
            } catch (failure: LinkageError) {
                log.debug("Active CMake model is unavailable", failure)
                null
            }
        }

        /** Constant-time checks on the EDT after collecting the full model snapshot in the background. */
        fun isCurrent(project: Project, snapshot: ActiveCppConfiguration?): Boolean =
            if (snapshot == null) true else snapshot.selection == selection(project) &&
                snapshot.rootsVersion == ProjectRootModificationTracker.getInstance(project).modificationCount

        private fun selection(project: Project): String =
            "${RunManager.getInstance(project).selectedConfiguration?.uniqueID}\n${ExecutionTargetManager.getActiveTarget(project).id}"

        private fun call(receiver: Any, name: String): Any? = receiver.javaClass.getMethod(name).invoke(receiver)
        private fun path(value: Any?): Path? = (value as? File)?.toPath()?.toAbsolutePath()?.normalize()

        private class SourceBudget(var remaining: Int = 4096)

        private fun roots(configuration: Any, budget: SourceBudget): Set<Path> {
            val build = path(call(configuration, "getConfigurationGenerationDir")) ?: return emptySet()
            val roots = linkedSetOf(build)
            path(call(configuration, "getConfigurationAndTargetGenerationDir"))?.let(roots::add)
            val settings = call(configuration, "getPerLanguageSettingsMap") as? Map<*, *> ?: return roots
            for (setting in settings.values.filterNotNull()) {
                val flags = (call(setting, "getFlags") as? List<*>)?.filterIsInstance<String>().orEmpty()
                roots.addAll(includeDirectories(build, flags))
            }
            // CMake can put include options on individual source files rather than on the target.
            val fileSettings = configuration.javaClass.methods.firstOrNull {
                it.name == "getFileSettings" && it.parameterTypes.contentEquals(arrayOf(File::class.java))
            }
            if (fileSettings != null && budget.remaining > 0) {
                val sources = (call(configuration, "getSources") as? Collection<*>)?.asSequence()
                    ?.filterIsInstance<File>()?.take(budget.remaining).orEmpty()
                for (source in sources) {
                    budget.remaining--
                    val custom = fileSettings.invoke(configuration, source) ?: continue
                    for (accessor in listOf("getLeadingFlags", "getTrailingFlags")) {
                        val flags = (call(custom, accessor) as? List<*>)?.filterIsInstance<String>().orEmpty()
                        roots.addAll(includeDirectories(build, flags))
                    }
                }
            }
            return roots
        }

        internal fun includeDirectories(directory: Path, flags: List<String>): Set<Path> {
            val result = linkedSetOf<Path>()
            var i = 0
            while (i < flags.size) {
                val flag = flags[i++]
                val value = when {
                    flag in setOf("-I", "-isystem", "-iquote", "/I", "/external:I") -> flags.getOrNull(i++) ?: break
                    flag.startsWith("-I") && flag.length > 2 -> flag.substring(2)
                    flag.startsWith("/I") && flag.length > 2 -> flag.substring(2)
                    flag.startsWith("-isystem") && flag.length > 8 -> flag.substring(8).removePrefix("=")
                    flag.startsWith("/external:I") && flag.length > 11 -> flag.substring(11)
                    else -> continue
                }
                runCatching { directory.resolve(value.trim('"')).normalize().toAbsolutePath() }.getOrNull()?.let(result::add)
            }
            return result
        }
    }
}
