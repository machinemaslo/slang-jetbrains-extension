package slanglsp.server;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.DocumentContentSynchronizer;
import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.LanguageServerManager;
import com.redhat.devtools.lsp4ij.LanguageServerWrapper;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.OpenedDocument;
import com.redhat.devtools.lsp4ij.client.WorkspaceFolderNotificationManager;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.services.LanguageServer;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * LSP4IJ 0.21's accessor, connect(), and didOpen futures use the common pool.
 * There is no executor-selecting public connection API. This version-specific adapter
 * uses the SAME wrapper registry, document lock, synchronizer, dispatcher, and disposal
 * owner; it replaces only the scheduling of connection/didOpen, not document ownership.
 */
public final class BatchConnection {
    public static final String SERVER_ID = "slanglsp.SlangLanguageServer";

    private BatchConnection() {
    }

    public static LanguageServerWrapper wrapper(Project project) {
        var definition = LanguageServersRegistry.getInstance().getServerDefinition(SERVER_ID);
        if (definition == null || !definition.isEnabled(project)) {
            throw new IllegalStateException("Slang language server is unavailable");
        }
        try {
            Class<?> accessorType = Class.forName("com.redhat.devtools.lsp4ij.LanguageServiceAccessor");
            Object accessor = project.getService(accessorType);
            synchronized (accessor) {
                var existing = findWrapper(accessorType, accessor);
                if (existing != null) {
                    return existing;
                }
                LanguageServerManager.getInstance(project).start(SERVER_ID,
                        new LanguageServerManager.StartOptions().setForceStart(true)
                                .setForceRestart(false).setWillEnable(false));
                existing = findWrapper(accessorType, accessor);
                if (existing == null) {
                    throw new IllegalStateException("Slang language server was not created");
                }
                return existing;
            }
        } catch (ReflectiveOperationException failure) {
            throw incompatible(failure);
        }
    }

    private static LanguageServerWrapper findWrapper(Class<?> type, Object accessor)
            throws ReflectiveOperationException {
        var field = type.getDeclaredField("startedServers");
        field.setAccessible(true);
        var servers = (Collection<?>) field.get(accessor);
        Object[] snapshot;
        synchronized (servers) {
            snapshot = servers.toArray();
        }
        for (Object server : snapshot) {
            var wrapper = (LanguageServerWrapper) server;
            if (SERVER_ID.equals(wrapper.getServerDefinition().getId())) {
                return wrapper;
            }
        }
        return null;
    }

