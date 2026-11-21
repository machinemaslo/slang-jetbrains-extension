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
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
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
import com.intellij.openapi.util.SystemInfo
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import java.io.IOException
import java.nio.file.Path
import org.jetbrains.annotations.Nls
import slanglsp.SlangBundle
import slanglsp.editor.navigation.cpp.ReflectionMetadataLookup.Result
import slanglsp.editor.navigation.isSlangIdentifier
import slanglsp.language.SlangFileType
import slanglsp.language.SlangLexer

class FindCppUsagesAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        if (project == null || !supportsCppReflection() || structName(event) == null) {
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
        val offset = event.getData(CommonDataKeys.CARET)?.offset ?: editor.caretModel.offset
        val stamp = editor.document.modificationStamp
        ReflectionHeaderSetup.checkVersion(project) { replaced ->
            if (!replaced && !editor.isDisposed && editor.document.modificationStamp == stamp) {
                CppUsageSearch.start(psi, editor, offset)
            }
        }
    }

    private fun structName(event: AnActionEvent): String? {
        val file = event.getData(CommonDataKeys.PSI_FILE) ?: return null
        val caret = event.getData(CommonDataKeys.CARET) ?: event.getData(CommonDataKeys.EDITOR)?.caretModel?.primaryCaret
        return caret?.let { structNameAt(file, it.offset) }
    }
}

internal object CppUsageSearch {
    fun start(
        psi: PsiFile,
        editor: Editor,
        sourceOffset: Int,
        unavailable: (Result.Unavailable) -> Unit = { notify(psi.project, it.message()) },
        showUsages: (CppLocation, () -> Boolean) -> Unit = { location, current ->
            openCppUsages(psi.project, location, current)
        },
        finished: () -> Unit = {},
    ): Boolean {
        val project = psi.project
        if (project.isDisposed || editor.isDisposed) {
            return false
        }

        val declaration = runReadActionBlocking {
            if (!psi.isValid) {
                return@runReadActionBlocking null
            }
            val declaration = structDeclarationAt(psi, sourceOffset) ?: return@runReadActionBlocking null
            val file = psi.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return@runReadActionBlocking null
            val document = if (FileDocumentManager.getInstance().getFile(editor.document) == file) {
                editor.document
            } else {
                FileDocumentManager.getInstance().getDocument(file) ?: return@runReadActionBlocking null
            }
            ReflectionMetadataLookup.Declaration(file.toNioPath(), declaration.textOffset, declaration.text, document)
        } ?: return false
        val editorStamp = editor.document.modificationStamp
        val root = Path.of(project.basePath ?: return false)
        val rider = isRiderForWindows()

        fun canUse(configuration: ActiveCppConfiguration?, mapped: Result.Mapped? = null): Boolean {
            if (project.isDisposed || editor.isDisposed) {
                return false
            }
            if (DumbService.isDumb(project)) {
                notify(project, SlangBundle.message("cpp.usages.indexing"))
                return false
            }
            val problem = when {
                !ActiveCppConfiguration.isCurrent(project, configuration) -> Result.Unavailable.BUILD_CHANGED
                !declaration.isCurrent() || editor.document.modificationStamp != editorStamp ||
                    mapped?.documentsAreCurrent?.invoke() == false -> Result.Unavailable.STALE
                else -> return true
            }
            unavailable(problem)
            return false
        }

        fun lookup(
            configuration: ActiveCppConfiguration? = null,
            chosen: CppDeclaration? = null,
            build: MsBuildReflectionConfiguration.Choice? = null,
        ) {
            object : Task.Backgroundable(project, SlangBundle.message("cpp.usages.progress"), true) {
                private var active = configuration
                private var result: Result? = null
                private var options = emptyList<MsBuildReflectionConfiguration.Choice>()
                private var association = build

                override fun run(indicator: ProgressIndicator) {
                    try {
                        if (DumbService.isDumb(project)) {
                            return
                        }
                        if (rider) {
                            if (association == null) {
                                options = MsBuildReflectionConfiguration.choices(root, indicator)
                                association = options.singleOrNull()
                            }
                            val selection = association
                            if (selection == null) {
                                result = Result.Unavailable.NO_ACTIVE_BUILD
                                return
                            }
                            when (val found = MsBuildReflectionConfiguration.resolve(project, root, selection)) {
                                is MsBuildReflectionConfiguration.Association.Found -> {
                                    active = found.configuration
                                    if (configuration != null && configuration != active) {
                                        result = Result.Unavailable.BUILD_CHANGED
                                        return
                                    }
                                }
                                is MsBuildReflectionConfiguration.Association.Rejected -> {
                                    result = found.reason
                                    return
                                }
                            }
                        } else {
                            active = configuration ?: ActiveCppConfiguration.readCMake(project)
                        }
                        result = project.service<ReflectionMetadataLookup>().find(root, active, declaration, indicator)
                        val selection = association
                        val current = if (selection != null) {
                            (MsBuildReflectionConfiguration.resolve(project, root, selection) as?
                                MsBuildReflectionConfiguration.Association.Found)?.configuration
                        } else {
                            ActiveCppConfiguration.readCMake(project)
                        }
                        if (active != null && current != active) {
                            result = Result.Unavailable.BUILD_CHANGED
                        }
                    } catch (_: IndexNotReadyException) {
                        // A project model change can restart indexing.
                        result = null
                    } catch (_: InvalidReflectionMetadata) {
                        result = Result.Unavailable.CONFLICTING
                    } catch (_: IOException) {
                        result = Result.Unavailable.CONFLICTING
                    }
                }

                override fun onSuccess() {
                    val resolved = result
                    val mapped = resolved as? Result.Mapped
                    if (!canUse(active, mapped)) {
                        return
                    }
                    if (options.size > 1) {
                        chooseBuild(project, options) { lookup(build = it) }
                        return
                    }
                    when (resolved) {
                        null -> notify(project, SlangBundle.message("cpp.usages.indexing"))
                        is Result.Unavailable -> unavailable(resolved)
                        is Result.Mapped -> {
                            val matches = resolved.matches
                            val target = if (chosen == null) matches.singleOrNull() else matches.singleOrNull {
                                it.type == chosen.type && it.location.file == chosen.location.file &&
                                    it.location.offset == chosen.location.offset
                            }
                            when {
                                target != null -> showUsages(target.location) { canUse(active, resolved) }
                                chosen != null -> unavailable(Result.Unavailable.STALE)
                                else -> choose(project, declaration.name, matches) { lookup(active, it, association) }
                            }
                        }
                    }
                }

                override fun onFinished() {
                    finished()
                }
            }.queue()
        }

        lookup()
        return true
    }

