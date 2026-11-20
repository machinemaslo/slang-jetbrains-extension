package slanglsp.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey

internal fun semanticColor(type: String, modifiers: List<String?>): TextAttributesKey? {
    return when (type) {
        "type",
        "class",
        "struct" -> SyntaxColors.TYPE_NAME
        "interface" -> SyntaxColors.INTERFACE
        "enum" -> SyntaxColors.ENUM
        "typeParameter" -> SyntaxColors.TYPE_PARAMETER
        "namespace" -> SyntaxColors.NAMESPACE
        "parameter" -> SyntaxColors.PARAMETER
        "variable" -> if ("readonly" in modifiers) SyntaxColors.CONSTANT else SyntaxColors.VARIABLE
        "property" -> SyntaxColors.FIELD
        "enumMember" -> SyntaxColors.CONSTANT
        "function" -> SyntaxColors.FUNCTION
        "method" -> SyntaxColors.METHOD
        "macro" -> SyntaxColors.MACRO_KEYWORD
        "keyword",
        "modifier" -> SyntaxColors.KEYWORD
        "comment" -> SyntaxColors.LINE_COMMENT
        "string" -> SyntaxColors.STRING
        "number" -> SyntaxColors.NUMBER
        "operator" -> SyntaxColors.OPERATOR
        else -> null
    }
}
