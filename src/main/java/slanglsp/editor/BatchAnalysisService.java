package slanglsp.editor;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.features.documentSymbol.DocumentSymbolData;
import com.redhat.devtools.lsp4ij.usages.LocationData;
import org.eclipse.lsp4j.*;
import slanglsp.server.BatchConnection;

import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/** Batch requests never schedule continuations on the commit workers' common pool. */
@Service(Service.Level.PROJECT)
public final class BatchAnalysisService implements Disposable {
    private static final ThreadLocal<Boolean> BATCH = new ThreadLocal<>();
    private final ExecutorService requests = executor("Slang batch requests", 2);
    // Server-to-client callbacks must remain runnable while requests wait for initialization.
    private final ExecutorService callbacks = executor("Slang batch callbacks", 2);
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Project project;
    private volatile boolean disposed;

    public BatchAnalysisService(Project project) {
        this.project = project;
    }

    interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    static Scope enter(boolean batch) {
        Boolean previous = BATCH.get();
        if (batch) {
            BATCH.set(true);
        }
        return () -> {
            if (previous == null) {
                BATCH.remove();
            } else {
                BATCH.set(previous);
            }
        };
    }

    static boolean isBatch() {
        return Boolean.TRUE.equals(BATCH.get());
    }

    public <T> CompletableFuture<T> callback(Supplier<T> action) {
        try {
            return track(CompletableFuture.supplyAsync(action, callbacks));
        } catch (RejectedExecutionException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    CompletableFuture<List<DocumentSymbolData>> symbols(PsiFile file, Document document) {
        return request(file, document, server -> mapResponse(server.getTextDocumentService().documentSymbol(
                new DocumentSymbolParams(identifier(file, server))), symbols -> {
            if (symbols == null) {
                return List.of();
            }
            return symbols.stream().filter(java.util.Objects::nonNull).map(symbol -> {
                if (!symbol.isRight()) {
                    // Supported slangd versions return hierarchical symbols for
                    // LSP4IJ's negotiated capabilities. Reject an unexpected legacy
                    // schema rather than silently reporting a clean inspection.
                    throw new IllegalStateException("Slang batch analysis requires hierarchical document symbols");
                }
                return new DocumentSymbolData(symbol.getRight(), file, server);
            }).toList();
        }));
    }

    CompletableFuture<List<LocationData>> references(PsiFile file, Document document, int offset) {
        var position = ReadActions.compute(() -> LSPIJUtils.toPosition(offset, document));
        return request(file, document, server -> {
            if (!ReadActions.compute(() -> server.getClientFeatures().getReferencesFeature().isSupported(file))) {
                return CompletableFuture.completedFuture(null);
            }
            var params = new ReferenceParams(identifier(file, server), position, new ReferenceContext(false));
            return mapResponse(server.getTextDocumentService().references(params),
                    locations -> locations == null ? List.of()
                            : locations.stream().map(location -> new LocationData(location, server)).toList());
        });
    }

    CompletableFuture<List<LocationData>> definitions(PsiFile file, Document document, int offset) {
        var position = ReadActions.compute(() -> LSPIJUtils.toPosition(offset, document));
        return request(file, document, server -> {
            var params = new DefinitionParams(identifier(file, server), position);
            return mapResponse(server.getTextDocumentService().definition(params),
                    locations -> LSPIJUtils.getLocations(locations, server));
        });
    }

    <T, R> CompletableFuture<R> mapResponse(CompletableFuture<T> wire, Function<T, R> convert) {
        var mapped = wire.thenApplyAsync(convert, requests);
        mapped.whenComplete((value, failure) -> {
            if (mapped.isCancelled()) {
                wire.cancel(true);
            }
        });
        return mapped;
    }

    private TextDocumentIdentifier identifier(PsiFile file, LanguageServerItem server) {
        return ReadActions.compute(() -> new TextDocumentIdentifier(
                com.redhat.devtools.lsp4ij.client.features.FileUriSupport.toString(
                        file.getVirtualFile(), server.getClientFeatures())));
    }

    private <T> CompletableFuture<T> request(PsiFile file, Document document,
            Function<LanguageServerItem, CompletableFuture<T>> action) {
        var result = track(new CompletableFuture<T>());
        var active = new AtomicReference<CompletableFuture<?>>();
        long stamp = document.getModificationStamp();
        result.whenComplete((value, failure) -> {
            if (failure != null) {
                var request = active.get();
                if (request != null) {
                    request.cancel(true);
                }
            }
        });
        result.orTimeout(10, TimeUnit.SECONDS);
        try {
            requests.execute(() -> {
                if (result.isDone()) {
                    return;
                }
                try (var deadline = AnalysisDeadline.open()) {
                    var wrapper = BatchConnection.wrapper(project);
                    var server = AnalysisDeadline.await(wrapper.getInitializedServer());
                    if (server == null || disposed || result.isDone()) {
                        throw new CancellationException("Batch server is no longer available");
                    }
                    var connection = ReadActions.compute(() -> {
                        if (!file.isValid() || document.getModificationStamp() != stamp) {
                            throw new CancellationException("Batch document changed");
                        }
                        return BatchConnection.connect(wrapper, server, file, document);
                    });
                    var connected = AnalysisDeadline.await(connection);
                    if (result.isDone()) {
                        return;
                    }
                    var request = action.apply(connected);
                    active.set(request);
                    if (result.isDone()) {
                        request.cancel(true);
                    }
                    request.whenComplete((value, failure) -> {
                        if (failure == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(failure);
                        }
                    });
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pending.add(future);
        future.whenComplete((value, failure) -> pending.remove(future));
        if (disposed) {
            future.cancel(true);
        }
        return future;
    }

    private static ExecutorService executor(String name, int threads) {
        var sequence = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256), runnable -> {
                    var thread = new Thread(runnable, name + "-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setContextClassLoader(com.intellij.openapi.application.ApplicationManager.class.getClassLoader());
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void dispose() {
        disposed = true;
        pending.forEach(future -> future.cancel(true));
        requests.shutdownNow();
        callbacks.shutdownNow();
    }
}
