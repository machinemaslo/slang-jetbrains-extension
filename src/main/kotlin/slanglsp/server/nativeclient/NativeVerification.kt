package slanglsp.server.nativeclient

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.services.LanguageServer
import slanglsp.SlangBundle
import slanglsp.analysis.ProjectTrust
import slanglsp.language.SlangFileType
import slanglsp.server.ServerConnection
import slanglsp.server.ServerSettings
import slanglsp.settings.project.ProjectSettings

@Service(Service.Level.PROJECT)
internal class NativeVerification(private val project: Project) : Disposable {
    // Keep each snapshot stable until its request completes, without touching editor-owned sync.
    private val lock = Mutex()
    private val lifecycle = Any()
    private var connection: Connection? = null
    private var generation = 0L
    private var disposed = false
    private var reportedTo: HeadlessSlangSession? = null
    private val reported = mutableMapOf<String, Snapshot>()

    suspend fun <T> request(
        file: VirtualFile,
        document: Document,
        stamp: Long,
        send: suspend (LanguageServer, TextDocumentIdentifier) -> T?,
    ): T? = lock.withLock {
        val snapshot = readAction {
            if (!file.isValid || !file.isInLocalFileSystem || document.modificationStamp != stamp) {
                return@readAction null
            }

            val documents = FileDocumentManager.getInstance()
            val index = ProjectFileIndex.getInstance(project)
            val uri = file.toNioPath().toUri().toASCIIString()
            val snapshots = buildMap<String, Snapshot> {
                // A closed candidate may import a file whose current contents exist only in memory.
                for (unsaved in documents.unsavedDocuments) {
                    val dirty = documents.getFile(unsaved) ?: continue
                    if (dirty.isValid && dirty.isInLocalFileSystem &&
                        dirty.fileType === SlangFileType && index.isInContent(dirty)) {
                        put(dirty.toNioPath().toUri().toASCIIString(), Snapshot(unsaved))
                    }
                }

                put(uri, Snapshot(document))
            }
            val root = project.basePath?.let(Path::of)
                ?: index.getContentRootForFile(file)?.takeIf { it.isInLocalFileSystem }?.toNioPath()
                ?: file.toNioPath().parent
            RequestSnapshot(uri, root, snapshots)
        } ?: return@withLock null

        val session = session(snapshot.root) ?: return@withLock null
        if (reportedTo !== session) {
            reportedTo = session
            reported.clear()
        }

        synchronize(session.server, snapshot.documents)
        send(session.server, TextDocumentIdentifier(snapshot.uri))
    }

    private fun synchronize(server: LanguageServer, snapshots: Map<String, Snapshot>) {
        val service = server.textDocumentService
        for (uri in reported.keys.toList()) {
            if (reported[uri] != snapshots[uri]) {
                service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
                reported.remove(uri)
            }
        }

        for ((uri, snapshot) in snapshots) {
            if (uri !in reported) {
                val item = TextDocumentItem(uri, "slang", 1, snapshot.text.toString())
                service.didOpen(DidOpenTextDocumentParams(item))
                reported[uri] = snapshot
            }
        }
    }

    private suspend fun session(root: Path): HeadlessSlangSession? {
        if (!ProjectTrust.isTrusted(project)) {
            return null
        }

        val settings = ProjectSettings.getInstance(project).getState().createServerSettings()
        val epoch = synchronized(lifecycle) {
            if (disposed) {
                return null
            }

            val previous = connection
            if (previous != null && previous.settings == settings && previous.root == root && previous.session.isAlive) {
                return previous.session
            }
            previous?.session?.close()
            connection = null
            generation
        }

        val session = withTimeoutOrNull(8.seconds) {
            withContext(Dispatchers.IO) {
                val executable = checkNotNull(ServerConnection.findExecutable(project)) {
                    SlangBundle.message("server.not.found")
                }
                check(root.isDirectory()) { "Slang workspace is not a directory: $root" }
                val started = HeadlessSlangSession.start(executable, root, project.name, settings)

                synchronized(lifecycle) {
                    if (disposed || generation != epoch) {
                        started.close()
                        null
                    } else {
                        connection = Connection(root, settings, started)
                        started
                    }
                }
            }
        }
        return session
    }

    fun reset() {
        synchronized(lifecycle) {
            generation++
            connection?.session?.close()
            connection = null
        }
        ServerSettings.invalidateAnalysis(project)
    }

    override fun dispose() {
        synchronized(lifecycle) {
            disposed = true
            connection?.session?.close()
            connection = null
        }
    }

    private data class RequestSnapshot(val uri: String, val root: Path, val documents: Map<String, Snapshot>)
    private data class Connection(val root: Path, val settings: Map<String?, Any?>, val session: HeadlessSlangSession)

    private data class Snapshot(val document: Document, val stamp: Long, val text: CharSequence) {
        constructor(document: Document) : this(document, document.modificationStamp, document.immutableCharSequence)
    }
}
