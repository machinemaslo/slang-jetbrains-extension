package slanglsp.editor.navigation.cpp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path
import org.jetbrains.annotations.Nls
import slanglsp.SlangBundle
import slanglsp.editor.navigation.isSlangIdentifier
import slanglsp.language.SlangFileType
import slanglsp.language.SlangLexer

class FindCppUsagesAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        if (project == null || ApplicationInfo.getInstance().build.productCode != "CL" || structName(event) == null) {
            event.presentation.isEnabledAndVisible = false
        } else {
            event.presentation.isVisible = true
            event.presentation.isEnabled = !DumbService.isDumb(project)
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val psi = event.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val declaration = structDeclarationAt(psi, event.getData(CommonDataKeys.CARET)?.offset ?: editor.caretModel.offset) ?: return
        val name = declaration.text
        val offset = declaration.textOffset
        val virtualFile = psi.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return
        val text = editor.document.immutableCharSequence
        val stamp = editor.document.modificationStamp
        val root = Path.of(project.basePath ?: return)
        val path = Path.of(virtualFile.path)

        object : Task.Backgroundable(project, SlangBundle.message("cpp.usages.progress"), true) {
            private var result: ReflectionSearch.Result? = null
            private lateinit var source: ReflectionProbeRunner.Source

            override fun run(indicator: ProgressIndicator) {
                try {
                    if (!DumbService.isDumb(project)) {
                        val configuration = ActiveCppConfiguration.read(project)
                        source = ReflectionProbeRunner.Source(root, path, offset, text.toString(), configuration)
                        val cached = project.service<ReflectionMappingCache>().getValid(virtualFile, offset, stamp, configuration)
                        if (cached != null) {
                            result = cached
                            return
                        }
                        val traced = project.service<ReflectionProbeRunner>().cached(path, offset, text, configuration)
                            ?.let(ReflectedCppUsageResolver::locateArtifact)
                        if (traced != null) {
                            result = ReflectionSearch.Result(listOf(ReflectionSearch.Match(name, traced, true)), emptyList(), false, setOf(traced.file))
                            return
                        }
                        val generated = GeneratedStructLookup.find(project, path, text, offset, indicator)
                        val selected = configuration?.select(generated) ?: generated
                        val proven = selected.filter { it.typeOnly }
                        val files = generated.mapTo(linkedSetOf()) { it.location.file }
                        result = when {
                            proven.isNotEmpty() -> ReflectionSearch.Result(proven, emptyList(), false, files)
                            selected.isNotEmpty() -> ReflectionSearch.Result(selected, emptyList(), false, files)
                            else -> {
                                val explicit = ReflectionSearch.find(project, name)
                                explicit.copy(matches = configuration?.select(explicit.matches) ?: explicit.matches)
                            }
                        }
                    }
                } catch (_: IndexNotReadyException) {
                    // A project model change can restart indexing while collecting candidates.
                }
            }

            override fun onSuccess() {
                if (project.isDisposed) {
                    return
                }
                val search = result
                if (search == null) {
                    notify(project, SlangBundle.message("cpp.usages.indexing"))
                } else if (editor.document.modificationStamp != stamp || !ActiveCppConfiguration.isCurrent(project, source.configuration)) {
                    notify(project, SlangBundle.message("cpp.usages.source.changed"))
                } else {
                    showResult(project, name, search, source) { location ->
                        if (!editor.isDisposed && editor.document.modificationStamp == stamp &&
                            ActiveCppConfiguration.isCurrent(project, source.configuration)) {
                            project.service<ReflectionMappingCache>().put(virtualFile, offset, stamp, location, source.configuration, search.generatedFiles)
                            openCppLocation(project, location, findUsages = true, search.generatedFiles)
                        } else {
                            notify(project, SlangBundle.message("cpp.usages.source.changed"))
                        }
                    }
                }
            }
        }.queue()
    }

    private fun structName(event: AnActionEvent): String? {
        val file = event.getData(CommonDataKeys.PSI_FILE) ?: return null
        val caret = event.getData(CommonDataKeys.CARET) ?: event.getData(CommonDataKeys.EDITOR)?.caretModel?.primaryCaret
        return caret?.let { structNameAt(file, it.offset) }
    }

    private fun showResult(
        project: Project, name: String, result: ReflectionSearch.Result, source: ReflectionProbeRunner.Source,
        confirmed: (ReflectionSearch.Location) -> Unit,
    ) {
        val matches = result.matches
        when {
            matches.size == 1 && !result.incomplete && (matches.single().typeOnly || source.configuration != null) ->
                confirmed(matches.single().location)
            matches.isNotEmpty() -> {
                val options = matches.map { Choice("${it.type} — ${label(project, it.location)}", it.location) } +
                    Choice(SlangBundle.message("cpp.usages.resolve.generator"), null)
                choose(project, SlangBundle.message("cpp.usages.choose.type", name), options, result.incomplete) {
                    if (it == null) ReflectedCppUsageResolver.replay(project, source)
                    else confirmed(it)
                }
            }
            else -> ReflectedCppUsageResolver.replay(project, source)
        }
    }

    private fun label(project: Project, location: ReflectionSearch.Location): String {
        val path = location.file.path
        val prefix = project.basePath?.trimEnd('/')?.plus('/')
        val relative = if (prefix != null) path.removePrefix(prefix) else path
        return "$relative:${location.line}"
    }

    private fun choose(
        project: Project,
        @Nls title: String,
        options: List<Choice>,
        incomplete: Boolean,
        action: (ReflectionSearch.Location?) -> Unit,
    ) {
        val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(options)
            .setTitle(title)
            .setItemChosenCallback { action(it.location) }
        if (incomplete) {
            popup.setAdText(SlangBundle.message("cpp.usages.limit"))
        }
        popup.createPopup().showCenteredInCurrentWindow(project)
    }

    private data class Choice(val label: String, val location: ReflectionSearch.Location?) {
        override fun toString(): String = label
    }
}

