package slanglsp.server.protocol

import com.redhat.devtools.lsp4ij.features.documentation.LSPDocumentationHelper
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import slanglsp.TestDependencies
import slanglsp.server.ServerConfiguration.select
import slanglsp.server.client.ClientFeatures
import slanglsp.server.client.ClientFeatures.Companion.configureWorkspace
import slanglsp.settings.project.ProjectSettings

/** Optional end-to-end test against a real slangd, using the plugin's LSP4J transport. */
internal class NativeProtocolTest {
    @TempDir var project: Path? = null

    @Test
    @Throws(Exception::class)
    fun importsHoverAndSemanticTokensWorkWithNativeServer() {
        val executable = TestDependencies.slangd()
        val scene = project!!.resolve("src/shader/shared/scene.slang")
        val source = project!!.resolve("src/shader/page/common/bounds.slang")
        Files.createDirectories(scene.parent)
        Files.createDirectories(source.parent)
        Files.writeString(
            scene,
            """
                module scene;
                /// Stores **scene lighting** data.
                public struct Scene { float value; };
                /// Computes the surface lighting.
                /// @param value Input intensity.
                /// @return The shaded intensity.
                public float shade(float value) { return value; }
                /// Holds material properties.
                public class Material { public float roughness; }
                
                """
                .trimIndent())
        Files.writeString(
            source, "import shared.scene;\nScene scene;\nfloat result = shade(1.0);\nMaterial material;\n")

        val state = ProjectSettings.State()
        state.enableSearchingSubDirectoriesOfWorkspace = false
        state.additionalIncludePaths = mutableListOf(project!!.resolve("src/shader").toString())
        val configRequests = AtomicInteger()
        val settings = state.createServerSettings()
        val client: LanguageClient =
            object : LanguageClient {
                override fun configuration(params: ConfigurationParams): CompletableFuture<MutableList<Any?>?> {
                    configRequests.incrementAndGet()

                    return CompletableFuture.completedFuture(select(settings, params))
                }

                override fun telemetryEvent(`object`: Any?) {}

                override fun publishDiagnostics(params: PublishDiagnosticsParams?) {}

                override fun showMessage(params: MessageParams?) {}

                override fun showMessageRequest(
                    params: ShowMessageRequestParams?
                ): CompletableFuture<MessageActionItem?> {
                    return CompletableFuture.completedFuture(null)
                }

                override fun logMessage(params: MessageParams?) {}

                override fun registerCapability(params: RegistrationParams?): CompletableFuture<Void?> {
                    return CompletableFuture.completedFuture(null)
                }

                override fun refreshInlayHints(): CompletableFuture<Void?> {
                    return CompletableFuture.completedFuture(null)
                }

                override fun refreshSemanticTokens(): CompletableFuture<Void?> {
                    return CompletableFuture.completedFuture(null)
                }
            }
        val process = ProcessBuilder(executable).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val launcher =
            ClientFeatures()
                .createLauncherBuilder<LanguageServer?>()
                .setLocalService(client)
                .setRemoteInterface(LanguageServer::class.java)
                .setInput(process.inputStream)
                .setOutput(process.outputStream)
                .create()
        val listening = launcher.startListening()
        val server = launcher.getRemoteProxy()

        try {
            val params = InitializeParams()
            val capabilities = ClientCapabilities()
            val workspace = WorkspaceClientCapabilities()
            workspace.configuration = true
            capabilities.workspace = workspace
            params.capabilities = capabilities
            configureWorkspace(params, project.toString(), "Shaders")
            server!!.initialize(params).get(15, TimeUnit.SECONDS)
            server.initialized(InitializedParams())
            server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(settings))
            server.textDocumentService.didOpen(
                DidOpenTextDocumentParams(
                    TextDocumentItem(source.toUri().toString(), "slang", 1, Files.readString(source))))
            val definition =
                server.textDocumentService
                    .definition(DefinitionParams(TextDocumentIdentifier(source.toUri().toString()), Position(1, 2)))
                    .get(15, TimeUnit.SECONDS)
            Assertions.assertNotNull(definition)
            val uris: MutableList<String> =
                if (definition!!.isLeft) definition.getLeft()!!.stream().map { obj -> obj!!.uri }.toList()
                else definition.getRight()!!.stream().map { obj -> obj!!.targetUri }.toList()
            assertDefinitionTargets(uris, scene)
            val imported =
                server.textDocumentService
                    .definition(DefinitionParams(TextDocumentIdentifier(source.toUri().toString()), Position(0, 18)))
                    .get(15, TimeUnit.SECONDS)
            Assertions.assertNotNull(imported)
            val importUris: MutableList<String> =
                if (imported!!.isLeft) imported.getLeft()!!.stream().map { obj -> obj!!.uri }.toList()
                else imported.getRight()!!.stream().map { obj -> obj!!.targetUri }.toList()
            assertDefinitionTargets(importUris, scene)
            server.textDocumentService.didOpen(
                DidOpenTextDocumentParams(
                    TextDocumentItem(scene.toUri().toString(), "slang", 1, Files.readString(scene))))
            val tokens =
                server.textDocumentService
                    .semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(source.toUri().toString())))
                    .get(15, TimeUnit.SECONDS)
            Assertions.assertNotNull(tokens)
            Assertions.assertFalse(tokens!!.data.isEmpty(), "slangd should return semantic colors")
            Assertions.assertEquals(0, tokens.data.size % 5)
            Assertions.assertTrue(configRequests.get() > 0, "slangd must request the client's settings")
            assertHoverContains(server, source, Position(1, 2), "scene lighting")
            assertHoverContains(server, source, Position(2, 17), "surface lighting")
            assertHoverContains(server, source, Position(3, 2), "material properties")
            server.shutdown().get(5, TimeUnit.SECONDS)
            server.exit()
        } finally {
            process.destroyForcibly()
            listening.cancel(true)
        }
    }

    companion object {
        @Throws(IOException::class)
        private fun assertDefinitionTargets(uris: MutableList<String>, expected: Path) {
            for (uri in uris) {
                // slangd canonicalizes paths; macOS temporary directories may use a symlink alias.
                if (Files.isSameFile(Path.of(URI.create(uri)), expected)) {
                    return
                }
            }
            Assertions.fail<Any?>("Go to definition must resolve to $expected: $uris")
        }

        @Throws(Exception::class)
        private fun assertHoverContains(
            server: LanguageServer,
            source: Path,
            position: Position,
            documentation: String
        ) {
            val hover =
                server.textDocumentService
                    .hover(HoverParams(TextDocumentIdentifier(source.toUri().toString()), position))
                    .get(15, TimeUnit.SECONDS)
            Assertions.assertNotNull(hover, "slangd should return symbol documentation")
            val contents = LSPDocumentationHelper.getValidMarkupContents(hover)
            Assertions.assertTrue(
                contents.stream().anyMatch { content: MarkupContent? -> content!!.value.contains(documentation) },
                "Expected documentation: $documentation in $contents")
        }
    }
}