    private fun choose(
        project: Project,
        name: String,
        matches: List<CppDeclaration>,
        confirmed: (CppDeclaration) -> Unit,
    ) {
        val options = matches.map { Choice("${it.type} — ${label(project, it.location)}", it) }
        JBPopupFactory.getInstance().createPopupChooserBuilder(options)
            .setTitle(SlangBundle.message("cpp.usages.choose.type", name))
            .setItemChosenCallback { confirmed(it.match) }
            .createPopup().showCenteredInCurrentWindow(project)
    }

    private fun chooseBuild(
        project: Project,
        choices: List<MsBuildReflectionConfiguration.Choice>,
        confirmed: (MsBuildReflectionConfiguration.Choice) -> Unit,
    ) {
        val options = choices.map { choice ->
            val identity = choice.identity
            val solution = choice.solutionConfigurations.joinToString()
            BuildChoice("${choice.solution}: $solution → ${identity.target} [${identity.configuration}|${identity.platform}]", choice)
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(options)
            .setTitle(SlangBundle.message("cpp.usages.metadata.choose.build"))
            .setItemChosenCallback { confirmed(it.build) }
            .createPopup().showCenteredInCurrentWindow(project)
    }

    private fun label(project: Project, location: CppLocation): String {
        val path = location.file.path
        val prefix = project.basePath?.trimEnd('/')?.plus('/')
        val relative = if (prefix != null) path.removePrefix(prefix) else path
        return "$relative:${location.line}"
    }

    private data class Choice(val label: String, val match: CppDeclaration) {
        override fun toString(): String = label
    }

    private data class BuildChoice(val label: String, val build: MsBuildReflectionConfiguration.Choice) {
        override fun toString(): String = label
    }
}

internal fun supportsCppReflection(): Boolean =
    ApplicationInfo.getInstance().build.productCode == "CL" || isRiderForWindows()

internal fun isRiderForWindows(): Boolean = SystemInfo.isWindows && ApplicationInfo.getInstance().build.productCode == "RD"

internal fun Result.Unavailable.message(): @Nls String = when (this) {
    Result.Unavailable.NO_ACTIVE_BUILD -> SlangBundle.message(
        if (isRiderForWindows()) "cpp.usages.metadata.no.msbuild" else "cpp.usages.metadata.no.build",
    )
    Result.Unavailable.MISSING_PACK -> SlangBundle.message("cpp.usages.metadata.missing.pack")
    Result.Unavailable.MISSING_SHADER -> SlangBundle.message("cpp.usages.metadata.missing.shader")
    Result.Unavailable.NOT_MAPPED -> SlangBundle.message("cpp.usages.metadata.not.mapped")
    Result.Unavailable.STALE -> SlangBundle.message("cpp.usages.metadata.stale")
    Result.Unavailable.CONFLICTING -> SlangBundle.message("cpp.usages.metadata.conflicting")
    Result.Unavailable.BUILD_CHANGED -> SlangBundle.message("cpp.usages.configuration.changed")
}

private fun openCppUsages(
    project: Project,
    location: CppLocation,
    current: () -> Boolean,
) {
    val (file, stamp, offset) = location
    if (project.isDisposed || !current()) {
        return
    }
    if (!file.isValid || FileDocumentManager.getInstance().getDocument(file)?.modificationStamp != stamp) {
        notify(project, Result.Unavailable.STALE.message())
        return
    }

    val actions = ActionManager.getInstance()
    val find = actions.getAction(IdeActions.ACTION_FIND_USAGES)
    if (find == null) {
        notify(project, SlangBundle.message("cpp.usages.handoff.failed"))
        return
    }

    val descriptor = OpenFileDescriptor(project, file, offset)
    val opened = descriptor.navigateInEditor(project, true)
    val editors = FileEditorManager.getInstance(project)
    val editor = (editors.getSelectedEditor(file) as? TextEditor)?.editor
    if (!opened || editor == null) {
        notify(project, SlangBundle.message("cpp.usages.handoff.failed"))
        return
    }

    editors.runWhenLoaded(editor) {
        if (project.isDisposed || editor.isDisposed || !current()) {
            return@runWhenLoaded
        }
        if (editor.document.modificationStamp != stamp) {
            notify(project, Result.Unavailable.STALE.message())
            return@runWhenLoaded
        }

        // Give CLion the C++ editor context and reference offset; don't fabricate a C++ PSI target.
        editor.caretModel.moveToOffset(offset)
        actions.tryToExecute(find, null, editor.contentComponent, ActionPlaces.EDITOR_POPUP, true)
            .doWhenRejected(Runnable { notify(project, SlangBundle.message("cpp.usages.handoff.failed")) })
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
