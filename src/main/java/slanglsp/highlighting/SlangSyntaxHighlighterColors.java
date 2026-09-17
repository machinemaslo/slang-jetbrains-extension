package slanglsp.highlighting;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;

public class SlangSyntaxHighlighterColors {
    // Resolve through the active editor scheme, including custom theme overrides.
    public static final TextAttributesKey TYPE_NAME = TextAttributesKey.createTextAttributesKey(
            "SLANG_TYPE_NAME", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey FUNCTION_CALL = TextAttributesKey.createTextAttributesKey(
            "SLANG_FUNCTION_CALL", DefaultLanguageHighlighterColors.FUNCTION_CALL);
    public static final TextAttributesKey VARIABLE = TextAttributesKey.createTextAttributesKey(
            "SLANG_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE);
    public static final TextAttributesKey CONSTANT = TextAttributesKey.createTextAttributesKey(
            "SLANG_CONSTANT", DefaultLanguageHighlighterColors.CONSTANT);
    public static final TextAttributesKey KEYWORD = TextAttributesKey.createTextAttributesKey(
            "SLANG_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey MACRO_KEYWORD = TextAttributesKey.createTextAttributesKey(
            "SLANG_MACRO_KEYWORD", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey STRING = TextAttributesKey.createTextAttributesKey(
            "SLANG_STRING", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey NUMBER = TextAttributesKey.createTextAttributesKey(
            "SLANG_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey LINE_COMMENT = TextAttributesKey.createTextAttributesKey(
            "SLANG_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey BLOCK_COMMENT = TextAttributesKey.createTextAttributesKey(
            "SLANG_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT);
}
