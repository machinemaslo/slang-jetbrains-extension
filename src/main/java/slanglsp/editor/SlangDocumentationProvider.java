package slanglsp.editor;

import slanglsp.language.SlangLanguage;

import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.openapi.project.DumbService;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.features.documentation.LSPDocumentationHelper;
import com.redhat.devtools.lsp4ij.features.documentation.LSPHoverSupport;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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

        if (source == null || !source.isValid())
            return null;
        PsiFile file = source.getContainingFile();

        if (file == null || !file.getLanguage().isKindOf(SlangLanguage.INSTANCE))
            return null;
        String html = hoverDocumentation(file, source.getTextOffset())
                              .stream()
                              .filter(content -> content != null && !content.isBlank())
                              .collect(Collectors.joining("<hr />"));

        return html.isBlank() ? null : html;
    }

    protected @NotNull List<String> hoverDocumentation(PsiFile file, int offset) {
        if (file.getProject().isDisposed() || DumbService.isDumb(file.getProject()))
            return List.of();

        // Legacy Ctrl-hover callbacks can hold a platform read lock. They must yield
        // promptly; the platform's normal LSP documentation target remains available.
        try (var deadline = AnalysisDeadline.open(java.time.Duration.ofMillis(500))) {
            return new HoverRequest(file).documentationAt(offset);
        } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) {
            throw cancelled;
        } catch (java.util.concurrent.CompletionException unavailable) {
            com.intellij.openapi.diagnostic.Logger.getInstance(getClass()).debug("Hover unavailable", unavailable);
            return List.of();
        }
    }

    /** The document feature API connects the file and synchronizes unsaved edits before a request. */
    // LSP4IJ exposes hover capability checks through its experimental feature API.
    private static final class HoverRequest extends LSPHoverSupport {
        HoverRequest(PsiFile file) {
            super(file);
        }

        List<String> documentationAt(int offset) {
            PsiFile file = getFile();
            var position = ReadActions.compute(() -> {
                var document = LSPIJUtils.getDocument(file);

                return document == null || file.getVirtualFile() == null || offset < 0 ||
                                offset > document.getTextLength()
                        ? null
                        : LSPIJUtils.toPosition(offset, document);
            });

            if (position == null)
                return List.of();
            var servers = AnalysisDeadline.await(getLanguageServers(file,
                    features
                    -> features.getHoverFeature().isEnabled(file),
                    features -> features.getHoverFeature().isSupported(file)));
            List<String> result = new ArrayList<>();

            for (var server : servers) {
                var identifier = new TextDocumentIdentifier();
                updateTextDocumentUri(identifier, file, server);
                var request = server.getTextDocumentService().hover(new HoverParams(identifier, position));

                try {
                    var hover = AnalysisDeadline.await(request);

                    if (hover != null) {
                        String html = LSPDocumentationHelper.convertToHtml(
                                LSPDocumentationHelper.getValidMarkupContents(hover), server, file);
                        result.add(html);
                    }
                } finally {
                    if (!request.isDone())
                        request.cancel(true);
                }
            }

            return result;
        }
    }
}
