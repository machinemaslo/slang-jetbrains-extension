package slanglsp.editor.navigation

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import slanglsp.SlangBundle
import slanglsp.language.SlangLanguage
import slanglsp.language.SlangLexer

class SymbolFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner {
        return DefaultWordsScanner(
            SlangLexer(),
            TokenSet.create(SlangLexer.TYPE, SlangLexer.FUNCTION, SlangLexer.VARIABLE),
            TokenSet.create(SlangLexer.COMMENT, SlangLexer.BLOCK_COMMENT),
            TokenSet.create(SlangLexer.STRING))
    }

    override fun canFindUsagesFor(element: PsiElement): Boolean {
        return isSlangIdentifier(element)
    }

    override fun getHelpId(element: PsiElement): String? {
        return null
    }

    override fun getType(element: PsiElement): String {
        return SlangBundle.message("symbol.description")
    }

    override fun getDescriptiveName(element: PsiElement): String {
        return element.text
    }

    override fun getNodeText(element: PsiElement, fullName: Boolean): String {
        return element.text
    }
}

internal fun isSlangIdentifier(element: PsiElement?): Boolean {
    val file = element?.containingFile ?: return false
    if (file.language !== SlangLanguage.INSTANCE) {
        return false
    }
    val type = file.findElementAt(element.textOffset)?.node?.elementType ?: return false
    return type === SlangLexer.TYPE || type === SlangLexer.FUNCTION || type === SlangLexer.VARIABLE
}
