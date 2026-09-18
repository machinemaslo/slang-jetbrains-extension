package slanglsp;

import com.intellij.find.FindManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.redhat.devtools.lsp4ij.features.LSPPsiElement;

/** Declaration navigation starts the IDE's cancellable, streaming Find Usages search. */
final class SlangUsageTarget extends LSPPsiElement {
    private final SmartPsiElementPointer<PsiElement> declaration;

    SlangUsageTarget(PsiElement element) {
        super(element.getContainingFile(), element.getTextRange());
        declaration = SmartPointerManager.createPointer(element);
    }

    @Override public void navigate(boolean requestFocus) {
        PsiElement target = declaration.getElement();
        if (target != null) FindManager.getInstance(getProject()).findUsages(target, false);
    }
    @Override public boolean canNavigate() { return declaration.getElement() != null; }
    @Override public boolean canNavigateToSource() { return canNavigate(); }
}
