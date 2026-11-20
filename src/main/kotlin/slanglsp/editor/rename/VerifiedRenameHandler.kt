package slanglsp.editor.rename

import com.intellij.ide.TitledHandler
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService.Companion.isDumb
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.usages.*
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import slanglsp.SlangBundle
import slanglsp.analysis.EditorAnalysisTasks
import slanglsp.editor.navigation.isSlangIdentifier
import slanglsp.language.SlangLanguage
import slanglsp.server.BuiltinFiles.BuiltinFile

/** Verified project-local rename. */
class VerifiedRenameHandler : RenameHandler, TitledHandler {
    override fun isAvailableOnDataContext(context: DataContext): Boolean {
        val project = CommonDataKeys.PROJECT.getData(context)

        if (project == null || project.isDisposed || isDumb(project)) {
            return false
        }
        val file = CommonDataKeys.PSI_FILE.getData(context)

        if (RenameUi.target(file, CommonDataKeys.EDITOR.getData(context)) == null) {
            return false
        }

        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, context: DataContext) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val element: PsiElement = RenameUi.target(file, editor) ?: return

        val name = element.text
        val dialog = RenameDialog(project, name)
        if (!dialog.showAndGet()) {
            return
        }
        val replacement = dialog.replacement()
        val previewRequested = dialog.previewRequested()
        val pointer = SmartPointerManager.createPointer<PsiElement?>(element)
        RenameUi.queueRename(project, replacement, previewRequested) { pointer.element }
    }

    override fun invoke(project: Project, elements: Array<PsiElement?>, context: DataContext) {
        val editor = CommonDataKeys.EDITOR.getData(context)
        val file = CommonDataKeys.PSI_FILE.getData(context)

        if (editor != null && file != null) {
            invoke(project, editor, file, context)
        }
    }

    override fun getActionTitle(): String {
        return SlangBundle.message("rename.rename.slang.symbol")
    }

    internal object RenameUi {
        fun queueRename(project: Project, replacement: String?, previewRequested: Boolean, resolve: () -> PsiElement?) {
            project.service<EditorAnalysisTasks>().launch(
                SlangBundle.message("rename.verifying.slang.rename"),
                onFailure = { failure ->
                    showError(
                        project, failure.message ?: SlangBundle.message("rename.slangd.could.not.verify.the.rename"))
                },
                serial = true,
                compute = {
                    val source =
                        checkNotNull(runReadActionBlocking(resolve)) {
                            SlangBundle.message("rename.the.symbol.changed.run.rename.again")
                        }
                    RenamePlan.collect(source, replacement)
                }) { plan ->
                    try {
                        finishRename(project, plan, previewRequested)
                    } catch (failure: IllegalStateException) {
                        showError(project, failure.message)
                    }
                }
        }

        fun target(file: PsiFile?, editor: Editor?): PsiElement? {
            if (file == null ||
                editor == null ||
                editor.isDisposed ||
                file.language !== SlangLanguage.INSTANCE ||
                file.virtualFile is BuiltinFile ||
                !file.isWritable) {
                return null
            }
            val offset = editor.caretModel.offset
            var element = file.findElementAt(offset)

            if (!isSlangIdentifier(element) && offset > 0) {
                element = file.findElementAt(offset - 1)
            }

            return if (isSlangIdentifier(element)) element else null
        }

        internal fun finishRename(project: Project, plan: RenamePlan, previewRequested: Boolean) {
            if (previewRequested || plan.requiresPreview()) {
                showPreview(project, plan)
            } else {
                plan.apply()
            }
        }

        private fun showPreview(project: Project, plan: RenamePlan) {
            val presentation = UsageViewPresentation()
            presentation.tabText = SlangBundle.message("rename.tab", plan.oldName)
            presentation.tabName = SlangBundle.message("rename.command", plan.oldName, plan.newName)
            presentation.codeUsagesString = SlangBundle.message("rename.verified.occurrences.including.declaration")
            val usages = plan.usages().map { UsageInfo2UsageAdapter(it) }.toTypedArray<Usage>()
            val view = UsageViewManager.getInstance(project).showUsages(UsageTarget.EMPTY_ARRAY, usages, presentation)
            view.setAdditionalComponent(
                JLabel(
                    SlangBundle.message(
                        "rename.project.slang.files.only.inactive.code.and.name.conflicts.cannot.be.checked.by.this.fallback")))
            view.addPerformOperationAction(
                Runnable {
                    if (view.excludedUsages.isNotEmpty()) {
                        showError(
                            project,
                            SlangBundle.message("rename.include.every.verified.occurrence.before.renaming.the.symbol"))
                        return@Runnable
                    }
                    try {
                        plan.apply()
                        view.close()
                    } catch (failure: IllegalStateException) {
                        showError(project, failure.message)
                    }
                },
                SlangBundle.message("rename.tab", plan.oldName),
                SlangBundle.message("rename.files.changed.run.rename.again"),
                SlangBundle.message("rename.action"),
                false)
        }

        fun showError(project: Project, message: String?) {
            Messages.showErrorDialog(project, message, SlangBundle.message("rename.cannot.rename.slang.symbol"))
        }
    }
}

/** Explicit preview is available without making it a mandatory second confirmation. */
private class RenameDialog(project: Project, private val oldName: String) : DialogWrapper(project) {
    private val name: JBTextField = JBTextField(oldName, 30)
    private var previewRequested = false
    private val preview: Action =
        object : AbstractAction(SlangBundle.message("rename.preview")) {
            override fun actionPerformed(event: ActionEvent?) {
                if (doValidate() == null) {
                    previewRequested = true
                    close(OK_EXIT_CODE)
                }
            }
        }

    init {
        title = SlangBundle.message("rename.rename.slang.symbol")
        setOKButtonText(SlangBundle.message("rename.action"))
        init()
        name.selectAll()
        name.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                    updateActions()
                }
            })
        updateActions()
    }

    private fun updateActions() {
        val valid = doValidate() == null
        isOKActionEnabled = valid
        preview.isEnabled = valid
    }

    fun replacement(): String? {
        return name.getText()
    }

    fun previewRequested(): Boolean {
        return previewRequested
    }

    override fun doValidate(): ValidationInfo? {
        if (!RenamePlan.validName(replacement())) {
            return ValidationInfo(
                SlangBundle.message("rename.enter.a.slang.identifier.not.a.keyword.or.built.in.type"), name)
        }
        return if (oldName == replacement())
            ValidationInfo(SlangBundle.message("rename.choose.a.different.identifier"), name)
        else null
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.add(JBLabel(SlangBundle.message("rename.prompt", oldName)), BorderLayout.NORTH)
        panel.add(name, BorderLayout.CENTER)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return name
    }

    override fun createActions(): Array<Action?> {
        return arrayOf(okAction, preview, cancelAction)
    }
}
