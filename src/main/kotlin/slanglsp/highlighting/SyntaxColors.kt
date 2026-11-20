package slanglsp.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/** Semantic categories inherit the active scheme; bundled schemes supply Slang defaults. */
object SyntaxColors {
    private fun key(name: String, fallback: TextAttributesKey): TextAttributesKey {
        return TextAttributesKey.createTextAttributesKey(name, fallback)
    }

    val TYPE_NAME: TextAttributesKey = key("SLANG_TYPE_NAME", DefaultLanguageHighlighterColors.CLASS_NAME)
    val INTERFACE: TextAttributesKey = key("SLANG_INTERFACE", DefaultLanguageHighlighterColors.INTERFACE_NAME)
    val ENUM: TextAttributesKey = key("SLANG_ENUM", DefaultLanguageHighlighterColors.CLASS_NAME)
    val TYPE_PARAMETER: TextAttributesKey = key("SLANG_TYPE_PARAMETER", DefaultLanguageHighlighterColors.CLASS_NAME)
    val NAMESPACE: TextAttributesKey = key("SLANG_NAMESPACE", DefaultLanguageHighlighterColors.CLASS_NAME)

    // The server has one function category and no declaration/call modifiers.
    val FUNCTION: TextAttributesKey =
        key("SLANG_FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)

    val METHOD: TextAttributesKey = key("SLANG_METHOD", DefaultLanguageHighlighterColors.INSTANCE_METHOD)
    val VARIABLE: TextAttributesKey = key("SLANG_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
    val PARAMETER: TextAttributesKey = key("SLANG_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER)
    val FIELD: TextAttributesKey = key("SLANG_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val CONSTANT: TextAttributesKey = key("SLANG_CONSTANT", DefaultLanguageHighlighterColors.CONSTANT)
    val KEYWORD: TextAttributesKey = key("SLANG_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val MACRO_KEYWORD: TextAttributesKey = key("SLANG_MACRO_KEYWORD", DefaultLanguageHighlighterColors.METADATA)
    val DIRECTIVE: TextAttributesKey = key("SLANG_DIRECTIVE", DefaultLanguageHighlighterColors.METADATA)
    val STRING: TextAttributesKey = key("SLANG_STRING", DefaultLanguageHighlighterColors.STRING)
    val NUMBER: TextAttributesKey = key("SLANG_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val LINE_COMMENT: TextAttributesKey = key("SLANG_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val BLOCK_COMMENT: TextAttributesKey = key("SLANG_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)
    val OPERATOR: TextAttributesKey = key("SLANG_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    val BRACES: TextAttributesKey = key("SLANG_BRACES", DefaultLanguageHighlighterColors.BRACES)
    val BRACKETS: TextAttributesKey = key("SLANG_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
    val PARENTHESES: TextAttributesKey = key("SLANG_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)
    val COMMA: TextAttributesKey = key("SLANG_COMMA", DefaultLanguageHighlighterColors.COMMA)
    val DOT: TextAttributesKey = key("SLANG_DOT", DefaultLanguageHighlighterColors.DOT)
    val SEMICOLON: TextAttributesKey = key("SLANG_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON)
}
