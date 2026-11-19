package slanglsp.server

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.*
import com.redhat.devtools.lsp4ij.client.WorkspaceFolderNotificationManager
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.URI
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.services.LanguageServer

/**
 * LSP4IJ 0.21's accessor, connect(), and didOpen futures use the common pool. There is no executor-selecting public
 * connection API. This version-specific adapter uses the SAME wrapper registry, document lock, synchronizer,
 * dispatcher, and disposal owner; it replaces only the scheduling of connection/didOpen, not document ownership.
 */
object BatchConnection {
    const val SERVER_ID: String = "slanglsp.SlangLanguageServer"

    @JvmStatic
    fun wrapper(project: Project): LanguageServerWrapper {
        val definition = LanguageServersRegistry.getInstance().getServerDefinition(SERVER_ID)
        check(!(definition == null || !definition.isEnabled(project))) { "Slang language server is unavailable" }
        try {
            val accessorType = Class.forName("com.redhat.devtools.lsp4ij.LanguageServiceAccessor")
            // Use the same accessor entry point as LSP4IJ's manager.
            val accessor = accessorType.getMethod("getInstance", Project::class.java).invoke(null, project)
            synchronized(accessor) {
                var existing = findWrapper(accessorType, accessor)
                if (existing != null) {
                    return existing
                }
                LanguageServerManager.getInstance(project)
                    .start(
                        SERVER_ID,
                        LanguageServerManager.StartOptions()
                            .setForceStart(true)
                            .setForceRestart(false)
                            .setWillEnable(false))
                existing = findWrapper(accessorType, accessor)
                checkNotNull(existing) { "Slang language server was not created" }
                return existing
            }
        } catch (failure: ReflectiveOperationException) {
            throw incompatible(failure)
        }
    }

    @Throws(ReflectiveOperationException::class)
    private fun findWrapper(type: Class<*>, accessor: Any): LanguageServerWrapper? {
        val field = type.getDeclaredField("startedServers")
        field.setAccessible(true)
        val servers = field.get(accessor) as MutableCollection<*>
        val snapshot: Array<Any?>
        synchronized(servers) {
            snapshot = servers.toTypedArray()
        }
        for (server in snapshot) {
            val wrapper = server as LanguageServerWrapper
            if (SERVER_ID == wrapper.serverDefinition.id) {
                return wrapper
            }
        }
        return null
    }

