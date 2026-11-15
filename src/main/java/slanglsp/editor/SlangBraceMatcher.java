package slanglsp.editor;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import slanglsp.language.SlangLexer;

public final class SlangBraceMatcher implements PairedBraceMatcher {
    private static final BracePair[] PAIRS = {
            new BracePair(SlangLexer.LBRACE, SlangLexer.RBRACE, true),
            new BracePair(SlangLexer.LPAREN, SlangLexer.RPAREN, false),
            new BracePair(SlangLexer.LBRACKET, SlangLexer.RBRACKET, false)
    };

    @Override
    public BracePair @NotNull [] getPairs() {
        return PAIRS;
    }

    @Override
    public boolean isPairedBracesAllowedBeforeType(
            @NotNull IElementType leftBraceType, @Nullable IElementType contextType) {
        return contextType == null || contextType == TokenType.WHITE_SPACE
                || contextType == SlangLexer.COMMENT || contextType == SlangLexer.BLOCK_COMMENT
                || contextType == SlangLexer.SEMICOLON || contextType == SlangLexer.COMMA
                || contextType == SlangLexer.RBRACE || contextType == SlangLexer.RPAREN
                || contextType == SlangLexer.RBRACKET;
    }

    @Override
    public int getCodeConstructStart(@NotNull PsiFile file, int openingBraceOffset) {
        return openingBraceOffset;
    }
}
