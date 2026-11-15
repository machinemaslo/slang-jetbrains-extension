package slanglsp.editor;

import com.intellij.codeInsight.TargetElementEvaluatorEx2;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Exposes token-level identifiers to the IDE's Find/Show Usages target lookup. */
public final class SlangTargetElementEvaluator extends TargetElementEvaluatorEx2 {
    @Override
    @Nullable
    public PsiElement getNamedElement(@NotNull PsiElement element) {
        if (!SlangFindUsagesProvider.isIdentifier(element)) {
            return null;
        }

        // UsageSearch resolves the symbol through slangd after the action starts.
        return element.getContainingFile().findElementAt(element.getTextOffset());
    }
}
