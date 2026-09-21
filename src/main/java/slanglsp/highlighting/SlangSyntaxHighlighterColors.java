package slanglsp.highlighting;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;

/** Semantic categories inherit the active scheme, never a hard-coded RGB palette. */
public final class SlangSyntaxHighlighterColors {
    private SlangSyntaxHighlighterColors() {}

    private static TextAttributesKey key(String name, TextAttributesKey fallback) {
        return TextAttributesKey.createTextAttributesKey(name, fallback);
    }

    public static final TextAttributesKey TYPE_NAME = key(
            "SLANG_TYPE_NAME", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey INTERFACE = key(
            "SLANG_INTERFACE", DefaultLanguageHighlighterColors.INTERFACE_NAME);
    public static final TextAttributesKey ENUM = key(
            "SLANG_ENUM", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey TYPE_PARAMETER = key(
            "SLANG_TYPE_PARAMETER", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey NAMESPACE = key(
            "SLANG_NAMESPACE", DefaultLanguageHighlighterColors.CLASS_NAME);
    // The server has one function category and no declaration/call modifiers.
    public static final TextAttributesKey FUNCTION = key(
            "SLANG_FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);
    public static final TextAttributesKey FUNCTION_CALL = FUNCTION;
    public static final TextAttributesKey FUNCTION_DECLARATION = FUNCTION;
    public static final TextAttributesKey METHOD = key(
            "SLANG_METHOD", DefaultLanguageHighlighterColors.INSTANCE_METHOD);
    public static final TextAttributesKey VARIABLE = key(
            "SLANG_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE);
    public static final TextAttributesKey PARAMETER = key(
            "SLANG_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER);
    public static final TextAttributesKey FIELD = key(
            "SLANG_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD);
    public static final TextAttributesKey CONSTANT = key(
            "SLANG_CONSTANT", DefaultLanguageHighlighterColors.CONSTANT);
    public static final TextAttributesKey KEYWORD = key(
            "SLANG_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey MACRO_KEYWORD = key(
            "SLANG_MACRO_KEYWORD", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey DIRECTIVE = key(
            "SLANG_DIRECTIVE", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey STRING = key(
            "SLANG_STRING", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey NUMBER = key(
            "SLANG_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey LINE_COMMENT = key(
            "SLANG_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey BLOCK_COMMENT = key(
            "SLANG_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT);
    public static final TextAttributesKey OPERATOR = key(
            "SLANG_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey BRACES = key(
            "SLANG_BRACES", DefaultLanguageHighlighterColors.BRACES);
    public static final TextAttributesKey BRACKETS = key(
            "SLANG_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS);
    public static final TextAttributesKey PARENTHESES = key(
            "SLANG_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES);
    public static final TextAttributesKey COMMA = key(
            "SLANG_COMMA", DefaultLanguageHighlighterColors.COMMA);
    public static final TextAttributesKey DOT = key(
            "SLANG_DOT", DefaultLanguageHighlighterColors.DOT);
    public static final TextAttributesKey SEMICOLON = key(
            "SLANG_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON);
}
