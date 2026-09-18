package slanglsp.highlighting;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.util.PlatformUtils;

/** Semantic categories inherit the active scheme, never a hard-coded RGB palette. */
public final class SlangSyntaxHighlighterColors {
    private SlangSyntaxHighlighterColors() {}

    private static TextAttributesKey key(String slangName, String cppName, TextAttributesKey standard) {
        TextAttributesKey parent = standard;
        if (PlatformUtils.isCLion()) {
            parent = TextAttributesKey.find(cppName);
            // find() creates an empty placeholder when the C++ implementation has not
            // registered this key. A scheme may define OC.STRUCT_LIKE but omit OC.STRING,
            // OC.CPP_KEYWORD, etc.; those must still inherit Language Defaults.
            // Keep any existing native fallback/default, and never copy RGB values.
            if (parent.getFallbackAttributeKey() == null && parent.getDefaultAttributes() == null) {
                parent = TextAttributesKey.createTextAttributesKey(cppName, standard);
            }
        }
        return TextAttributesKey.createTextAttributesKey(slangName, parent);
    }

    public static final TextAttributesKey TYPE_NAME = key(
            "SLANG_TYPE_NAME", "OC.STRUCT_LIKE", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey INTERFACE = key(
            "SLANG_INTERFACE", "OC.STRUCT_LIKE", DefaultLanguageHighlighterColors.INTERFACE_NAME);
    public static final TextAttributesKey ENUM = key(
            "SLANG_ENUM", "OC.STRUCT_LIKE", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey TYPE_PARAMETER = key(
            "SLANG_TYPE_PARAMETER", "OC.TYPEDEF", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey NAMESPACE = key(
            "SLANG_NAMESPACE", "OC.NAMESPACE", DefaultLanguageHighlighterColors.CLASS_NAME);
    // The server has one function category and no declaration/call modifiers.
    public static final TextAttributesKey FUNCTION = key(
            "SLANG_FUNCTION_DECLARATION", "OC.FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);
    public static final TextAttributesKey FUNCTION_CALL = FUNCTION;
    public static final TextAttributesKey FUNCTION_DECLARATION = FUNCTION;
    public static final TextAttributesKey METHOD = key(
            "SLANG_METHOD", "OC.FUNCTION", DefaultLanguageHighlighterColors.INSTANCE_METHOD);
    public static final TextAttributesKey VARIABLE = key(
            "SLANG_VARIABLE", "OC.LOCAL_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE);
    public static final TextAttributesKey PARAMETER = key(
            "SLANG_PARAMETER", "OC.PARAMETER", DefaultLanguageHighlighterColors.PARAMETER);
    public static final TextAttributesKey FIELD = key(
            "SLANG_FIELD", "OC.STRUCT_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD);
    public static final TextAttributesKey CONSTANT = key(
            "SLANG_CONSTANT", "OC.ENUM_CONST", DefaultLanguageHighlighterColors.CONSTANT);
    public static final TextAttributesKey KEYWORD = key(
            "SLANG_KEYWORD", "OC.CPP_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey MACRO_KEYWORD = key(
            "SLANG_MACRO_KEYWORD", "OC.MACRONAME", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey DIRECTIVE = key(
            "SLANG_DIRECTIVE", "OC.DIRECTIVE", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey STRING = key(
            "SLANG_STRING", "OC.STRING", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey NUMBER = key(
            "SLANG_NUMBER", "OC.NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey LINE_COMMENT = key(
            "SLANG_LINE_COMMENT", "OC.LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey BLOCK_COMMENT = key(
            "SLANG_BLOCK_COMMENT", "OC.BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT);
    public static final TextAttributesKey OPERATOR = key(
            "SLANG_OPERATOR", "OC.OPERATION_SIGN", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey BRACES = key(
            "SLANG_BRACES", "OC.BRACES", DefaultLanguageHighlighterColors.BRACES);
    public static final TextAttributesKey BRACKETS = key(
            "SLANG_BRACKETS", "OC.BRACKETS", DefaultLanguageHighlighterColors.BRACKETS);
    public static final TextAttributesKey PARENTHESES = key(
            "SLANG_PARENTHESES", "OC.PARENTHS", DefaultLanguageHighlighterColors.PARENTHESES);
    public static final TextAttributesKey COMMA = key(
            "SLANG_COMMA", "OC.COMMA", DefaultLanguageHighlighterColors.COMMA);
    public static final TextAttributesKey DOT = key(
            "SLANG_DOT", "OC.DOT", DefaultLanguageHighlighterColors.DOT);
    public static final TextAttributesKey SEMICOLON = key(
            "SLANG_SEMICOLON", "OC.SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON);
}
