package slanglsp;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.PsiFile;
import slanglsp.highlighting.SlangSyntaxHighlighterColors;
import java.util.List;

import com.redhat.devtools.lsp4ij.client.features.LSPSemanticTokensFeature;

final class SlangSemanticTokensFeature extends LSPSemanticTokensFeature {

    @Override
    public TextAttributesKey getTextAttributesKey(String type, List<String> modifiers, PsiFile file) {
        if (type == null) return super.getTextAttributesKey(type, modifiers, file);

        switch (type) {
            case "type":
            case "class":
            case "struct":
            case "interface":
            case "enum":
            case "typeParameter":
                return SlangSyntaxHighlighterColors.TYPE_NAME;
            case "parameter":
            case "variable":
            case "property":
                return SlangSyntaxHighlighterColors.VARIABLE;
            case "enumMember":
                return SlangSyntaxHighlighterColors.CONSTANT;
            case "function":
            case "method":
                return SlangSyntaxHighlighterColors.FUNCTION_CALL;
            case "macro":
                return SlangSyntaxHighlighterColors.MACRO_KEYWORD;
            case "keyword":
            case "modifier":
                return SlangSyntaxHighlighterColors.KEYWORD;
            case "comment":
                return SlangSyntaxHighlighterColors.LINE_COMMENT;
            case "string":
                return SlangSyntaxHighlighterColors.STRING;
            case "number":
                return SlangSyntaxHighlighterColors.NUMBER;
            case "operator":
                return DefaultLanguageHighlighterColors.OPERATION_SIGN; // Keep default for operator
        }

        return super.getTextAttributesKey(type, modifiers, file);
    }
}
