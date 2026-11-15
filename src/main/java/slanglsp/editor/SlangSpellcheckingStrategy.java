package slanglsp.editor;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.spellchecker.inspections.PlainTextSplitter;
import com.intellij.spellchecker.inspections.SpellCheckingInspection.SpellCheckingScope;
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy;
import com.intellij.spellchecker.tokenizer.TokenConsumer;
import com.intellij.spellchecker.tokenizer.Tokenizer;
import org.jetbrains.annotations.NotNull;
import slanglsp.language.SlangLexer;

import java.util.Set;

/** Uses the IDE's dictionaries, suggestions and Save to dictionary actions. */
public final class SlangSpellcheckingStrategy extends SpellcheckingStrategy {
    private static final Tokenizer<PsiElement> STRING_TOKENIZER = new Tokenizer<>() {
        @Override
        public void tokenize(@NotNull PsiElement element, @NotNull TokenConsumer consumer) {
            String text = element.getText();
            int end = text.length();
            if (end > 1 && text.charAt(end - 1) == text.charAt(0)) end--;
            int start = 1;
            for (int i = start; i < end;) {
                if (text.charAt(i) != '\\') {
                    i++;
                    continue;
                }
                consume(element, text, start, i, consumer);
                i++;
                if (i < end) {
                    char escape = text.charAt(i++);
                    int digits = escape == 'u' ? 4 : escape == 'U' ? 8 : escape == 'x' ? Integer.MAX_VALUE : 0;
                    if (escape >= '0' && escape <= '7') {
                        for (int count = 0; count < 2 && i < end && text.charAt(i) >= '0' && text.charAt(i) <= '7'; count++) i++;
                    } else {
                        for (int count = 0; count < digits && i < end && Character.digit(text.charAt(i), 16) >= 0; count++) i++;
                    }
                }
                start = i;
            }
            consume(element, text, start, end, consumer);
        }
    };

    private static void consume(PsiElement element, String text, int start, int end, TokenConsumer consumer) {
        if (start < end) {
            consumer.consumeToken(element, text, false, 0, new TextRange(start, end), PlainTextSplitter.getInstance());
        }
    }

    @Override
    public boolean elementFitsScope(@NotNull PsiElement element, @NotNull Set<SpellCheckingScope> scope) {
        var type = element.getNode().getElementType();
        if (type == SlangLexer.STRING) return scope.contains(SpellCheckingScope.Literals);
        return (type == SlangLexer.COMMENT || type == SlangLexer.BLOCK_COMMENT)
                && scope.contains(SpellCheckingScope.Comments);
    }

    @Override
    public @NotNull Tokenizer<?> getTokenizer(PsiElement element, Set<SpellCheckingScope> scope) {
        return elementFitsScope(element, scope) ? getTokenizer(element) : EMPTY_TOKENIZER;
    }

    @Override
    public @NotNull Tokenizer<?> getTokenizer(PsiElement element) {
        if (isInjectedLanguageFragment(element)) return EMPTY_TOKENIZER;
        var type = element.getNode().getElementType();
        if (type == SlangLexer.STRING) return STRING_TOKENIZER;
        if (type == SlangLexer.COMMENT || type == SlangLexer.BLOCK_COMMENT) {
            return com.intellij.codeInspection.SuppressionUtil.isSuppressionComment(element)
                    ? EMPTY_TOKENIZER : TEXT_TOKENIZER;
        }
        return EMPTY_TOKENIZER;
    }
}
