package slanglsp.editor.navigation

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

class IndexedFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean {
        return isSlangIdentifier(element)
    }

    override fun createFindUsagesHandler(element: PsiElement, highlight: Boolean): FindUsagesHandler {
        return object : FindUsagesHandler(element) {
            override fun processElementUsages(
                target: PsiElement,
                processor: Processor<in UsageInfo?>,
                options: FindUsagesOptions
            ): Boolean {
                return UsageSearch.process(target, options.searchScope, processor)
            }
        }
    }
}
