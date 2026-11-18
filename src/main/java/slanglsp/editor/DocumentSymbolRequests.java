package slanglsp.editor;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiModificationTracker;
import com.redhat.devtools.lsp4ij.features.documentSymbol.DocumentSymbolData;
import com.redhat.devtools.lsp4ij.features.documentSymbol.LSPDocumentSymbolSupport;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import slanglsp.server.ServerClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Coalesces document symbols for naming, unused declarations, and rename. */
final class DocumentSymbolRequests {
    private static final Key<Slot> LIVE = Key.create("slang.symbolRequests.live");
    private static final Key<Slot> BATCH = Key.create("slang.symbolRequests.batch");
    private static final Object SLOT_LOCK = new Object();

    record Revision(Document document, long documentStamp, long projectStamp, long serverStamp) {
    }

    static final class Slot {
        private Revision revision;
        private CompletableFuture<List<DocumentSymbolData>> request;

        synchronized CompletableFuture<List<DocumentSymbolData>> share(
                Revision current, java.util.function.Supplier<CompletableFuture<List<DocumentSymbolData>>> start) {
            if (!current.equals(revision) || request == null
                    || request.isDone() && (request.isCompletedExceptionally() || request.getNow(null) == null)) {
                request = start.get();
                revision = current;
            }
            // One consumer timing out or being canceled must not cancel another consumer's request.
            return request.copy();
        }
    }

    static CompletableFuture<List<DocumentSymbolData>> get(PsiFile file, Document document, long stamp, boolean batch) {
        return ReadActions.compute(() -> {
            if (!file.isValid() || document.getModificationStamp() != stamp) {
                throw new ProcessCanceledException();
            }
            var project = file.getProject();
            var revision = new Revision(document, stamp, PsiModificationTracker.getInstance(project).getModificationCount(),
                    ServerClient.analysisGeneration(project));
            var slot = slot(file, batch ? BATCH : LIVE);
            return slot.share(revision, () -> batch
                    ? project.getService(BatchAnalysisService.class).symbols(file, document)
                    : requestLive(file));
        });
    }

    private static Slot slot(PsiFile file, Key<Slot> key) {
        Slot slot = file.getUserData(key);
        if (slot != null) {
            return slot;
        }
        synchronized (SLOT_LOCK) {
            slot = file.getUserData(key);
            if (slot == null) {
                slot = new Slot();
                file.putUserData(key, slot);
            }
            return slot;
        }
    }

    private static CompletableFuture<List<DocumentSymbolData>> requestLive(PsiFile file) {
        var support = new LSPDocumentSymbolSupport(file);
        var params = new DocumentSymbolParams(new TextDocumentIdentifier(UsageSearch.fileUri(file.getVirtualFile()).toString()));
        var source = support.getDocumentSymbols(params);
        var bounded = source.copy().orTimeout(10, TimeUnit.SECONDS);
        bounded.whenComplete((ignored, failure) -> {
            if (failure != null && !source.isDone()) {
                support.cancel();
            }
        });
        return bounded;
    }
}
