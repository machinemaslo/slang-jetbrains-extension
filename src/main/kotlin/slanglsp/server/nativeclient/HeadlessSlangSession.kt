package slanglsp.server.nativeclient

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.EnvironmentUtil
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import slanglsp.server.protocol.ResponseInputStream

/** Background LSP transport. Neither startup nor server callbacks depend on IDE write actions. */
internal class HeadlessSlangSession private constructor(
    val server: LanguageServer,
    val capabilities: ServerCapabilities,
    private val process: Process,
    private val listening: Future<Void>,
) : AutoCloseable {
    val isAlive: Boolean
        get() = process.isAlive && !listening.isDone

    suspend fun shutdown() {
        withTimeoutOrNull(250.milliseconds) {
            server.shutdown().await()
            server.exit()
        }
    }

    override fun close() {
        listening.cancel(true)
        stop(process)
    }

    companion object {
        suspend fun start(
            executable: String,
            root: Path,
            projectName: String,
            settings: Map<String?, Any?>,
        ): HeadlessSlangSession {
            val folder = WorkspaceFolder(root.toUri().toASCIIString(), projectName)
            val process = ProcessBuilder(executable)
                .directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { environment().putAll(EnvironmentUtil.getEnvironmentMap()) }
                .start()

            var listening: Future<Void>? = null
            try {
                val launcher = SessionLauncher()
                    .setLocalService(SessionClient(settings, listOf(folder)))
                    .setRemoteInterface(LanguageServer::class.java)
                    .setInput(process.inputStream)
                    .setOutput(process.outputStream)
                    .setExecutorService(AppExecutorUtil.getAppExecutorService())
                    .create()
                listening = launcher.startListening()
                val server = launcher.remoteProxy
                @Suppress("DEPRECATION")
                val params = InitializeParams().apply {
                    processId = ProcessHandle.current().pid().toInt()
                    rootUri = folder.uri
                    rootPath = root.toString()
                    workspaceFolders = listOf(folder)
                    capabilities = ClientCapabilities().apply {
                        workspace = WorkspaceClientCapabilities().apply {
                            configuration = true
                            workspaceFolders = true
                        }
                    }
                }
                val capabilities = server.initialize(params).await().capabilities
                server.initialized(InitializedParams())
                server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(settings))
                return HeadlessSlangSession(server, capabilities, process, listening)
            } catch (failure: Throwable) {
                listening?.cancel(true)
                stop(process)
                throw failure
            }
        }

        private fun stop(process: Process) {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
        }
    }

    private class SessionLauncher : Launcher.Builder<LanguageServer>() {
        override fun createRemoteEndpoint(handler: MessageJsonHandler): RemoteEndpoint {
            val endpoint = super.createRemoteEndpoint(handler)
            input = ResponseInputStream(input) { id -> handler.methodProvider.resolveMethod(id) }
            return endpoint
        }
    }

    private class SessionClient(
        private val settings: Map<String?, Any?>,
        private val folders: List<WorkspaceFolder>,
    ) : LanguageClient {
        override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any?>> {
            return CompletableFuture.completedFuture(params.items.map { item ->
                if (item.section.isNullOrEmpty()) settings else settings[item.section]
            })
        }

        override fun workspaceFolders(): CompletableFuture<List<WorkspaceFolder>> =
            CompletableFuture.completedFuture(folders)

        override fun registerCapability(params: RegistrationParams): CompletableFuture<Void?> =
            CompletableFuture.completedFuture(null)

        override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void?> =
            CompletableFuture.completedFuture(null)

        override fun refreshInlayHints(): CompletableFuture<Void?> = CompletableFuture.completedFuture(null)

        override fun refreshSemanticTokens(): CompletableFuture<Void?> = CompletableFuture.completedFuture(null)

        override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem?> =
            CompletableFuture.completedFuture(null)

        override fun telemetryEvent(value: Any?) = Unit
        override fun publishDiagnostics(params: PublishDiagnosticsParams) = Unit
        override fun showMessage(params: MessageParams) = logMessage(params)
        override fun logMessage(params: MessageParams) {
            Logger.getInstance(HeadlessSlangSession::class.java).debug(params.message)
        }
    }
}
