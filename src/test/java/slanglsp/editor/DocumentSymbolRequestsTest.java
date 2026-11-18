package slanglsp.editor;

import com.intellij.openapi.editor.impl.DocumentImpl;
import com.redhat.devtools.lsp4ij.features.documentSymbol.DocumentSymbolData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DocumentSymbolRequestsTest {
    @Test
    void sharesRequestsAndInvalidatesOnDocumentProjectOrServerChanges() {
        var slot = new DocumentSymbolRequests.Slot();
        var document = new DocumentImpl("");
        var revision = new DocumentSymbolRequests.Revision(document, 1, 2, 3);
        var wire = new CompletableFuture<List<DocumentSymbolData>>();
        var count = new AtomicInteger();
        var first = slot.share(revision, () -> {
            count.incrementAndGet();
            return wire;
        });
        var second = slot.share(revision, () -> fail("Should share the in-flight request"));
        first.cancel(true);
        assertFalse(wire.isCancelled());
        wire.complete(List.of());
        assertEquals(List.of(), second.join());
        assertEquals(List.of(), slot.share(revision, () -> fail("Should reuse the completed request")).join());

        for (var next : List.of(new DocumentSymbolRequests.Revision(document, 2, 2, 3),
                new DocumentSymbolRequests.Revision(document, 2, 3, 3),
                new DocumentSymbolRequests.Revision(document, 2, 3, 4))) {
            slot.share(next, () -> {
                count.incrementAndGet();
                return CompletableFuture.completedFuture(List.of());
            }).join();
        }
        assertEquals(4, count.get());
    }

    @Test
    void retriesFailedAndUnavailableRequests() {
        var slot = new DocumentSymbolRequests.Slot();
        var revision = new DocumentSymbolRequests.Revision(new DocumentImpl(""), 1, 1, 1);
        assertTrue(slot.share(revision, () -> CompletableFuture.failedFuture(new IllegalStateException()))
                .isCompletedExceptionally());
        assertNull(slot.share(revision, () -> CompletableFuture.completedFuture(null)).join());
        assertEquals(List.of(), slot.share(revision, () -> CompletableFuture.completedFuture(List.of())).join());
    }
}