    /** Caller waits for wrapper initialization off the read lock before invoking this method. */
    fun connect(
        wrapper: LanguageServerWrapper,
        server: LanguageServer?,
        file: PsiFile,
        document: Document
    ): CompletableFuture<LanguageServerItem?> {
        try {
            val access: Access = Access.INSTANCE
            val virtualFile = file.virtualFile
            val uri = wrapper.toUri(virtualFile)
            check(!(uri == null || !wrapper.isEnabled || wrapper.languageServer !== server)) {
                "Slang document connection is unavailable"
            }
            val workspace = WorkspaceFolderNotificationManager(wrapper)
            val folder = workspace.computeWorkspaceFolderToNotify(virtualFile)
            var notifyFolder = false
            val synchronizer: DocumentContentSynchronizer?
            var opened: CompletableFuture<LanguageServer?>?
            val dispatched: CompletableFuture<LanguageServer?>
            synchronized(access.documentsLock.get(wrapper)) {
                access.removeStopTimer.invoke(wrapper, false)
                val existing = wrapper.getOpenedDocument(uri)
                if (existing?.synchronizer == null) {
                    val text = document.immutableCharSequence.toString()
                    val languageId = wrapper.serverDefinition.getLanguageId(virtualFile, file.project)
                    synchronizer =
                        DocumentContentSynchronizer(wrapper, uri.toString(), virtualFile, document, text, languageId)
                    Disposer.register(wrapper, synchronizer)
                    document.addDocumentListener(synchronizer, synchronizer)
                    access.opened(wrapper)!![uri] =
                        OpenedDocument(LanguageServerItem(server, wrapper), virtualFile, synchronizer)
                    access.closed(wrapper)!!.remove(uri)
                } else {
                    synchronizer = existing.synchronizer
                }
                if (folder != null) {
                    notifyFolder = workspace.markFolderAsNotified(folder)
                }
                // Use the same monitor as LSP4IJ's document synchronizer.
                synchronized(synchronizer!!) {
                    opened = access.didOpen(synchronizer)
                    if (opened == null) {
                        opened = CompletableFuture()
                        access.didOpenFuture.set(synchronizer, opened)
                        access.version.setInt(synchronizer, 1)
                    }
                    if (opened!!.isCompletedExceptionally()) {
                        return opened!!.thenApply { LanguageServerItem(server, wrapper) }
                    }
                    if (opened!!.isDone) {
                        dispatched = CompletableFuture.completedFuture(server)
                    } else {
                        val version = access.version.getInt(synchronizer)
                        // ProtocolCompatibility serializes/deduplicates didOpen if a normal
                        // editor connection already queued one on the starved common pool.
                        val item =
                            TextDocumentItem(
                                uri.toString(),
                                wrapper.serverDefinition.getLanguageId(virtualFile, file.project),
                                version,
                                document.immutableCharSequence.toString())
                        dispatched = wrapper.sendNotification { ls: LanguageServer? ->
                            ls!!.textDocumentService.didOpen(DidOpenTextDocumentParams(item))
                            ls
                        }
                    }
                }
            }
            if (notifyFolder) {
                workspace.sendFolderAddedNotification(folder!!)
            }
            val didOpen: CompletableFuture<LanguageServer?> = opened!!
            val sync: DocumentContentSynchronizer = synchronizer!!
            dispatched.whenComplete { _, failure ->
                if (failure != null) {
                    didOpen.completeExceptionally(failure)
                }
            }
            return dispatched
                .thenCompose<Void?> { ls ->
                    // Complete the existing readiness barrier only after ordered dispatch.
                    didOpen.complete(ls)
                    sync.flushPendingChanges()
                }
                .thenApply { LanguageServerItem(server, wrapper) }
        } catch (failure: ReflectiveOperationException) {
            throw incompatible(failure)
        }
    }

    private fun incompatible(failure: ReflectiveOperationException): IllegalStateException {
        return IllegalStateException("Unsupported LSP4IJ batch connection API; expected 0.21 layout", failure)
    }

    private class Access {
        val documentsLock: Field
        private val openedDocuments: Field
        private val closedDocuments: Field
        val didOpenFuture: Field
        val version: Field
        val removeStopTimer: Method

        init {
            try {
                documentsLock = field(LanguageServerWrapper::class.java, "documentsLock")
                openedDocuments = field(LanguageServerWrapper::class.java, "openedDocuments")
                closedDocuments = field(LanguageServerWrapper::class.java, "closedDocuments")
                didOpenFuture = field(DocumentContentSynchronizer::class.java, "didOpenFuture")
                version = field(DocumentContentSynchronizer::class.java, "version")
                removeStopTimer =
                    LanguageServerWrapper::class
                        .java
                        .getDeclaredMethod("removeStopTimer", Boolean::class.javaPrimitiveType)
                removeStopTimer.setAccessible(true)
            } catch (failure: ReflectiveOperationException) {
                throw incompatible(failure)
            }
        }

        // These are the generic types of the pinned LSP4IJ fields; reflection erases them.
        @Suppress("UNCHECKED_CAST")
        @Throws(IllegalAccessException::class)
        fun opened(wrapper: LanguageServerWrapper): MutableMap<URI?, OpenedDocument?>? {
            return openedDocuments.get(wrapper) as MutableMap<URI?, OpenedDocument?>?
        }

        @Throws(IllegalAccessException::class)
        fun closed(wrapper: LanguageServerWrapper): MutableMap<*, *>? {
            return closedDocuments.get(wrapper) as MutableMap<*, *>?
        }

        @Suppress("UNCHECKED_CAST")
        @Throws(IllegalAccessException::class)
        fun didOpen(synchronizer: DocumentContentSynchronizer): CompletableFuture<LanguageServer?>? {
            return didOpenFuture.get(synchronizer) as CompletableFuture<LanguageServer?>?
        }

        companion object {
            val INSTANCE = Access()

            @Throws(NoSuchFieldException::class)
            private fun field(type: Class<*>, name: String): Field {
                val field = type.getDeclaredField(name)
                field.setAccessible(true)
                return field
            }
        }
    }
}
