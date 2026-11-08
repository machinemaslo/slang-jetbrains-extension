package slanglsp.highlighting;

import slanglsp.language.SlangLexer;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

public class SlangSyntaxHighlighter extends SyntaxHighlighterBase {
    @NotNull
    @Override
    public Lexer getHighlightingLexer() {
        return new SlangLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(@NotNull IElementType tokenType) {
        if (tokenType.equals(SlangLexer.KEYWORD)) return new TextAttributesKey[]{SyntaxColors.KEYWORD};
        if (tokenType.equals(SlangLexer.MODULE_NAME)) return pack(SyntaxColors.NAMESPACE);
        if (tokenType.equals(SlangLexer.BUILTIN_TYPE)) return pack(SyntaxColors.TYPE_NAME);
        if (tokenType.equals(SlangLexer.DIRECTIVE)) return pack(SyntaxColors.DIRECTIVE);
        if (tokenType.equals(SlangLexer.OPERATOR)) return pack(SyntaxColors.OPERATOR);
        if (tokenType.equals(SlangLexer.BRACES)) return pack(SyntaxColors.BRACES);
        if (tokenType.equals(SlangLexer.BRACKETS)) return pack(SyntaxColors.BRACKETS);
        if (tokenType.equals(SlangLexer.PARENTHESES)) return pack(SyntaxColors.PARENTHESES);
        if (tokenType.equals(SlangLexer.COMMA)) return pack(SyntaxColors.COMMA);
        if (tokenType.equals(SlangLexer.DOT)) return pack(SyntaxColors.DOT);
        if (tokenType.equals(SlangLexer.SEMICOLON)) return pack(SyntaxColors.SEMICOLON);
        if (tokenType.equals(SlangLexer.TYPE)) return new TextAttributesKey[]{SyntaxColors.TYPE_NAME};
        if (tokenType.equals(SlangLexer.FUNCTION)) return new TextAttributesKey[]{SyntaxColors.FUNCTION_CALL};
        if (tokenType.equals(SlangLexer.VARIABLE)) return new TextAttributesKey[]{SyntaxColors.VARIABLE};
        if (tokenType.equals(SlangLexer.STRING)) return new TextAttributesKey[]{SyntaxColors.STRING};
        if (tokenType.equals(SlangLexer.NUMBER)) return new TextAttributesKey[]{SyntaxColors.NUMBER};
        if (tokenType.equals(SlangLexer.COMMENT)) return new TextAttributesKey[]{SyntaxColors.LINE_COMMENT};
        if (tokenType.equals(SlangLexer.BLOCK_COMMENT)) return new TextAttributesKey[]{SyntaxColors.BLOCK_COMMENT};
        return new TextAttributesKey[0];
    }
}
