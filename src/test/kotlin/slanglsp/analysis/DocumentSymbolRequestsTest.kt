package slanglsp.analysis

import com.intellij.openapi.editor.impl.DocumentImpl
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import org.eclipse.lsp4j.DocumentSymbol
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class DocumentSymbolRequestsTest {
    @Test
    fun sharesRequestsAndInvalidatesOnDocumentProjectOrServerChanges() {
        val slot = DocumentSymbolRequests.Slot()
        val document = DocumentImpl("")
        val revision = DocumentSymbolRequests.Revision(document, 1, 2, 3)
        val wire = CompletableFuture<List<DocumentSymbol>?>()
        val count = AtomicInteger()
        val first =
            slot.share(revision) {
                count.incrementAndGet()
                wire
            }
        val second = slot.share(revision) { error("Should share the in-flight request") }
        first.cancel(true)
        Assertions.assertFalse(wire.isCancelled)
        wire.complete(emptyList())
        Assertions.assertEquals(emptyList<DocumentSymbol>(), second.join())
        Assertions.assertEquals(
            emptyList<DocumentSymbol>(),
            slot
                .share(revision) {
                    error("Should reuse the completed request")
                }
                .join())

        for (next in
            listOf(
                DocumentSymbolRequests.Revision(document, 2, 2, 3),
                DocumentSymbolRequests.Revision(document, 2, 3, 3),
                DocumentSymbolRequests.Revision(document, 2, 3, 4))) {
            slot
                .share(next) {
                    count.incrementAndGet()
                    CompletableFuture.completedFuture(emptyList())
                }
                .join()
        }
        Assertions.assertEquals(4, count.get())
    }

    @Test
    fun retriesFailedAndUnavailableRequests() {
        val slot = DocumentSymbolRequests.Slot()
        val revision = DocumentSymbolRequests.Revision(DocumentImpl(""), 1, 1, 1)
        Assertions.assertTrue(
            slot
                .share(revision) {
                    CompletableFuture.failedFuture(IllegalStateException())
                }
                .isCompletedExceptionally())
        Assertions.assertNull(
            slot
                .share(revision) {
                    CompletableFuture.completedFuture(null)
                }
                .join())
        Assertions.assertEquals(
            emptyList<DocumentSymbol>(),
            slot
                .share(revision) {
                    CompletableFuture.completedFuture(emptyList())
                }
                .join())
    }
}
