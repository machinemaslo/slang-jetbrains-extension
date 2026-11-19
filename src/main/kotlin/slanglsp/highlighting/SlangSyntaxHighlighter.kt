package slanglsp.highlighting

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType
import slanglsp.language.SlangLexer

class SlangSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer {
        return SlangLexer()
    }

    override fun getTokenHighlights(tokenType: IElementType): Array<out TextAttributesKey> {
        if (tokenType == SlangLexer.KEYWORD) {
            return arrayOf(SyntaxColors.KEYWORD)
        }

        if (tokenType == SlangLexer.MODULE_NAME) {
            return pack(SyntaxColors.NAMESPACE)
        }

        if (tokenType == SlangLexer.BUILTIN_TYPE) {
            return pack(SyntaxColors.TYPE_NAME)
        }

        if (tokenType == SlangLexer.DIRECTIVE) {
            return pack(SyntaxColors.DIRECTIVE)
        }

        if (tokenType == SlangLexer.OPERATOR) {
            return pack(SyntaxColors.OPERATOR)
        }

        if (tokenType === SlangLexer.LBRACE || tokenType === SlangLexer.RBRACE) {
            return pack(SyntaxColors.BRACES)
        }

        if (tokenType === SlangLexer.LBRACKET || tokenType === SlangLexer.RBRACKET) {
            return pack(SyntaxColors.BRACKETS)
        }

        if (tokenType === SlangLexer.LPAREN || tokenType === SlangLexer.RPAREN) {
            return pack(SyntaxColors.PARENTHESES)
        }

        if (tokenType == SlangLexer.COMMA) {
            return pack(SyntaxColors.COMMA)
        }

        if (tokenType == SlangLexer.DOT) {
            return pack(SyntaxColors.DOT)
        }

        if (tokenType == SlangLexer.SEMICOLON) {
            return pack(SyntaxColors.SEMICOLON)
        }

        if (tokenType == SlangLexer.TYPE) {
            return arrayOf(SyntaxColors.TYPE_NAME)
        }

        if (tokenType == SlangLexer.FUNCTION) {
            return arrayOf(SyntaxColors.FUNCTION_CALL)
        }

        if (tokenType == SlangLexer.VARIABLE) {
            return arrayOf(SyntaxColors.VARIABLE)
        }

        if (tokenType == SlangLexer.STRING) {
            return arrayOf(SyntaxColors.STRING)
        }

        if (tokenType == SlangLexer.NUMBER) {
            return arrayOf(SyntaxColors.NUMBER)
        }

        if (tokenType == SlangLexer.COMMENT) {
            return arrayOf(SyntaxColors.LINE_COMMENT)
        }

        if (tokenType == SlangLexer.BLOCK_COMMENT) {
            return arrayOf(SyntaxColors.BLOCK_COMMENT)
        }

        return emptyArray()
    }
}
