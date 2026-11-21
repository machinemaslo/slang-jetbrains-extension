package slanglsp.editor.navigation.cpp

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.IOException
import javax.swing.JButton
import org.jetbrains.annotations.Nls
import slanglsp.SlangBundle

internal object ReflectionHeaderSetup {
    private val logger = Logger.getInstance(ReflectionHeaderSetup::class.java)

    fun open(project: Project, button: JButton, @Nls reason: String) {
        if (project.isDisposed) {
            return
        }

        val root = projectRoot(project)
        if (root == null) {
            button.isEnabled = true
            notify(project, reason)
            return
        }
        val registry = project.service<ReflectionHeaderRegistry>()
        button.isEnabled = false

        inspect(project, { registry.find(root) }) { result ->
            button.isEnabled = true
            when (result) {
                is ReflectionHeaderRegistry.Lookup.Found -> {
                    if (registry.canUpgrade(root, result.file)) {
                        offerUpgrade(project, root, registry, result.file, reason)
                    } else {
                        notify(project, reason)
                    }
                }
                ReflectionHeaderRegistry.Lookup.Missing -> offerHeader(project, root, registry, reason)
                ReflectionHeaderRegistry.Lookup.Ambiguous -> notify(
                    project,
                    reason + "\n" + SlangBundle.message("cpp.usages.header.ambiguous", ReflectionHeaderRegistry.HEADER_NAME),
                )
            }
        }
    }

    fun checkVersion(project: Project, finished: (replaced: Boolean) -> Unit) {
        if (project.isDisposed) {
            return
        }
        val root = projectRoot(project)
        if (root == null) {
            finished(false)
            return
        }
        val registry = project.service<ReflectionHeaderRegistry>()
        inspect(project, {
            val file = (registry.find(root) as? ReflectionHeaderRegistry.Lookup.Found)?.file
            VersionCheck(file?.let { registry.outdated(root, it) })
        }) { (update) ->
            finished(update != null && offerReplacement(project, root, registry, update))
        }
    }

    private data class VersionCheck(val update: ReflectionHeaderRegistry.Update?)

    private fun projectRoot(project: Project): VirtualFile? =
        project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }

    private fun <T : Any> inspect(
        project: Project,
        read: () -> T,
        finished: (T) -> Unit,
    ) {
        ReadAction.nonBlocking<T> { read() }
            .inSmartMode(project)
            .expireWhen { project.isDisposed }
            .finishOnUiThread(ModalityState.nonModal()) {
                if (!project.isDisposed) {
                    finished(it)
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun offerReplacement(
        project: Project,
        root: VirtualFile,
        registry: ReflectionHeaderRegistry,
        update: ReflectionHeaderRegistry.Update,
    ): Boolean {
        if (project.isDisposed) {
            return false
        }
        val file = update.file
        val label = SlangBundle.message("cpp.usages.header.replace")
        val content = SlangBundle.message("cpp.usages.header.outdated", file.name, update.installedVersion, update.bundledVersion)
        val answer = Messages.showOkCancelDialog(
            project,
            content + "\n\n" + SlangBundle.message("cpp.usages.header.replace.confirm", file.presentableUrl),
            SlangBundle.message("cpp.usages.quick.view"),
            label,
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        )
        if (answer != Messages.OK) {
            return false
        }

        var replaced = false
        if (install(project, label, file) { replaced = registry.replaceOutdated(root, update) }) {
            notify(
                project,
                SlangBundle.message(if (replaced) "cpp.usages.header.replaced" else "cpp.usages.header.replace.changed", file.name),
            )
        }
        return replaced
    }

    private fun offerHeader(project: Project, root: VirtualFile, registry: ReflectionHeaderRegistry, @Nls reason: String) {
        val name = ReflectionHeaderRegistry.HEADER_NAME
        val title = SlangBundle.message("cpp.usages.quick.view")
        val add = SlangBundle.message("cpp.usages.header.add")
        val confirmed = Messages.showOkCancelDialog(
            project,
            reason + "\n\n" + SlangBundle.message("cpp.usages.header.setup", name),
            title,
            add,
            Messages.getCancelButton(),
            Messages.getInformationIcon(),
        )
        if (confirmed != Messages.OK || project.isDisposed) {
            return
        }

        val descriptor = FileChooserDescriptorFactory.singleDir()
            .withRoots(root)
            .withTitle(SlangBundle.message("cpp.usages.header.select.folder"))
        FileChooser.chooseFile(descriptor, project, root) { folder ->
            install(project, add, folder) { registry.create(root, folder) }
        }
    }

    private fun offerUpgrade(
        project: Project, root: VirtualFile, registry: ReflectionHeaderRegistry, file: VirtualFile, @Nls reason: String,
    ) {
        val upgrade = SlangBundle.message("cpp.usages.header.install")
        val confirmed = Messages.showOkCancelDialog(
            project,
            reason + "\n\n" + SlangBundle.message("cpp.usages.header.upgrade", file.name),
            SlangBundle.message("cpp.usages.quick.view"),
            upgrade,
            Messages.getCancelButton(),
            Messages.getInformationIcon(),
        )
        if (confirmed == Messages.OK) {
            install(project, upgrade, file) { registry.upgradeEmpty(root, file) }
        }
    }

    private fun install(project: Project, title: String, location: VirtualFile, write: () -> Unit): Boolean {
        if (project.isDisposed) {
            return false
        }
        try {
            WriteCommandAction.runWriteCommandAction(project, title, null, Runnable(write))
            return true
        } catch (failure: IOException) {
            logger.warn("Could not install reflection metadata helper at ${location.path}", failure)
            notify(project, SlangBundle.message("cpp.usages.header.failed", ReflectionHeaderRegistry.HEADER_NAME))
            return false
        }
    }
}
