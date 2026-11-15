package slanglsp.editor;

import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import slanglsp.language.SlangLanguage;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the clang-format executable selected in the Slang code-style scheme. */
public final class ClangFormattingService extends AsyncDocumentFormattingService {
    @Override
    public @NotNull Set<Feature> getFeatures() {
        return Set.of(Feature.FORMAT_FRAGMENTS);
    }

    @Override
    public boolean canFormat(@NotNull PsiFile file) {
        return file.getLanguage().isKindOf(SlangLanguage.INSTANCE) && ClangFormat.options(file) != null;
    }

    @Override
    protected FormattingTask createFormattingTask(@NotNull AsyncFormattingRequest request) {
        var file = request.getContext().getContainingFile();
        var options = ClangFormat.options(file);
        var document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (options == null || document == null) return null;
        var result = new SlangFormattingService.Result(request, document);
        var cancelled = new AtomicBoolean();
        var process = new AtomicReference<CapturingProcessHandler>();
        return new FormattingTask() {
            @Override
            public void run() {
                try {
                    if (cancelled.get()) return;
                    String formatted = ClangFormat.format(options, result.getDocumentText(), request.getFormattingRanges(), handler -> {
                        process.set(handler);
                        if (cancelled.get()) handler.destroyProcess();
                    });
                    if (!cancelled.get()) result.onTextReady(formatted);
                } catch (ProcessCanceledException failure) {
                    result.cancel();
                    throw failure;
                } catch (Exception failure) {
                    if (!cancelled.get()) result.onError(getName(), failure.getMessage() == null ? failure.toString() : failure.getMessage());
                }
            }

            @Override
            public boolean cancel() {
                cancelled.set(true);
                result.cancel();
                var handler = process.get();
                if (handler != null) handler.destroyProcess();
                return true;
            }

            @Override
            public boolean isRunUnderProgress() {
                return true;
            }
        };
    }

    @Override
    protected @NotNull String getNotificationGroupId() {
        return "Slang LSP";
    }

    @Override
    protected @NotNull String getName() {
        return "Slang clang-format";
    }
}
