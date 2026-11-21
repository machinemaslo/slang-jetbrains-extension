package slanglsp.editor.navigation

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.ide.util.EditSourceUtil
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import slanglsp.analysis.EditorAnalysisTasks

/** An editor link whose LSP target is resolved on activation, never while highlighting it. */
internal class DeferredNavigationTarget(
    source: PsiElement,
    private val offset: Int,
    private val editor: Editor,
    private val handler: SymbolDeclarationHandler,
) : NavigationPsiElement(source.containingFile, source.textRange) {
    private val pointer = SmartPointerManager.createPointer(source)
    private val label = source.text
    private val stamp = editor.document.modificationStamp

    override fun getText(): String = label

    override fun canNavigate(): Boolean =
        super.canNavigate() && !editor.isDisposed && editor.document.modificationStamp == stamp

    override fun navigate(requestFocus: Boolean) {
        if (!canNavigate() || project.isDisposed) {
            return
        }

        project.service<EditorAnalysisTasks>().launch(
            CodeInsightBundle.message("command.completion.GotoDeclaration.text"),
            compute = {
                val source = runReadActionBlocking { if (canNavigate()) pointer.element else null }
                source?.let { handler.resolveTargets(it, offset, editor) } ?: PsiElement.EMPTY_ARRAY
            },
            onSuccess = { targets ->
                if (canNavigate()) {
                    val current = targets.filter { it.isValid && navigationFor(it)?.canNavigate() == true }
                    when (current.size) {
                        0 -> HintManager.getInstance().showErrorHint(
                            editor, CodeInsightBundle.message("declaration.navigation.nowhere.to.go"),
                        )
                        1 -> navigateTo(current.single(), requestFocus)
                        else -> PsiTargetNavigator(current).navigate(
                            editor, CodeInsightBundle.message("declaration.navigation.title"),
                        ) { target ->
                            navigateTo(target, requestFocus)
                        }
                    }
                }
            },
        )
    }

    private fun navigateTo(target: PsiElement, requestFocus: Boolean): Boolean {
        val navigation = navigationFor(target)?.takeIf { it.canNavigate() } ?: return false
        if (navigation is UsageNavigationTarget) {
            navigation.navigate(requestFocus)
            return true
        }

        // Resolution completed after the IDE's original navigation command returned.
        CommandProcessor.getInstance().executeCommand(
            project,
            {
                val history = IdeDocumentHistory.getInstance(project)
                history.includeCurrentCommandAsNavigation()
                history.setCurrentCommandHasMoves()
                navigation.navigate(requestFocus)
            },
            CodeInsightBundle.message("command.completion.GotoDeclaration.text"),
            null,
        )
        return true
    }

    private fun navigationFor(target: PsiElement): Navigatable? =
        (target as? Navigatable) ?: EditSourceUtil.getDescriptor(target)
}
