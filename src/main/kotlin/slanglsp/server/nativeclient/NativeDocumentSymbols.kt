package slanglsp.server.nativeclient

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.getClients
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.future
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams

@Service(Service.Level.PROJECT)
internal class NativeDocumentSymbols(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    fun request(file: VirtualFile): CompletableFuture<List<DocumentSymbol>?> {
        return scope.future {
            withTimeoutOrNull(10.seconds) {
                val client = awaitClient(file) ?: return@withTimeoutOrNull null
                val params = DocumentSymbolParams(client.getDocumentIdentifier(file))
                val symbols = client.sendRequest { it.textDocumentService.documentSymbol(params) }
                    ?: return@withTimeoutOrNull null

                // Slang analyses require hierarchical symbols, not SymbolInformation entries.
                symbols.map { it.right ?: return@withTimeoutOrNull null }
            }
        }
    }

    private suspend fun awaitClient(file: VirtualFile): LspClient? {
        val editors = FileEditorManager.getInstance(project)
        while (file.isValid && editors.isFileOpen(file)) {
            runningSlangClient(project)?.let { return it }
            delay(50.milliseconds)
        }

        return null
    }
}

internal fun runningSlangClient(project: Project): LspClient? {
    if (project.isDefault || project.isDisposed) {
        return null
    }

    return LspClientManager.getInstance(project).getClients<SlangLspProvider>()
        .firstOrNull { it.state == LspServerState.Running }
}
