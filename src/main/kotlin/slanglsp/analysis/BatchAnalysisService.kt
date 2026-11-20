package slanglsp.analysis

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.features.documentSymbol.DocumentSymbolData
import com.redhat.devtools.lsp4ij.usages.LocationData
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import slanglsp.server.BatchConnection
import slanglsp.server.client.ClientFeatureAccess

/** Batch requests never schedule continuations on the commit workers' common pool. */
@OptIn(ExperimentalAtomicApi::class)
@Service(Service.Level.PROJECT)
class BatchAnalysisService(private val project: Project) : Disposable {
    private val requests: ExecutorService = executor("Slang batch requests")

    // Server-to-client callbacks must remain runnable while requests wait for initialization.
    private val callbacks: ExecutorService = executor("Slang batch callbacks")

    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

    @Volatile
    private var disposed = false

    internal fun interface Scope : AutoCloseable {
        override fun close()
    }

    fun <T> callback(action: () -> T?): CompletableFuture<T?> {
        return try {
            track(CompletableFuture.supplyAsync({ action() }, callbacks))
        } catch (failure: RejectedExecutionException) {
            CompletableFuture.failedFuture(failure)
        }
    }

    fun symbols(
        file: PsiFile,
        document: Document,
    ): CompletableFuture<MutableList<DocumentSymbolData?>?> {
        return request(file, document) { server ->
            val wire = server.textDocumentService.documentSymbol(DocumentSymbolParams(identifier(file, server)))

            mapResponse(wire) { symbols ->
                symbols.orEmpty().mapNotNullTo(mutableListOf<DocumentSymbolData?>()) { symbol ->
                    symbol ?: return@mapNotNullTo null

                    check(symbol.isRight) {
                        "Slang batch analysis requires hierarchical document symbols"
                    }

                    DocumentSymbolData(checkNotNull(symbol.right), file, server)
                }
            }
        }
    }

    fun references(
        file: PsiFile,
        document: Document,
        offset: Int,
    ): CompletableFuture<MutableList<LocationData?>?> {
        val position = runReadActionBlocking {
            LSPIJUtils.toPosition(offset, document)
        }

        return request(file, document) { server ->
            val supported = runReadActionBlocking {
                ClientFeatureAccess.supportsReferences(server, file)
            }

            if (!supported) {
                return@request CompletableFuture.completedFuture(null)
            }

            val params = ReferenceParams(identifier(file, server), position, ReferenceContext(false))
            mapResponse(server.textDocumentService.references(params)) { locations ->
                locations.orEmpty().mapTo(mutableListOf()) { LocationData(it, server) }
            }
        }
    }

    fun definitions(
        file: PsiFile,
        document: Document,
        offset: Int,
    ): CompletableFuture<MutableList<LocationData?>?> {
        val position = runReadActionBlocking {
            LSPIJUtils.toPosition(offset, document)
        }

        return request(file, document) { server ->
            val params = DefinitionParams(identifier(file, server), position)
            mapResponse(server.textDocumentService.definition(params)) { locations ->
                LSPIJUtils.getLocations(locations, server)
            }
        }
    }

    internal fun <T, R> mapResponse(
        wire: CompletableFuture<T?>,
        convert: (T?) -> R?,
    ): CompletableFuture<R?> {
        val mapped = wire.thenApplyAsync({ convert(it) }, requests)

        mapped.whenComplete { _, _ ->
            if (mapped.isCancelled) {
                wire.cancel(true)
            }
        }

        return mapped
    }

    private fun identifier(file: PsiFile, server: LanguageServerItem): TextDocumentIdentifier {
        return runReadActionBlocking {
            TextDocumentIdentifier(ClientFeatureAccess.documentUri(server, file.virtualFile))
        }
    }

    // The deadline applies to all waits inside the request.
    private fun <T> request(
        file: PsiFile,
        document: Document,
        action: (LanguageServerItem) -> CompletableFuture<T?>,
    ): CompletableFuture<T?> {
        val result = track<T?>(CompletableFuture())
        val active = AtomicReference<CompletableFuture<*>?>(null)
        val stamp = document.modificationStamp

        result.whenComplete { _, failure ->
            if (failure != null) {
                active.load()?.cancel(true)
            }
        }

        result.orTimeout(REQUEST_BUDGET.inWholeMilliseconds, TimeUnit.MILLISECONDS)

        try {
            requests.execute {
                if (result.isDone) {
                    return@execute
                }

                try {
                    AnalysisDeadline.open(REQUEST_BUDGET).use {
                        val wrapper = BatchConnection.wrapper(project)
                        val server = AnalysisDeadline.await(wrapper.initializedServer)

                        if (server == null || disposed || result.isDone) {
                            throw CancellationException("Batch server is no longer available")
                        }

                        val connection = runReadActionBlocking {
                            if (!file.isValid || document.modificationStamp != stamp) {
                                throw CancellationException("Batch document changed")
                            }

                            BatchConnection.connect(wrapper, server, file, document)
                        }
                        val connected = AnalysisDeadline.await(connection)

                        if (result.isDone) {
                            return@execute
                        }

                        val connectedServer = checkNotNull(connected) {
                            "Batch document connection is unavailable"
                        }
                        val request = action(connectedServer)
                        active.store(request)

                        if (result.isDone) {
                            request.cancel(true)
                        }

                        request.whenComplete { value, failure ->
                            if (failure == null) {
                                result.complete(value)
                            } else {
                                result.completeExceptionally(failure)
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        } catch (failure: RejectedExecutionException) {
            result.completeExceptionally(failure)
        }

        return result
    }

    private fun <T> track(future: CompletableFuture<T?>): CompletableFuture<T?> {
        pending.add(future)
        future.whenComplete { _, _ ->
            pending.remove(future)
        }

        if (disposed) {
            future.cancel(true)
        }

        return future
    }

    override fun dispose() {
        disposed = true
        pending.forEach { it.cancel(true) }
        requests.shutdownNow()
        callbacks.shutdownNow()
    }

    companion object {
        private val REQUEST_BUDGET = 10.seconds
        private val BATCH = ThreadLocal<Boolean?>()

        internal fun enter(batch: Boolean): Scope {
            val previous = BATCH.get()

            if (batch) {
                BATCH.set(true)
            }

            return Scope {
                if (previous == null) {
                    BATCH.remove()
                } else {
                    BATCH.set(previous)
                }
            }
        }

        val isBatch: Boolean
            get() = BATCH.get() == true

        private fun executor(name: String): ExecutorService {
            val sequence = AtomicInt(1)

            return ThreadPoolExecutor(
                2,
                2,
                30,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(256),
                { runnable ->
                    Thread(runnable, "$name-${sequence.fetchAndAdd(1)}").apply {
                        isDaemon = true
                        contextClassLoader = ApplicationManager::class.java.classLoader
                    }
                },
                AbortPolicy(),
            )
        }
    }
}
