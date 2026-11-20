package slanglsp.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon
import slanglsp.SlangBundle
import slanglsp.language.SlangFileType

class HighlightingSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon {
        return SlangFileType.icon
    }

    override fun getHighlighter(): SyntaxHighlighter {
        return SlangSyntaxHighlighter
    }

    override fun getDisplayName(): String {
        return SlangBundle.message("settings.slang.display.name")
    }

    override fun getAttributeDescriptors(): Array<AttributesDescriptor?> {
        return Colors.descriptors
    }

    override fun getColorDescriptors(): Array<ColorDescriptor?> {
        return ColorDescriptor.EMPTY_ARRAY
    }

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> {
        return Colors.tags
    }

    override fun getDemoText(): String {
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
                
                """
            .trimIndent()
    }

    private object Colors {
        val tags = mapOf(
            "type" to SyntaxColors.TYPE_NAME,
            "interface" to SyntaxColors.INTERFACE,
            "enum" to SyntaxColors.ENUM,
            "typeparam" to SyntaxColors.TYPE_PARAMETER,
            "namespace" to SyntaxColors.NAMESPACE,
            "decl" to SyntaxColors.FUNCTION,
            "call" to SyntaxColors.FUNCTION,
            "param" to SyntaxColors.PARAMETER,
            "field" to SyntaxColors.FIELD,
            "constant" to SyntaxColors.CONSTANT,
            "method" to SyntaxColors.METHOD,
            "macro" to SyntaxColors.MACRO_KEYWORD,
        )

        val descriptors =
            arrayOf<AttributesDescriptor?>(
                AttributesDescriptor(SlangBundle.message("color.keyword"), SyntaxColors.KEYWORD),
                AttributesDescriptor(
                    SlangBundle.message("color.types.class.struct.and.built.in.type"),
                    SyntaxColors.TYPE_NAME,
                ),
                AttributesDescriptor(SlangBundle.message("color.types.interface"), SyntaxColors.INTERFACE),
                AttributesDescriptor(SlangBundle.message("color.types.enum"), SyntaxColors.ENUM),
                AttributesDescriptor(SlangBundle.message("color.types.type.parameter"), SyntaxColors.TYPE_PARAMETER),
                AttributesDescriptor(SlangBundle.message("color.namespace.or.module"), SyntaxColors.NAMESPACE),
                AttributesDescriptor(
                    SlangBundle.message("color.function.declarations.and.calls"),
                    SyntaxColors.FUNCTION,
                ),
                AttributesDescriptor(SlangBundle.message("color.method"), SyntaxColors.METHOD),
                AttributesDescriptor(SlangBundle.message("color.local.variable"), SyntaxColors.VARIABLE),
                AttributesDescriptor(SlangBundle.message("color.parameter"), SyntaxColors.PARAMETER),
                AttributesDescriptor(SlangBundle.message("color.field"), SyntaxColors.FIELD),
                AttributesDescriptor(SlangBundle.message("color.constant"), SyntaxColors.CONSTANT),
                AttributesDescriptor(SlangBundle.message("color.macro"), SyntaxColors.MACRO_KEYWORD),
                AttributesDescriptor(SlangBundle.message("color.preprocessor.directive"), SyntaxColors.DIRECTIVE),
                AttributesDescriptor(SlangBundle.message("color.punctuation.operator"), SyntaxColors.OPERATOR),
                AttributesDescriptor(SlangBundle.message("color.punctuation.braces"), SyntaxColors.BRACES),
                AttributesDescriptor(SlangBundle.message("color.punctuation.brackets"), SyntaxColors.BRACKETS),
                AttributesDescriptor(SlangBundle.message("color.punctuation.parentheses"), SyntaxColors.PARENTHESES),
                AttributesDescriptor(SlangBundle.message("color.punctuation.comma"), SyntaxColors.COMMA),
                AttributesDescriptor(SlangBundle.message("color.punctuation.dot"), SyntaxColors.DOT),
                AttributesDescriptor(SlangBundle.message("color.punctuation.semicolon"), SyntaxColors.SEMICOLON),
                AttributesDescriptor(SlangBundle.message("color.string"), SyntaxColors.STRING),
                AttributesDescriptor(SlangBundle.message("color.number"), SyntaxColors.NUMBER),
                AttributesDescriptor(SlangBundle.message("color.line.comment"), SyntaxColors.LINE_COMMENT),
                AttributesDescriptor(SlangBundle.message("color.block.comment"), SyntaxColors.BLOCK_COMMENT),
            )
    }
}
