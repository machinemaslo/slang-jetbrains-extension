package slanglsp.editor;

import com.intellij.formatting.FormattingRangesInfo;
import com.intellij.formatting.service.CoreFormattingService;
import com.intellij.formatting.service.FormattingService;
import com.intellij.lang.ImportOptimizer;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import slanglsp.language.SlangLanguage;

import java.util.Set;

/** Select the platform model ahead of generic LSP services when clang-format is absent. */
public final class NativeFormattingService implements FormattingService {
    private final CoreFormattingService delegate = new CoreFormattingService();

    @Override public boolean canFormat(@NotNull PsiFile file) {
        return file.getLanguage().isKindOf(SlangLanguage.INSTANCE);
    }
    @Override public @NotNull Set<Feature> getFeatures() { return delegate.getFeatures(); }
    @Override public @NotNull PsiElement formatElement(@NotNull PsiElement element, boolean whitespaceOnly) {
        return delegate.formatElement(element, whitespaceOnly);
    }
    @Override public @NotNull PsiElement formatElement(@NotNull PsiElement element, @NotNull TextRange range, boolean whitespaceOnly) {
        return delegate.formatElement(element, range, whitespaceOnly);
    }
    @Override public void formatRanges(@NotNull PsiFile file, FormattingRangesInfo ranges, boolean whitespaceOnly, boolean quick) {
        delegate.formatRanges(file, ranges, whitespaceOnly, quick);
    }
    @Override public @NotNull Set<ImportOptimizer> getImportOptimizers(@NotNull PsiFile file) { return Set.of(); }
}
