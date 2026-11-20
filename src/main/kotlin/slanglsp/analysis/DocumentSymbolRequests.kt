package slanglsp.analysis

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import org.eclipse.lsp4j.DocumentSymbol
import slanglsp.server.ServerSettings
import slanglsp.server.nativeclient.NativeDocumentSymbols
import slanglsp.server.nativeclient.NativeNavigationRequests

/** Coalesces document symbols for naming, unused declarations, and rename. */
internal object DocumentSymbolRequests {
    private val LIVE_REQUEST_TIMEOUT = 10.seconds
    private val LIVE = Key.create<Slot>("slang.symbolRequests.live")
    private val BATCH = Key.create<Slot>("slang.symbolRequests.batch")
    private val SLOT_LOCK = Any()

    fun get(
        file: PsiFile,
        document: Document,
        stamp: Long,
        batch: Boolean,
    ): CompletableFuture<List<DocumentSymbol>?> {
        return runReadActionBlocking {
            if (!file.isValid || document.modificationStamp != stamp) {
                throw ProcessCanceledException()
            }

            val project = file.project
            val revision = Revision(
                document,
                stamp,
                PsiModificationTracker.getInstance(project).modificationCount,
                ServerSettings.analysisGeneration(project),
            )
            val slot = slot(file, if (batch) BATCH else LIVE)

            slot.share(revision) {
                val virtualFile = file.virtualFile ?: return@share CompletableFuture.completedFuture(null)
                if (batch) {
                    val request = project.service<NativeNavigationRequests>().verificationSymbols(virtualFile, document, stamp)
                    boundRequest(request)
                } else {
                    project.service<NativeDocumentSymbols>().request(virtualFile)
                }
            }
        }
    }

    private fun slot(file: PsiFile, key: Key<Slot>): Slot {
        file.getUserData(key)?.let {
            return it
        }

        return synchronized(SLOT_LOCK) {
            file.getUserData(key) ?: Slot().also {
                file.putUserData(key, it)
            }
        }
    }

    private fun <T> boundRequest(source: CompletableFuture<T>): CompletableFuture<T> {
        val bounded = source.copy().orTimeout(LIVE_REQUEST_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        bounded.whenComplete { _, failure ->
            if (failure != null && !source.isDone) {
                source.cancel(true)
            }
        }
        return bounded
    }

    internal data class Revision(
        val document: Document?,
        val documentStamp: Long,
        val projectStamp: Long,
        val serverStamp: Long,
    )

    internal class Slot {
        private var revision: Revision? = null
        private var request: CompletableFuture<List<DocumentSymbol>?>? = null

        @Synchronized
        fun share(
            current: Revision,
            start: () -> CompletableFuture<List<DocumentSymbol>?>,
        ): CompletableFuture<List<DocumentSymbol>?> {
            val existing = request?.takeIf { current == revision && it.isReusable() }
            val shared = existing ?: start().also { next ->
                request = next
                revision = current
            }

            // One consumer timing out or being canceled must not cancel another consumer's request.
            return shared.copy()
        }

        private fun <T> CompletableFuture<T?>.isReusable(): Boolean {
            return !isDone || (!isCompletedExceptionally && getNow(null) != null)
        }
    }
}
