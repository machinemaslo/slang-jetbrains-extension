package slanglsp;

import com.intellij.find.actions.ShowUsagesAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.redhat.devtools.lsp4ij.features.LSPPsiElement;

/** Declaration navigation uses the IDE's usage popup and single-result navigation. */
final class SlangUsageTarget extends LSPPsiElement {
    private final SmartPsiElementPointer<PsiElement> declaration;
    private final Editor editor;

    SlangUsageTarget(PsiElement element, Editor editor) {
        super(element.getContainingFile(), element.getTextRange());
        declaration = SmartPointerManager.createPointer(element);
        this.editor = editor;
    }

    @Override public void navigate(boolean requestFocus) {
        PsiElement target = declaration.getElement();
        if (target == null || editor == null || editor.isDisposed() || getProject().isDisposed()) return;
        ShowUsagesAction.startFindUsages(target,
                JBPopupFactory.getInstance().guessBestPopupLocation(editor), editor);
    }
    @Override public boolean canNavigate() {
        return editor != null && !editor.isDisposed() && declaration.getElement() != null;
    }
    @Override public boolean canNavigateToSource() { return canNavigate(); }
}
