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
    private static final com.intellij.openapi.util.Key<Result> CACHE =
            com.intellij.openapi.util.Key.create("slang.unused.analysis");

    public record Input(PsiFile file, Document document, long stamp, long projectStamp, String text) {
    }

    public record Candidate(String name, String kind, TextRange range) {
        String message() {
            return switch (kind) {
                case "function" -> SlangBundle.message("unused.symbol.function", name);
                case "method" -> SlangBundle.message("unused.symbol.method", name);
                case "struct" -> SlangBundle.message("unused.symbol.struct", name);
                case "class" -> SlangBundle.message("unused.symbol.class", name);
                case "enum" -> SlangBundle.message("unused.symbol.enum", name);
                case "field" -> SlangBundle.message("unused.symbol.field", name);
                case "constant" -> SlangBundle.message("unused.symbol.constant", name);
                default -> SlangBundle.message("unused.symbol.variable", name);
            };
        }

        String removalText() {
            return switch (kind) {
                case "function" -> SlangBundle.message("unused.remove.function");
                case "method" -> SlangBundle.message("unused.remove.method");
                case "struct" -> SlangBundle.message("unused.remove.struct");
                case "class" -> SlangBundle.message("unused.remove.class");
                case "enum" -> SlangBundle.message("unused.remove.enum");
                case "field" -> SlangBundle.message("unused.remove.field");
                case "constant" -> SlangBundle.message("unused.remove.constant");
                default -> SlangBundle.message("unused.remove.variable");
            };
        }
    }

    public record Result(Input input, List<Candidate> unused) {
    }

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
        var cached = input.file().getUserData(CACHE);
        if (cached != null && cached.input.stamp == input.stamp && cached.input.projectStamp == input.projectStamp) {
            return cached;
        }
        var result = analyze(input);
        if (result != null) {
            ReadActions.run(() -> {
                check(input);
                input.file().putUserData(CACHE, result);
            });
        }
        return result;
    }

    private Result analyze(Input input) {
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
                    collect(input, symbol.getDocumentSymbol(), candidates, false, false);
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

    private static void collect(Input input, DocumentSymbol symbol, List<Candidate> result,
                                boolean externallyUsed, boolean member) {
        if (symbol == null || symbol.getSelectionRange() == null || symbol.getRange() == null) {
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
            case Class -> "class";
            case Enum -> "enum";
            case Function -> member ? "method" : "function";
            case Method -> "method";
            case Constant -> "constant";
            case Field -> "field";
            case Variable -> member ? "field" : "variable";
            default -> null;
        };

        if (!external && kind != null) {
            result.add(new Candidate(symbol.getName(), kind, new TextRange(start, end)));
        }

        if (symbol.getKind() == SymbolKind.Function || symbol.getKind() == SymbolKind.Method) {
            int bodyEnd = LSPIJUtils.toOffset(symbol.getRange().getEnd(), input.document);
            result.addAll(UnusedDeclarations.locals(input.text, end, Math.min(bodyEnd, input.text.length())));
        }

        if (symbol.getChildren() != null) {
            boolean type = symbol.getKind() == SymbolKind.Struct || symbol.getKind() == SymbolKind.Class;
            int body = type ? input.text.indexOf('{', end) : -1;
            boolean implementsInterface = body >= end && input.text.substring(end, body).contains(":");
            for (var child : symbol.getChildren()) {
                collect(input, child, result, external || implementsInterface, type);
            }
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
            // Keep unused styling; overlapping naming annotations omit their hover text.
            var annotation = holder.newAnnotation(HighlightSeverity.WEAK_WARNING,
                           candidate.message())
                    .range(candidate.range)
                    .highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL);
            if (UnusedRemoval.range(result.input.text, candidate) != null) {
                annotation = annotation.newFix(new RemoveUnusedFix(result.input, candidate))
                        .range(candidate.range).registerFix();
            }
            annotation.create();
        }
    }
}
