package slanglsp;

import com.intellij.lang.cacheBuilder.DefaultWordsScanner;
import com.intellij.lang.cacheBuilder.WordsScanner;
import com.intellij.lang.findUsages.FindUsagesProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

public final class SlangFindUsagesProvider implements FindUsagesProvider {
    @Override public WordsScanner getWordsScanner() {
        return new DefaultWordsScanner(new SlangLexer(),
                TokenSet.create(SlangLexer.TYPE, SlangLexer.FUNCTION, SlangLexer.VARIABLE),
                TokenSet.create(SlangLexer.COMMENT, SlangLexer.BLOCK_COMMENT),
                TokenSet.create(SlangLexer.STRING));
    }

    static boolean isIdentifier(PsiElement element) {
        if (element == null || element.getContainingFile() == null
                || element.getContainingFile().getLanguage() != SlangLanguage.INSTANCE) return false;
        var leaf = element.getContainingFile().findElementAt(element.getTextOffset());
        if (leaf == null || leaf.getNode() == null) return false;
        var type = leaf.getNode().getElementType();
        return type == SlangLexer.TYPE || type == SlangLexer.FUNCTION || type == SlangLexer.VARIABLE;
    }

    @Override public boolean canFindUsagesFor(@NotNull PsiElement element) { return isIdentifier(element); }
    @Override public String getHelpId(@NotNull PsiElement element) { return null; }
    @Override public @NotNull String getType(@NotNull PsiElement element) { return "Slang symbol"; }
    @Override public @NotNull String getDescriptiveName(@NotNull PsiElement element) { return element.getText(); }
    @Override public @NotNull String getNodeText(@NotNull PsiElement element, boolean fullName) { return element.getText(); }
}
