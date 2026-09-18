package slanglsp;

import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.find.findUsages.FindUsagesOptions;
import com.intellij.psi.PsiElement;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;

public final class SlangFindUsagesHandlerFactory extends FindUsagesHandlerFactory {
    @Override public boolean canFindUsages(@NotNull PsiElement element) {
        return SlangFindUsagesProvider.isIdentifier(element);
    }

    @Override public FindUsagesHandler createFindUsagesHandler(@NotNull PsiElement element, boolean highlight) {
        return new FindUsagesHandler(element) {
            @Override public boolean processElementUsages(@NotNull PsiElement target,
                    @NotNull Processor<? super UsageInfo> processor, @NotNull FindUsagesOptions options) {
                return SlangUsageSearch.process(target, options.searchScope, processor);
            }
            @Override protected boolean isSearchForTextOccurrencesAvailable(@NotNull PsiElement target, boolean singleFile) {
                return false;
            }
        };
    }
}
