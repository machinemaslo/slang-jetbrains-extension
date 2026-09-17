package slanglsp;

import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.features.documentation.LSPDocumentationTarget;
import com.redhat.devtools.lsp4ij.features.documentation.LSPDocumentationTargetProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

/** Connects Ctrl+hover on Slang PSI to the same LSP hover used by Quick Documentation. */
public class SlangDocumentationProvider extends AbstractDocumentationProvider {
    @Override
    public @Nullable String getQuickNavigateInfo(PsiElement element, PsiElement originalElement) {
        return documentation(element, originalElement);
    }

    @Override
    public @Nullable String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
        return documentation(element, originalElement);
    }

    private @Nullable String documentation(PsiElement element, PsiElement originalElement) {
        // Ask at the usage site, not the resolved declaration: overloads and generic
        // substitutions can have different documentation at different call sites.
        PsiElement source = originalElement != null ? originalElement : element;
        if (source == null || !source.isValid()) return null;
        PsiFile file = source.getContainingFile();
        if (file == null || !file.getLanguage().isKindOf(SlangLanguage.INSTANCE)) return null;
        String html = hoverDocumentation(file, source.getTextOffset()).stream()
                .filter(content -> content != null && !content.isBlank())
                .collect(Collectors.joining("<hr />"));
        return html.isBlank() ? null : html;
    }

    protected @NotNull List<String> hoverDocumentation(PsiFile file, int offset) {
        return new LSPDocumentationTargetProvider().documentationTargets(file, offset).stream()
                .filter(LSPDocumentationTarget.class::isInstance)
                .map(LSPDocumentationTarget.class::cast)
                .map(LSPDocumentationTarget::getHtml)
                .toList();
    }
}
