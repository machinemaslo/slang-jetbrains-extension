package slanglsp.editor;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.annotation.*;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiModificationTracker;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.features.documentSymbol.LSPDocumentSymbolSupport;
import org.eclipse.lsp4j.*;
import org.jetbrains.annotations.NotNull;
import slanglsp.SlangBundle;
import slanglsp.settings.ProjectSettings;

import java.util.*;

/** Network and index work runs outside the read action; stale results are never applied. */
public final class UnusedSymbolsAnnotator
        extends ExternalAnnotator<UnusedSymbolsAnnotator.Input, UnusedSymbolsAnnotator.Result> {
    record Input(PsiFile file, Document document, long stamp, long projectStamp, String text) {}

    record Candidate(String name, String kind, TextRange range) {}

    record Result(Input input, List<Candidate> unused) {}

    @Override
    public Input collectInformation(
            @NotNull PsiFile file, @NotNull com.intellij.openapi.editor.Editor editor, boolean hasErrors) {
        return hasErrors ? null : collectInformation(file);
    }

    @Override
    public Input collectInformation(@NotNull PsiFile file) {
        var project = file.getProject();

        if (ProjectSettings.getInstance(project).getState().useSlangdUnusedDiagnosticsOnly ||
                DumbService.isDumb(project) || file.getVirtualFile() == null ||
                !ProjectFileIndex.getInstance(project).isInContent(file.getVirtualFile()))
            return null;
        var document = LSPIJUtils.getDocument(file);

        return document == null
                ? null
                : new Input(file, document, document.getModificationStamp(),
                          PsiModificationTracker.getInstance(project).getModificationCount(), document.getText());
    }

    @Override
    public Result doAnnotate(Input input) {
        var support = new LSPDocumentSymbolSupport(input.file);

        try {
            var future = ReadActions.compute(
                    ()
                            -> support.getDocumentSymbols(new DocumentSymbolParams(new TextDocumentIdentifier(
                                    UsageSearch.fileUri(input.file.getVirtualFile()).toString()))));
            var symbols = ProgressIndicatorUtils.awaitWithCheckCanceled(future);

            if (symbols == null) {
                return null;
            }

            List<Candidate> candidates = new ArrayList<>();
            ReadActions.run(() -> {
                check(input);

                for (var symbol : symbols) {
                    collect(input, symbol.getDocumentSymbol(), candidates, false);
                }
            });

            List<Candidate> unused = new ArrayList<>();

            for (var candidate : candidates) {
                ProgressManager.checkCanceled();
                var element = ReadActions.compute(() -> {
                    check(input);

                    return input.file.findElementAt(candidate.range.getStartOffset());
                });

                if (element != null && UsageSearch.isUnused(element)) {
                    unused.add(candidate);
                }
            }

            return new Result(input, unused);
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            // An unavailable server or failed request cannot prove that a declaration is unused.
            com.intellij.openapi.diagnostic.Logger.getInstance(getClass())
                    .debug("Unused symbol analysis unavailable", failure);

            return null;
        } finally {
            support.cancel();
        }
    }

    private static void check(Input input) {
        ProgressManager.checkCanceled();

        if (!input.file.isValid() || input.document.getModificationStamp() != input.stamp ||
                PsiModificationTracker.getInstance(input.file.getProject()).getModificationCount() !=
                        input.projectStamp)
            throw new ProcessCanceledException();
    }

    private static void collect(Input input, DocumentSymbol symbol, List<Candidate> result, boolean externallyUsed) {
        if (symbol.getSelectionRange() == null || symbol.getRange() == null) {
            return;
        }

        int start = LSPIJUtils.toOffset(symbol.getSelectionRange().getStart(), input.document);
        // Some slangd versions give selection ranges extending beyond the identifier.
        int end = start + symbol.getName().length();

        if (start < 0 || end > input.text.length() || !input.text.substring(start, end).equals(symbol.getName())) {
            return;
        }

        int boundary = start;

        while (boundary > 0 && ";{}".indexOf(input.text.charAt(boundary - 1)) < 0) {
            boundary--;
        }

        String prefix = input.text.substring(boundary, start);
        boolean external = externallyUsed || symbol.getKind() == SymbolKind.Interface ||
                           UnusedDeclarations.isExternal(symbol.getName(), prefix);
        String kind = switch (symbol.getKind()) {
            case Struct -> "struct";
            case Function, Method -> "function";
            case Variable, Constant, Field -> "variable";
            default -> null;
        };

        if (!external && kind != null) {
            result.add(new Candidate(symbol.getName(), kind, new TextRange(start, end)));
        }

        if (symbol.getKind() == SymbolKind.Function || symbol.getKind() == SymbolKind.Method) {
            int bodyEnd = LSPIJUtils.toOffset(symbol.getRange().getEnd(), input.document);
            result.addAll(UnusedDeclarations.locals(input.text, end, Math.min(bodyEnd, input.text.length())));
        }

        if (symbol.getChildren() != null)

            for (var child : symbol.getChildren()) {
                collect(input, child, result, external);
            }
    }

    @Override
    public void apply(@NotNull PsiFile file, Result result, @NotNull AnnotationHolder holder) {
        if (result == null ||
                ProjectSettings.getInstance(file.getProject()).getState().useSlangdUnusedDiagnosticsOnly) {
            return;
        }

        check(result.input);

        for (var candidate : result.unused) {
            // A weak-warning layer takes precedence over LSP semantic token colours.
            holder.newAnnotation(HighlightSeverity.WEAK_WARNING,
                          SlangBundle.message("unused.symbol", candidate.kind, candidate.name))
                    .range(candidate.range)
                    .highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL)
                    .create();
        }
    }
}
