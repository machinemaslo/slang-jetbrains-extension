package slanglsp.editor.navigation.cpp

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path
import org.jetbrains.annotations.Nls
import slanglsp.SlangBundle
import slanglsp.analysis.EditorAnalysisTasks
import slanglsp.analysis.ProjectTrust

internal object ReflectedCppUsageResolver {
    fun replay(project: Project, source: ReflectionProbeRunner.Source) {
        if (!sourceIsCurrent(source)) {
            notify(project, SlangBundle.message("cpp.probe.save.source"))
            return
        }
        if (!ProjectTrust.isTrusted(project)) {
            notify(project, SlangBundle.message("cpp.probe.trusted"))
            return
        }
        val runner = project.service<ReflectionProbeRunner>()
        background(project, SlangBundle.message("cpp.probe.discovering"), compute = {
            val active = source.configuration
            if (active != null) {
                listOf(active.buildDirectory.resolve("build.ninja"), active.buildDirectory.resolve("compile_commands.json"))
                    .filter { java.nio.file.Files.isRegularFile(it) }.take(1)
            } else {
                runner.discoverBuilds(source.root)
            }
        }) { builds ->
            if (!ActiveCppConfiguration.isCurrent(project, source.configuration)) {
                notify(project, SlangBundle.message("cpp.usages.configuration.changed"))
                return@background
            }
            if (builds.isEmpty()) {
                if (source.configuration != null) notify(project, SlangBundle.message("cpp.usages.active.manifest.missing"))
                else pickManifest(project, source)
            } else if (builds.size == 1) {
                selectTarget(project, source, builds.single())
            } else {
                val choices = builds.map { BuildChoice(source.root.relativize(it).toString(), it) } +
                    BuildChoice(SlangBundle.message("cpp.probe.other.build"), null)
                choose(project, SlangBundle.message("cpp.probe.build.title"), choices) { choice ->
                    val manifest = choice.manifest
                    if (manifest == null) {
                        pickManifest(project, source)
                    } else {
                        selectTarget(project, source, manifest)
                    }
                }
            }
        }
    }

    private fun pickManifest(project: Project, source: ReflectionProbeRunner.Source) {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withFileFilter { it.name == "build.ninja" || it.name == "compile_commands.json" }
            .withTitle(SlangBundle.message("cpp.probe.build.title"))
        FileChooser.chooseFile(descriptor, project, null) { file ->
            if (file.isInLocalFileSystem) {
                selectTarget(project, source, Path.of(file.path))
            }
        }
    }

    private fun selectTarget(project: Project, source: ReflectionProbeRunner.Source, manifest: Path) {
        val runner = project.service<ReflectionProbeRunner>()
        background(project, SlangBundle.message("cpp.probe.discovering"), compute = {
            val build = manifest.toRealPath().parent
            val targets = if (manifest.fileName.toString() == "compile_commands.json") {
                ReflectionInvocationDiscovery.databaseTargets(manifest, source.file)
            } else {
                runner.targets(build, source.file)
            }
            build to targets
        }) { (build, targets) ->
            when (targets.size) {
                0 -> if (manifest.fileName.toString() == "build.ninja") chooseCustomTarget(project, source, build)
                    else notify(project, SlangBundle.message("cpp.probe.no.database.entry"))
                1 -> confirm(project, source, build, targets.single())
                else -> choose(project, SlangBundle.message("cpp.probe.target.title"), targets) {
                    confirm(project, source, build, it)
                }
            }
        }
    }

    private fun chooseCustomTarget(project: Project, source: ReflectionProbeRunner.Source, build: Path) {
        val runner = project.service<ReflectionProbeRunner>()
        val progress = SlangBundle.message("cpp.probe.discovering")
        background(project, progress, compute = { runner.availableTargets(build) }) { outputs ->
            if (outputs.isEmpty()) {
                notify(project, SlangBundle.message("cpp.probe.no.targets"))
            } else {
                choose(project, SlangBundle.message("cpp.probe.choose.custom.target"), outputs) { output ->
                    background(project, progress, compute = { runner.target(build, output) }) { target ->
                        if (target == null) {
                            notify(project, SlangBundle.message("cpp.probe.no.command"))
                        } else {
                            confirm(project, source, build, target)
                        }
                    }
                }
            }
        }
    }

