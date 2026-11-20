package slanglsp.highlighting

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.psi.tree.IElementType
import slanglsp.language.SlangLexer

internal object SlangSyntaxHighlighter : SyntaxHighlighter {
    private val noHighlights = emptyArray<TextAttributesKey>()
    private val highlights = buildMap {
        fun register(color: TextAttributesKey, vararg tokens: IElementType) {
            val keys = arrayOf(color)
            for (token in tokens) {
                put(token, keys)
            }
        }

        register(SyntaxColors.KEYWORD, SlangLexer.KEYWORD)
        register(SyntaxColors.NAMESPACE, SlangLexer.MODULE_NAME)
        register(SyntaxColors.TYPE_NAME, SlangLexer.BUILTIN_TYPE, SlangLexer.TYPE)
        register(SyntaxColors.DIRECTIVE, SlangLexer.DIRECTIVE)
        register(SyntaxColors.OPERATOR, SlangLexer.OPERATOR)
        register(SyntaxColors.BRACES, SlangLexer.LBRACE, SlangLexer.RBRACE)
        register(SyntaxColors.BRACKETS, SlangLexer.LBRACKET, SlangLexer.RBRACKET)
        register(SyntaxColors.PARENTHESES, SlangLexer.LPAREN, SlangLexer.RPAREN)
        register(SyntaxColors.COMMA, SlangLexer.COMMA)
        register(SyntaxColors.DOT, SlangLexer.DOT)
        register(SyntaxColors.SEMICOLON, SlangLexer.SEMICOLON)
        register(SyntaxColors.FUNCTION, SlangLexer.FUNCTION)
        register(SyntaxColors.VARIABLE, SlangLexer.VARIABLE)
        register(SyntaxColors.STRING, SlangLexer.STRING)
        register(SyntaxColors.NUMBER, SlangLexer.NUMBER)
        register(SyntaxColors.LINE_COMMENT, SlangLexer.COMMENT)
        register(SyntaxColors.BLOCK_COMMENT, SlangLexer.BLOCK_COMMENT)
    }

    override fun getHighlightingLexer(): Lexer {
        return SlangLexer()
    }

    override fun getTokenHighlights(tokenType: IElementType): Array<out TextAttributesKey> {
        return highlights[tokenType] ?: noHighlights
    }
}
