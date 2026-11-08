package slanglsp.server;

import slanglsp.language.SlangLanguage;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.client.features.LSPSemanticTokensFeature;
import slanglsp.highlighting.SyntaxColors;

import java.util.List;
import org.jetbrains.annotations.NotNull;

// LSP4IJ exposes custom client features through experimental extension points.
final class SemanticTokensFeature extends LSPSemanticTokensFeature {
    @Override
    public boolean isSupported(@NotNull PsiFile file) {
        return file.getLanguage() == SlangLanguage.INSTANCE && super.isSupported(file);
    }

    @Override
    public boolean shouldVisitPsiElement(@NotNull PsiFile file) {
        // slangd supplies precise ranges. Apply them directly; our token-only PSI must not
        // suppress types/keywords or require a matching lexer classification first.

        return false;
    }

    @Override
    public TextAttributesKey getTextAttributesKey(
            @NotNull String type, @NotNull List<String> modifiers, @NotNull PsiFile file) {
        if (file.getLanguage() != SlangLanguage.INSTANCE)
            return null;

        return switch (type) {
            case "type", "class", "struct" -> SyntaxColors.TYPE_NAME;
            case "interface" -> SyntaxColors.INTERFACE;
            case "enum" -> SyntaxColors.ENUM;
            case "typeParameter" -> SyntaxColors.TYPE_PARAMETER;
            case "namespace" -> SyntaxColors.NAMESPACE;
            case "parameter" -> SyntaxColors.PARAMETER;
            case "variable" -> modifiers.contains("readonly") ? SyntaxColors.CONSTANT : SyntaxColors.VARIABLE;
            case "property" -> SyntaxColors.FIELD;
            case "enumMember" -> SyntaxColors.CONSTANT;
            // slangd emits function for both declarations and calls with an empty modifier
            // legend. Use one function-symbol style, like VS Code's entity.name.function.
            case "function" -> SyntaxColors.FUNCTION_DECLARATION;
            case "method" -> SyntaxColors.METHOD;
            case "macro" -> SyntaxColors.MACRO_KEYWORD;
            case "keyword", "modifier" -> SyntaxColors.KEYWORD;
            case "comment" -> SyntaxColors.LINE_COMMENT;
            case "string" -> SyntaxColors.STRING;
            case "number" -> SyntaxColors.NUMBER;
            case "operator" -> SyntaxColors.OPERATOR;
            default -> null;
        };
    }
}
