package slanglsp.editor.typing

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import slanglsp.language.SlangLexer

class BracePairMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair?> {
        return PAIRS
    }

    override fun isPairedBracesAllowedBeforeType(leftBraceType: IElementType, contextType: IElementType?): Boolean {
        return contextType == null ||
            contextType === TokenType.WHITE_SPACE ||
            contextType === SlangLexer.COMMENT ||
            contextType === SlangLexer.BLOCK_COMMENT ||
            contextType === SlangLexer.SEMICOLON ||
            contextType === SlangLexer.COMMA ||
            contextType === SlangLexer.RBRACE ||
            contextType === SlangLexer.RPAREN ||
            contextType === SlangLexer.RBRACKET
    }

    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int {
        return openingBraceOffset
    }
}

private val PAIRS =
    arrayOf<BracePair?>(
        BracePair(SlangLexer.LBRACE, SlangLexer.RBRACE, true),
        BracePair(SlangLexer.LPAREN, SlangLexer.RPAREN, false),
        BracePair(SlangLexer.LBRACKET, SlangLexer.RBRACKET, false))