internal fun openCppLocation(
    project: Project,
    location: ReflectionSearch.Location,
    findUsages: Boolean,
    generatedFiles: Set<com.intellij.openapi.vfs.VirtualFile> = setOf(location.file),
) {
    val (file, stamp, offset) = location
    if (project.isDisposed) {
        return
    }
    if (!file.isValid || FileDocumentManager.getInstance().getDocument(file)?.modificationStamp != stamp) {
        notify(project, SlangBundle.message("cpp.usages.source.changed"))
        return
    }

    val descriptor = OpenFileDescriptor(project, file, offset)
    val opened = descriptor.navigateInEditor(project, true)
    if (!findUsages) {
        return
    }
    val editors = FileEditorManager.getInstance(project)
    val editor = (editors.getSelectedEditor(file) as? TextEditor)?.editor
    if (!opened || editor == null) {
        notify(project, SlangBundle.message("cpp.usages.handoff.failed"))
        return
    }

    editors.runWhenLoaded(editor) {
        if (project.isDisposed || editor.isDisposed) {
            return@runWhenLoaded
        }
        if (editor.document.modificationStamp != stamp) {
            notify(project, SlangBundle.message("cpp.usages.source.changed"))
            return@runWhenLoaded
        }

        // Give CLion the C++ editor context and reference offset; don't fabricate a C++ PSI target.
        editor.caretModel.moveToOffset(offset)
        val actions = ActionManager.getInstance()
        val find = actions.getAction(IdeActions.ACTION_FIND_USAGES)
        if (find == null) {
            notify(project, SlangBundle.message("cpp.usages.handoff.failed"))
        } else {
            val chars = editor.document.immutableCharSequence
            var end = offset
            while (end < chars.length && (chars[end].isLetterOrDigit() || chars[end] == '_')) end++
            val name = chars.subSequence(offset, end).toString().takeIf { it.isNotEmpty() }
            if (name != null) {
                val exclusions = project.service<ReflectedUsageExclusions>()
                val request = exclusions.prepare(location, name, generatedFiles)
                actions.tryToExecute(find, null, editor.contentComponent, ActionPlaces.EDITOR_POPUP, true)
                    .doWhenRejected(Runnable {
                        exclusions.cancel(request)
                        notify(project, SlangBundle.message("cpp.usages.handoff.failed"))
                    })
            } else {
                notify(project, SlangBundle.message("cpp.usages.handoff.failed"))
            }
        }
    }
}

internal fun notify(project: Project, @Nls message: String) {
    if (!project.isDisposed) {
        NotificationGroupManager.getInstance().getNotificationGroup("Slang LSP")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}

internal fun structNameAt(file: PsiFile, offset: Int): String? = structDeclarationAt(file, offset)?.text

internal fun structDeclarationAt(file: PsiFile, offset: Int): PsiElement? {
    if (file.fileType !== SlangFileType) {
        return null
    }
    val current = file.findElementAt(offset)
    if (current.isStructKeyword()) {
        val next = adjacentToken(current!!, forward = true)
        return next?.takeIf(::isSlangIdentifier)
    }
    val previous = if (offset > 0) file.findElementAt(offset - 1) else null
    for (token in listOfNotNull(current, previous).distinct()) {
        if (isSlangIdentifier(token) && adjacentToken(token, forward = false).isStructKeyword()) {
            return token
        }
    }
    return null
}

private fun PsiElement?.isStructKeyword(): Boolean = this?.node?.elementType === SlangLexer.KEYWORD && text == "struct"

private fun adjacentToken(element: PsiElement, forward: Boolean): PsiElement? {
    var token = element
    while (true) {
        token = (if (forward) PsiTreeUtil.nextLeaf(token) else PsiTreeUtil.prevLeaf(token)) ?: return null
        when (token.node.elementType) {
            TokenType.WHITE_SPACE, SlangLexer.COMMENT, SlangLexer.BLOCK_COMMENT -> continue
            else -> return token
        }
    }
}
