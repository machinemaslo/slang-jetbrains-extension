package slanglsp.editor.rename

import com.google.gson.JsonParser
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import slanglsp.editor.navigation.cpp.MsBuildReflectionConfiguration
import slanglsp.editor.navigation.cpp.MsBuildReflectionConfiguration.Association
import slanglsp.editor.navigation.cpp.MsBuildReflectionConfiguration.Choice
import slanglsp.editor.navigation.cpp.ReflectionMetadataLookup
import slanglsp.editor.rename.ReflectionRenameRegistration.Entry
import slanglsp.editor.rename.ReflectionRenameRegistration.GenerationTask

/** Resolves each previously built, affected solution mapping without using build recency. */
internal object MsBuildReflectionProducer {
    class Request(
        val choice: Choice,
        val association: Association.Found,
        val task: GenerationTask,
        val affected: Set<Path>,
    ) {
        fun isCurrent(project: Project, workspace: Path): Boolean {
            val current = resolve(project, workspace, choice) ?: return false
            val configuration = association.configuration
            return current.producer == association.producer &&
                current.configuration.buildDirectory == configuration.buildDirectory &&
                current.configuration.reflectionIdentity == configuration.reflectionIdentity &&
                Files.isRegularFile(pack(association), NOFOLLOW_LINKS)
        }

        fun verifyEvaluation(output: String) {
            val properties = JsonParser.parseString(output.removePrefix("\uFEFF")).asJsonObject.getAsJsonObject("Properties")
                ?: throw IOException("No evaluated MSBuild properties")
            val directory = properties.get("IntDir")?.asString?.takeIf { it.isNotBlank() }
            val entry = task.entry as Entry.MsBuild
            if (directory == null || entry.project.parent.resolve(directory).normalize() != association.configuration.buildDirectory ||
                properties.get("Configuration")?.asString != task.configuration ||
                properties.get("Platform")?.asString != entry.platform ||
                properties.get("JbSlangLspReflectionTarget")?.asString != task.target) {
                throw IOException("MSBuild did not evaluate to the published reflection variant and target")
            }
        }
    }

    fun plan(
        project: Project,
        workspace: Path,
        changed: Set<Path>,
        lookup: ReflectionMetadataLookup,
        indicator: ProgressIndicator,
    ): List<Request> {
        val published = linkedMapOf<Path, MutableList<Pair<Choice, Association.Found>>>()
        for (choice in MsBuildReflectionConfiguration.choices(workspace, indicator)) {
            indicator.checkCanceled()
            val association = resolve(project, workspace, choice) ?: continue
            val file = pack(association)
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                continue
            }
            val realFile = try {
                file.toRealPath()
            } catch (_: IOException) {
                continue
            }
            published.getOrPut(realFile) { mutableListOf() }.add(choice to association)
        }
        return published.values.mapNotNull { candidates ->
            // The same physical pack cannot have two different solution/producer contexts.
            val (choice, association) = candidates.singleOrNull() ?: return@mapNotNull null
            request(workspace, choice, association, changed, lookup, indicator)
        }
    }

    private fun request(
        workspace: Path,
        choice: Choice,
        association: Association.Found,
        changed: Set<Path>,
        lookup: ReflectionMetadataLookup,
        indicator: ProgressIndicator,
    ): Request? {
        val producer = association.producer ?: return null
        val active = association.configuration
        val identity = active.reflectionIdentity
        val configuration = ReflectionRenameRegistration.configurationName
        if (!configuration.matches(identity.configuration) || !configuration.matches(identity.platform)) {
            return null
        }
        val inputs = lookup.inputsForRename(workspace, active.buildDirectory, identity, changed, indicator) ?: return null
        val entry = Entry.MsBuild(workspace.resolve(identity.target), identity.platform, workspace.resolve(choice.solution))
        val task = GenerationTask(entry, active.buildType, producer.target, producer.executable, inputs.recorded)
        return Request(choice, association, task, inputs.affected)
    }

    private fun pack(association: Association.Found): Path = association.configuration.let {
        it.buildDirectory.resolve(".jb-slanglsp/reflection/v1").resolve(it.reflectionIdentity.packName())
    }

    private fun resolve(project: Project, workspace: Path, choice: Choice): Association.Found? =
        MsBuildReflectionConfiguration.resolve(project, workspace, choice, selection = "") as? Association.Found
}