    /** Caller waits for wrapper initialization off the read lock before invoking this method. */
    public static CompletableFuture<LanguageServerItem> connect(
            LanguageServerWrapper wrapper, LanguageServer server, PsiFile file, Document document) {
        try {
            var access = Access.INSTANCE;
            var virtualFile = file.getVirtualFile();
            URI uri = wrapper.toUri(virtualFile);
            if (uri == null || !wrapper.isEnabled() || wrapper.getLanguageServer() != server) {
                throw new IllegalStateException("Slang document connection is unavailable");
            }
            var workspace = new WorkspaceFolderNotificationManager(wrapper);
            var folder = workspace.computeWorkspaceFolderToNotify(virtualFile);
            boolean notifyFolder = false;
            DocumentContentSynchronizer synchronizer;
            CompletableFuture<LanguageServer> opened;
            CompletableFuture<LanguageServer> dispatched;
            synchronized (access.documentsLock.get(wrapper)) {
                access.removeStopTimer.invoke(wrapper, false);
                var existing = wrapper.getOpenedDocument(uri);
                if (existing == null || existing.getSynchronizer() == null) {
                    String text = document.getImmutableCharSequence().toString();
                    String languageId = wrapper.getServerDefinition().getLanguageId(virtualFile, file.getProject());
                    synchronizer = new DocumentContentSynchronizer(
                            wrapper, uri.toString(), virtualFile, document, text, languageId);
                    Disposer.register(wrapper, synchronizer);
                    document.addDocumentListener(synchronizer, synchronizer);
                    access.opened(wrapper).put(uri,
                            new OpenedDocument(new LanguageServerItem(server, wrapper), virtualFile, synchronizer));
                    access.closed(wrapper).remove(uri);
                } else {
                    synchronizer = existing.getSynchronizer();
                }
                if (folder != null) {
                    notifyFolder = workspace.markFolderAsNotified(folder);
                }
                synchronized (synchronizer) {
                    opened = access.didOpen(synchronizer);
                    if (opened == null) {
                        opened = new CompletableFuture<>();
                        access.didOpenFuture.set(synchronizer, opened);
                        access.version.setInt(synchronizer, 1);
                    }
                    if (opened.isCompletedExceptionally()) {
                        return opened.thenApply(ignored -> new LanguageServerItem(server, wrapper));
                    }
                    if (opened.isDone()) {
                        dispatched = CompletableFuture.completedFuture(server);
                    } else {
                        int version = access.version.getInt(synchronizer);
                        // ProtocolCompatibility serializes/deduplicates didOpen if a normal
                        // editor connection already queued one on the starved common pool.
                        var item = new TextDocumentItem(uri.toString(),
                                wrapper.getServerDefinition().getLanguageId(virtualFile, file.getProject()),
                                version, document.getImmutableCharSequence().toString());
                        dispatched = wrapper.sendNotification(ls -> {
                            ls.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));
                            return ls;
                        });
                    }
                }
            }
            if (notifyFolder) {
                workspace.sendFolderAddedNotification(folder);
            }
            var didOpen = opened;
            var sync = synchronizer;
            dispatched.whenComplete((ls, failure) -> {
                if (failure != null) {
                    didOpen.completeExceptionally(failure);
                }
            });
            return dispatched.thenCompose(ls -> {
                // Complete the existing readiness barrier only after ordered dispatch.
                didOpen.complete(ls);
                return sync.flushPendingChanges();
            }).thenApply(ignored -> new LanguageServerItem(server, wrapper));
        } catch (ReflectiveOperationException failure) {
            throw incompatible(failure);
        }
    }

    private static IllegalStateException incompatible(ReflectiveOperationException failure) {
        return new IllegalStateException("Unsupported LSP4IJ batch connection API; expected 0.21 layout", failure);
    }

    private static final class Access {
        private static final Access INSTANCE = new Access();
        private final Field documentsLock;
        private final Field openedDocuments;
        private final Field closedDocuments;
        private final Field didOpenFuture;
        private final Field version;
        private final java.lang.reflect.Method removeStopTimer;

        private Access() {
            try {
                documentsLock = field(LanguageServerWrapper.class, "documentsLock");
                openedDocuments = field(LanguageServerWrapper.class, "openedDocuments");
                closedDocuments = field(LanguageServerWrapper.class, "closedDocuments");
                didOpenFuture = field(DocumentContentSynchronizer.class, "didOpenFuture");
                version = field(DocumentContentSynchronizer.class, "version");
                removeStopTimer = LanguageServerWrapper.class.getDeclaredMethod("removeStopTimer", boolean.class);
                removeStopTimer.setAccessible(true);
            } catch (ReflectiveOperationException failure) {
                throw incompatible(failure);
            }
        }

        private static Field field(Class<?> type, String name) throws NoSuchFieldException {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        @SuppressWarnings("unchecked")
        private Map<URI, OpenedDocument> opened(LanguageServerWrapper wrapper) throws IllegalAccessException {
            return (Map<URI, OpenedDocument>) openedDocuments.get(wrapper);
        }

        private Map<?, ?> closed(LanguageServerWrapper wrapper) throws IllegalAccessException {
            return (Map<?, ?>) closedDocuments.get(wrapper);
        }

        @SuppressWarnings("unchecked")
        private CompletableFuture<LanguageServer> didOpen(DocumentContentSynchronizer synchronizer)
                throws IllegalAccessException {
            return (CompletableFuture<LanguageServer>) didOpenFuture.get(synchronizer);
        }
    }
}
