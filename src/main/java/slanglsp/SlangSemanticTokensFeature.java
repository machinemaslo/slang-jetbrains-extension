package slanglsp;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.client.features.LSPSemanticTokensFeature;
import slanglsp.highlighting.SlangSyntaxHighlighterColors;

import java.util.List;

final class SlangSemanticTokensFeature extends LSPSemanticTokensFeature {
    @Override
    public boolean isSupported(PsiFile file) {
        return file.getLanguage() == SlangLanguage.INSTANCE && super.isSupported(file);
    }

    @Override
    public boolean shouldVisitPsiElement(PsiFile file) {
        // slangd supplies precise ranges. Apply them directly; our token-only PSI must not
        // suppress types/keywords or require a matching lexer classification first.
        return false;
    }

    @Override
    public TextAttributesKey getTextAttributesKey(String type, List<String> modifiers, PsiFile file) {
        if (type == null || file.getLanguage() != SlangLanguage.INSTANCE) return null;
        return switch (type) {
            case "type", "class", "struct" -> SlangSyntaxHighlighterColors.TYPE_NAME;
            case "interface" -> SlangSyntaxHighlighterColors.INTERFACE;
            case "enum" -> SlangSyntaxHighlighterColors.ENUM;
            case "typeParameter" -> SlangSyntaxHighlighterColors.TYPE_PARAMETER;
            case "namespace" -> SlangSyntaxHighlighterColors.NAMESPACE;
            case "parameter" -> SlangSyntaxHighlighterColors.PARAMETER;
            case "variable" -> modifiers.contains("readonly") ? SlangSyntaxHighlighterColors.CONSTANT
                    : SlangSyntaxHighlighterColors.VARIABLE;
            case "property" -> SlangSyntaxHighlighterColors.FIELD;
            case "enumMember" -> SlangSyntaxHighlighterColors.CONSTANT;
            // slangd emits function for both declarations and calls with an empty modifier
            // legend. Use one function-symbol style, like VS Code's entity.name.function.
            case "function" -> SlangSyntaxHighlighterColors.FUNCTION_DECLARATION;
            case "method" -> SlangSyntaxHighlighterColors.METHOD;
            case "macro" -> SlangSyntaxHighlighterColors.MACRO_KEYWORD;
            case "keyword", "modifier" -> SlangSyntaxHighlighterColors.KEYWORD;
            case "comment" -> SlangSyntaxHighlighterColors.LINE_COMMENT;
            case "string" -> SlangSyntaxHighlighterColors.STRING;
            case "number" -> SlangSyntaxHighlighterColors.NUMBER;
            case "operator" -> SlangSyntaxHighlighterColors.OPERATOR;
            default -> null;
        };
    }
}
