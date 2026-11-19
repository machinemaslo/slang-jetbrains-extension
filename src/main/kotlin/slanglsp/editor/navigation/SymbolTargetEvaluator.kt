package slanglsp.editor.navigation

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.psi.PsiElement

/** Exposes token-level identifiers to the IDE's Find/Show Usages target lookup. */
class SymbolTargetEvaluator : TargetElementEvaluatorEx2() {
    override fun getNamedElement(element: PsiElement): PsiElement? {
        if (!isSlangIdentifier(element)) {
            return null
        }

        // UsageSearch resolves the symbol through slangd after the action starts.
        return element.containingFile.findElementAt(element.textOffset)
    }
}
