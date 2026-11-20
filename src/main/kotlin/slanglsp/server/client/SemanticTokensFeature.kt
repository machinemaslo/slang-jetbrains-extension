package slanglsp.server.client

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.client.features.LSPSemanticTokensFeature
import slanglsp.highlighting.SyntaxColors
import slanglsp.language.SlangLanguage

// LSP4IJ exposes custom client features through experimental extension points.
@Suppress("UnstableApiUsage")
internal class SemanticTokensFeature : LSPSemanticTokensFeature() {
    override fun isSupported(file: PsiFile): Boolean {
        return file.language === SlangLanguage.INSTANCE && super.isSupported(file)
    }

    override fun shouldVisitPsiElement(file: PsiFile): Boolean {
        // slangd supplies precise ranges. Apply them directly; our token-only PSI must not
        // suppress types/keywords or require a matching lexer classification first.

        return false
    }

    override fun getTextAttributesKey(
        type: String,
        modifiers: MutableList<String?>,
        file: PsiFile
    ): TextAttributesKey? {
        if (file.language !== SlangLanguage.INSTANCE) {
            return null
        }

        return when (type) {
            "type",
            "class",
            "struct" -> SyntaxColors.TYPE_NAME
            "interface" -> SyntaxColors.INTERFACE
            "enum" -> SyntaxColors.ENUM
            "typeParameter" -> SyntaxColors.TYPE_PARAMETER
            "namespace" -> SyntaxColors.NAMESPACE
            "parameter" -> SyntaxColors.PARAMETER
            "variable" -> if (modifiers.contains("readonly")) SyntaxColors.CONSTANT else SyntaxColors.VARIABLE
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
}
