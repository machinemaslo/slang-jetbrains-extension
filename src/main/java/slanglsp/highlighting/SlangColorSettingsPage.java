package slanglsp.highlighting;

import slanglsp.SlangBundle;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.jetbrains.annotations.NotNull;
import slanglsp.language.SlangFileType;

import javax.swing.Icon;
import java.util.Map;

public final class SlangColorSettingsPage implements ColorSettingsPage {
    private static final AttributesDescriptor[] DESCRIPTORS = {
            new AttributesDescriptor(SlangBundle.message("color.keyword"), SyntaxColors.KEYWORD),
            new AttributesDescriptor(
                    SlangBundle.message("color.types.class.struct.and.built.in.type"), SyntaxColors.TYPE_NAME),
            new AttributesDescriptor(SlangBundle.message("color.types.interface"), SyntaxColors.INTERFACE),
            new AttributesDescriptor(SlangBundle.message("color.types.enum"), SyntaxColors.ENUM),
            new AttributesDescriptor(SlangBundle.message("color.types.type.parameter"), SyntaxColors.TYPE_PARAMETER),
            new AttributesDescriptor(SlangBundle.message("color.namespace.or.module"), SyntaxColors.NAMESPACE),
            new AttributesDescriptor(
                    SlangBundle.message("color.function.declarations.and.calls"), SyntaxColors.FUNCTION),
            new AttributesDescriptor(SlangBundle.message("color.method"), SyntaxColors.METHOD),
            new AttributesDescriptor(SlangBundle.message("color.local.variable"), SyntaxColors.VARIABLE),
            new AttributesDescriptor(SlangBundle.message("color.parameter"), SyntaxColors.PARAMETER),
            new AttributesDescriptor(SlangBundle.message("color.field"), SyntaxColors.FIELD),
            new AttributesDescriptor(SlangBundle.message("color.constant"), SyntaxColors.CONSTANT),
            new AttributesDescriptor(SlangBundle.message("color.macro"), SyntaxColors.MACRO_KEYWORD),
            new AttributesDescriptor(SlangBundle.message("color.preprocessor.directive"), SyntaxColors.DIRECTIVE),
            new AttributesDescriptor(SlangBundle.message("color.punctuation.operator"), SyntaxColors.OPERATOR),
            new AttributesDescriptor(SlangBundle.message("color.punctuation.braces"), SyntaxColors.BRACES),
            new AttributesDescriptor(SlangBundle.message("color.punctuation.brackets"), SyntaxColors.BRACKETS),
            new AttributesDescriptor(SlangBundle.message("color.punctuation.parentheses"), SyntaxColors.PARENTHESES),
            new AttributesDescriptor(SlangBundle.message("color.punctuation.comma"), SyntaxColors.COMMA),
            new AttributesDescriptor(SlangBundle.message("color.punctuation.dot"), SyntaxColors.DOT),
            new AttributesDescriptor(SlangBundle.message("color.punctuation.semicolon"), SyntaxColors.SEMICOLON),
            new AttributesDescriptor(SlangBundle.message("color.string"), SyntaxColors.STRING),
            new AttributesDescriptor(SlangBundle.message("color.number"), SyntaxColors.NUMBER),
            new AttributesDescriptor(SlangBundle.message("color.line.comment"), SyntaxColors.LINE_COMMENT),
            new AttributesDescriptor(SlangBundle.message("color.block.comment"), SyntaxColors.BLOCK_COMMENT)};

    @Override
    public Icon getIcon() {
        return SlangFileType.INSTANCE.getIcon();
    }

    @Override
    public @NotNull SyntaxHighlighter getHighlighter() {
        return new SlangSyntaxHighlighter();
    }

    @Override
    public @NotNull String getDisplayName() {
        return SlangBundle.message("settings.slang.display.name");
    }

    @Override
    public AttributesDescriptor @NotNull[] getAttributeDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    public ColorDescriptor @NotNull[] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return Map.ofEntries(Map.entry("type", SyntaxColors.TYPE_NAME), Map.entry("interface", SyntaxColors.INTERFACE),
                Map.entry("enum", SyntaxColors.ENUM), Map.entry("typeparam", SyntaxColors.TYPE_PARAMETER),
                Map.entry("namespace", SyntaxColors.NAMESPACE), Map.entry("decl", SyntaxColors.FUNCTION_DECLARATION),
                Map.entry("call", SyntaxColors.FUNCTION_CALL), Map.entry("param", SyntaxColors.PARAMETER),
                Map.entry("field", SyntaxColors.FIELD), Map.entry("constant", SyntaxColors.CONSTANT),
                Map.entry("method", SyntaxColors.METHOD), Map.entry("macro", SyntaxColors.MACRO_KEYWORD));
    }

    @Override
    public @NotNull String getDemoText() {
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
