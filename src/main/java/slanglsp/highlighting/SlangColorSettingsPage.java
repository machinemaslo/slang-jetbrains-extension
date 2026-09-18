package slanglsp.highlighting;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.jetbrains.annotations.NotNull;
import slanglsp.SlangFileType;
import slanglsp.SlangSyntaxHighlighter;

import javax.swing.Icon;
import java.util.Map;

public final class SlangColorSettingsPage implements ColorSettingsPage {
    private static final AttributesDescriptor[] DESCRIPTORS = {
            new AttributesDescriptor("Keyword", SlangSyntaxHighlighterColors.KEYWORD),
            new AttributesDescriptor("Types//Class, struct and built-in type", SlangSyntaxHighlighterColors.TYPE_NAME),
            new AttributesDescriptor("Types//Interface", SlangSyntaxHighlighterColors.INTERFACE),
            new AttributesDescriptor("Types//Enum", SlangSyntaxHighlighterColors.ENUM),
            new AttributesDescriptor("Types//Type parameter", SlangSyntaxHighlighterColors.TYPE_PARAMETER),
            new AttributesDescriptor("Namespace or module", SlangSyntaxHighlighterColors.NAMESPACE),
            new AttributesDescriptor("Function (declarations and calls)", SlangSyntaxHighlighterColors.FUNCTION),
            new AttributesDescriptor("Method", SlangSyntaxHighlighterColors.METHOD),
            new AttributesDescriptor("Local variable", SlangSyntaxHighlighterColors.VARIABLE),
            new AttributesDescriptor("Parameter", SlangSyntaxHighlighterColors.PARAMETER),
            new AttributesDescriptor("Field", SlangSyntaxHighlighterColors.FIELD),
            new AttributesDescriptor("Constant", SlangSyntaxHighlighterColors.CONSTANT),
            new AttributesDescriptor("Macro", SlangSyntaxHighlighterColors.MACRO_KEYWORD),
            new AttributesDescriptor("Preprocessor directive", SlangSyntaxHighlighterColors.DIRECTIVE),
            new AttributesDescriptor("Punctuation//Operator", SlangSyntaxHighlighterColors.OPERATOR),
            new AttributesDescriptor("Punctuation//Braces", SlangSyntaxHighlighterColors.BRACES),
            new AttributesDescriptor("Punctuation//Brackets", SlangSyntaxHighlighterColors.BRACKETS),
            new AttributesDescriptor("Punctuation//Parentheses", SlangSyntaxHighlighterColors.PARENTHESES),
            new AttributesDescriptor("Punctuation//Comma", SlangSyntaxHighlighterColors.COMMA),
            new AttributesDescriptor("Punctuation//Dot", SlangSyntaxHighlighterColors.DOT),
            new AttributesDescriptor("Punctuation//Semicolon", SlangSyntaxHighlighterColors.SEMICOLON),
            new AttributesDescriptor("String", SlangSyntaxHighlighterColors.STRING),
            new AttributesDescriptor("Number", SlangSyntaxHighlighterColors.NUMBER),
            new AttributesDescriptor("Line comment", SlangSyntaxHighlighterColors.LINE_COMMENT),
            new AttributesDescriptor("Block comment", SlangSyntaxHighlighterColors.BLOCK_COMMENT)
    };

    @Override public Icon getIcon() { return SlangFileType.INSTANCE.getIcon(); }
    @Override public @NotNull SyntaxHighlighter getHighlighter() { return new SlangSyntaxHighlighter(); }
    @Override public @NotNull String getDisplayName() { return "Slang"; }
    @Override public AttributesDescriptor @NotNull [] getAttributeDescriptors() { return DESCRIPTORS; }
    @Override public ColorDescriptor @NotNull [] getColorDescriptors() { return ColorDescriptor.EMPTY_ARRAY; }
    @Override public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return Map.ofEntries(
                Map.entry("type", SlangSyntaxHighlighterColors.TYPE_NAME),
                Map.entry("interface", SlangSyntaxHighlighterColors.INTERFACE),
                Map.entry("enum", SlangSyntaxHighlighterColors.ENUM),
                Map.entry("typeparam", SlangSyntaxHighlighterColors.TYPE_PARAMETER),
                Map.entry("namespace", SlangSyntaxHighlighterColors.NAMESPACE),
                Map.entry("decl", SlangSyntaxHighlighterColors.FUNCTION_DECLARATION),
                Map.entry("call", SlangSyntaxHighlighterColors.FUNCTION_CALL),
                Map.entry("param", SlangSyntaxHighlighterColors.PARAMETER),
                Map.entry("field", SlangSyntaxHighlighterColors.FIELD),
                Map.entry("constant", SlangSyntaxHighlighterColors.CONSTANT),
                Map.entry("method", SlangSyntaxHighlighterColors.METHOD),
                Map.entry("macro", SlangSyntaxHighlighterColors.MACRO_KEYWORD));
    }
    @Override public @NotNull String getDemoText() {
        return """
                module <namespace>lighting</namespace>;
                interface <interface>IShading</interface> {}
                enum <enum>ShadingMode</enum> { <constant>Diffuse</constant>, <constant>Specular</constant> }
                struct <type>Container</type><<typeparam>T</typeparam>> { <typeparam>T</typeparam> <field>value</field>; }
                // Surface shading
                /* Material properties */
                struct <type>Material</type> {
                    float <field>roughness</field>;
                    float <method>evaluate</method>() { return <field>roughness</field>; }
                }
                static const float <constant>PI</constant> = 3.14159;
                #define <macro>LABEL</macro> "lighting"
                float <decl>shade</decl>(float <param>intensity</param>) {
                    uint3 threadId = uint3(0, 1, 2);
                    float result = <param>intensity</param> * <constant>PI</constant>;
                    return result;
                }
                float <decl>main</decl>() { return <call>shade</call>(1.0); }
                """;
    }
}
