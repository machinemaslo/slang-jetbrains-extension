package slanglsp.analysis

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.features.documentSymbol.DocumentSymbolData
import com.redhat.devtools.lsp4ij.usages.LocationData
import java.util.concurrent.*
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.Volatile
import org.eclipse.lsp4j.*
import slanglsp.server.BatchConnection
import slanglsp.server.client.ClientFeatureAccess

/** Batch requests never schedule continuations on the commit workers' common pool. */
@Service(Service.Level.PROJECT)
class BatchAnalysisService(private val project: Project) : Disposable {
    private val requests: ExecutorService = executor("Slang batch requests")

    // Server-to-client callbacks must remain runnable while requests wait for initialization.
    private val callbacks: ExecutorService = executor("Slang batch callbacks")
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

    @Volatile private var disposed = false

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

    fun symbols(file: PsiFile, document: Document): CompletableFuture<MutableList<DocumentSymbolData?>?> {
        return request(file, document) { server ->
            val wire = server.textDocumentService.documentSymbol(DocumentSymbolParams(identifier(file, server)))
            mapResponse(wire) { symbols ->
                val result = mutableListOf<DocumentSymbolData?>()
                for (symbol in symbols.orEmpty().filterNotNull()) {
                    check(symbol.isRight) { "Slang batch analysis requires hierarchical document symbols" }
                    result.add(DocumentSymbolData(checkNotNull(symbol.right), file, server))
                }
                result
            }
        }
    }

    fun references(file: PsiFile, document: Document, offset: Int): CompletableFuture<MutableList<LocationData?>?> {
        val position = ReadActions.compute { LSPIJUtils.toPosition(offset, document) }
        return request(file, document) { server ->
            val supported = ReadActions.compute {
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

    fun definitions(file: PsiFile, document: Document, offset: Int): CompletableFuture<MutableList<LocationData?>?> {
        val position = ReadActions.compute { LSPIJUtils.toPosition(offset, document) }
        return request(file, document) { server ->
            val params = DefinitionParams(identifier(file, server), position)
            mapResponse(server.textDocumentService.definition(params)) { locations ->
                LSPIJUtils.getLocations(locations, server)
            }
        }
    }

    internal fun <T, R> mapResponse(wire: CompletableFuture<T?>, convert: (T?) -> R?): CompletableFuture<R?> {
        val mapped = wire.thenApplyAsync({ convert(it) }, requests)
        mapped.whenComplete { _, _ ->
            if (mapped.isCancelled) {
                wire.cancel(true)
            }
        }
        return mapped
    }

    private fun identifier(file: PsiFile, server: LanguageServerItem): TextDocumentIdentifier {
        return ReadActions.compute {
            TextDocumentIdentifier(ClientFeatureAccess.documentUri(server, file.virtualFile))
        }
    }

    // The deadline applies to all waits inside the request.
    private fun <T> request(
        file: PsiFile,
        document: Document,
        action: (LanguageServerItem) -> CompletableFuture<T?>
    ): CompletableFuture<T?> {
        val result = track<T?>(CompletableFuture())
        val active = AtomicReference<CompletableFuture<*>?>()
        val stamp = document.modificationStamp
        result.whenComplete { _, failure ->
            if (failure != null) {
                val request = active.get()
                request?.cancel(true)
            }
        }
        result.orTimeout(10, TimeUnit.SECONDS)
        try {
            requests.execute {
                if (result.isDone) {
                    return@execute
                }
                try {
                    AnalysisDeadline.open().use {
                        val wrapper = BatchConnection.wrapper(project)
                        val server = AnalysisDeadline.await(wrapper.initializedServer)
                        if (server == null || disposed || result.isDone) {
                            throw CancellationException("Batch server is no longer available")
                        }
                        val connection = ReadActions.compute {
                            if (!file.isValid || document.modificationStamp != stamp) {
                                throw CancellationException("Batch document changed")
                            }
                            BatchConnection.connect(wrapper, server, file, document)
                        }
                        val connected = AnalysisDeadline.await(connection)
                        if (result.isDone) {
                            return@execute
                        }
                        val request = action(checkNotNull(connected) { "Batch document connection is unavailable" })
                        active.set(request)
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
        future.whenComplete { _, _ -> pending.remove(future) }
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
        private val BATCH = ThreadLocal<Boolean?>()

        internal fun enter(batch: Boolean): Scope {
            val previous: Boolean? = BATCH.get()
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
            val sequence = AtomicInteger()
            return ThreadPoolExecutor(
                2,
                2,
                30,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(256),
                { runnable ->
                    val thread = Thread(runnable, name + "-" + sequence.incrementAndGet())
                    thread.setDaemon(true)
                    thread.contextClassLoader = ApplicationManager::class.java.classLoader
                    thread
                },
                AbortPolicy())
        }
    }
}