    private fun confirm(project: Project, source: ReflectionProbeRunner.Source, build: Path, target: ReflectionProbeRunner.Target) {
        // Binding reads the executable header. Keep it off the EDT, before offering to run it.
        background(project, SlangBundle.message("cpp.probe.discovering"), compute = {
            ReflectionGeneratorReplay.bind(build, source.file, target) != null
        }) { bound ->
            if (bound) confirmReplay(project, source, build, target)
            else notify(project, SlangBundle.message("cpp.probe.no.binding"))
        }
    }

    private fun confirmReplay(project: Project, source: ReflectionProbeRunner.Source, build: Path, target: ReflectionProbeRunner.Target) {
        if (!ActiveCppConfiguration.isCurrent(project, source.configuration)) {
            notify(project, SlangBundle.message("cpp.usages.configuration.changed"))
            return
        }
        val answer = Messages.showOkCancelDialog(
            project,
            SlangBundle.message("cpp.probe.confirm", target.toString(), build.toString(), target.command.take(500)),
            SlangBundle.message("cpp.probe.confirm.title"),
            SlangBundle.message("cpp.probe.run"),
            Messages.getCancelButton(),
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.OK || project.isDisposed) {
            return
        }
        if (!ProjectTrust.isTrusted(project)) {
            notify(project, SlangBundle.message("cpp.probe.trusted"))
            return
        }
        if (!ActiveCppConfiguration.isCurrent(project, source.configuration)) {
            notify(project, SlangBundle.message("cpp.usages.configuration.changed"))
            return
        }

        val runner = project.service<ReflectionProbeRunner>()
        background(project, SlangBundle.message("cpp.probe.running"), compute = {
            runner.probe(build, source, target)?.let(::locateArtifact)
        }) { location ->
            if (!ActiveCppConfiguration.isCurrent(project, source.configuration)) {
                notify(project, SlangBundle.message("cpp.usages.configuration.changed"))
            } else if (!sourceIsCurrent(source)) {
                notify(project, SlangBundle.message("cpp.probe.save.source"))
            } else if (location == null) {
                notify(project, SlangBundle.message("cpp.probe.unproven"))
            } else {
                openCppLocation(project, location, findUsages = true)
            }
        }
    }

    private fun sourceIsCurrent(source: ReflectionProbeRunner.Source): Boolean {
        val file = LocalFileSystem.getInstance().findFileByNioFile(source.file) ?: return false
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(file) ?: return true
        return !documents.isDocumentUnsaved(document) && document.immutableCharSequence.contentEquals(source.text)
    }

    fun locateArtifact(match: ReflectionProbeRunner.Match): ReflectionSearch.Location? {
        val declaration = match.declaration
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(declaration.file) ?: return null
        return runReadActionBlocking {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@runReadActionBlocking null
            if (!document.immutableCharSequence.contentEquals(editorText(match.artifact))) {
                return@runReadActionBlocking null
            }
            val offset = editorText(match.artifact.substring(0, declaration.offset)).length
            ReflectionSearch.Location(file, document.modificationStamp, offset, document.getLineNumber(offset) + 1)
        }
    }

    private fun <T> background(project: Project, @Nls title: String, compute: () -> T, success: (T) -> Unit) {
        project.service<EditorAnalysisTasks>().launch(title, onFailure = { failure ->
            val message = if (failure is ReflectionProbeRunner.Failure) {
                failure.message.orEmpty()
            } else {
                SlangBundle.message("cpp.probe.failed", failure.localizedMessage.orEmpty().take(400))
            }
            notify(project, message)
        }, compute = compute, onSuccess = success)
    }

    private fun <T> choose(project: Project, @Nls title: String, choices: List<T>, selected: (T) -> Unit) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setTitle(title).setItemChosenCallback { selected(it) }.createPopup().showCenteredInCurrentWindow(project)
    }

    private data class BuildChoice(val label: String, val manifest: Path?) {
        override fun toString(): String = label
    }
}
