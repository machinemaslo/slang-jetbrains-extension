package slanglsp.highlighting;

import slanglsp.SlangBundle;

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
            new AttributesDescriptor(SlangBundle.message("color.keyword"), SlangSyntaxHighlighterColors.KEYWORD),
            new AttributesDescriptor(SlangBundle.message("color.types.class.struct.and.built.in.type"), SlangSyntaxHighlighterColors.TYPE_NAME),
            new AttributesDescriptor(SlangBundle.message("color.types.interface"), SlangSyntaxHighlighterColors.INTERFACE),
            new AttributesDescriptor(SlangBundle.message("color.types.enum"), SlangSyntaxHighlighterColors.ENUM),
            new AttributesDescriptor(SlangBundle.message("color.types.type.parameter"), SlangSyntaxHighlighterColors.TYPE_PARAMETER),
            new AttributesDescriptor(SlangBundle.message("color.namespace.or.module"), SlangSyntaxHighlighterColors.NAMESPACE),
            new AttributesDescriptor(SlangBundle.message("color.function.declarations.and.calls"), SlangSyntaxHighlighterColors.FUNCTION),
            new AttributesDescriptor(SlangBundle.message("color.method"), SlangSyntaxHighlighterColors.METHOD),
            new AttributesDescriptor(SlangBundle.message("color.local.variable"), SlangSyntaxHighlighterColors.VARIABLE),
            new AttributesDescriptor(SlangBundle.message("color.parameter"), SlangSyntaxHighlighterColors.PARAMETER),
            new AttributesDescriptor(SlangBundle.message("color.field"), SlangSyntaxHighlighterColors.FIELD),
            new AttributesDescriptor(SlangBundle.message("color.constant"), SlangSyntaxHighlighterColors.CONSTANT),
            new AttributesDescriptor(SlangBundle.message("color.macro"), SlangSyntaxHighlighterColors.MACRO_KEYWORD),
            new AttributesDescriptor(SlangBundle.message("color.preprocessor.directive"), SlangSyntaxHighlighterColors.DIRECTIVE),
            new AttributesDescriptor(SlangBundle.message("color.punctuation.operator"), SlangSyntaxHighlighterColors.OPERATOR),
            new AttributesDescriptor(SlangBundle.message("color.punctuation.braces"), SlangSyntaxHighlighterColors.BRACES),
            new AttributesDescriptor(SlangBundle.message("color.punctuation.brackets"), SlangSyntaxHighlighterColors.BRACKETS),
            new AttributesDescriptor(SlangBundle.message("color.punctuation.parentheses"), SlangSyntaxHighlighterColors.PARENTHESES),
            new AttributesDescriptor(SlangBundle.message("color.punctuation.comma"), SlangSyntaxHighlighterColors.COMMA),
            new AttributesDescriptor(SlangBundle.message("color.punctuation.dot"), SlangSyntaxHighlighterColors.DOT),
            new AttributesDescriptor(SlangBundle.message("color.punctuation.semicolon"), SlangSyntaxHighlighterColors.SEMICOLON),
            new AttributesDescriptor(SlangBundle.message("color.string"), SlangSyntaxHighlighterColors.STRING),
            new AttributesDescriptor(SlangBundle.message("color.number"), SlangSyntaxHighlighterColors.NUMBER),
            new AttributesDescriptor(SlangBundle.message("color.line.comment"), SlangSyntaxHighlighterColors.LINE_COMMENT),
            new AttributesDescriptor(SlangBundle.message("color.block.comment"), SlangSyntaxHighlighterColors.BLOCK_COMMENT)
    };

    @Override public Icon getIcon() { return SlangFileType.INSTANCE.getIcon(); }
    @Override public @NotNull SyntaxHighlighter getHighlighter() { return new SlangSyntaxHighlighter(); }
    @Override public @NotNull String getDisplayName() { return SlangBundle.message("settings.slang.display.name"); }
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
