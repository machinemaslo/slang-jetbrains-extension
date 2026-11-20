package slanglsp.editor.navigation

import com.intellij.find.actions.ShowUsagesAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer

/** Delegate declaration-to-usage navigation to the IDE's native Show Usages workflow. */
internal class UsageNavigationTarget(element: PsiElement, private val editor: Editor?) :
    NavigationPsiElement(element.containingFile, element.textRange) {
    private val declaration: SmartPsiElementPointer<PsiElement?> =
        SmartPointerManager.createPointer<PsiElement?>(element)

    override fun navigate(requestFocus: Boolean) {
        if (!canNavigate() || project.isDisposed) {
            return
        }

        val target = declaration.getElement() ?: return

        ShowUsagesAction.startFindUsages(target, JBPopupFactory.getInstance().guessBestPopupLocation(editor!!), editor)
    }

    override fun canNavigate(): Boolean {
        return editor != null && !editor.isDisposed && declaration.getElement() != null
    }

    override fun canNavigateToSource(): Boolean {
        return canNavigate()
    }
}
