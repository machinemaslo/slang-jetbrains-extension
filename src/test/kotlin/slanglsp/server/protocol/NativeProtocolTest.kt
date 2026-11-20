package slanglsp.server.protocol

import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import slanglsp.TestDependencies
import slanglsp.server.nativeclient.HeadlessSlangSession
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
        val settings = state.createServerSettings()
        val session = runBlocking {
            withTimeout(15.seconds) { HeadlessSlangSession.start(executable, project!!, "Shaders", settings) }
        }
        session.use {
            val server = session.server
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
            assertHoverContains(server, source, Position(1, 2), "scene lighting")
            assertHoverContains(server, source, Position(2, 17), "surface lighting")
            assertHoverContains(server, source, Position(3, 2), "material properties")
            runBlocking { session.shutdown() }
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
            val markup = hover!!.contents
            val contents = if (markup.isRight) listOf(markup.right.value) else {
                markup.left.map { if (it.isLeft) it.left else it.right.value }
            }
            Assertions.assertTrue(
                contents.any { documentation in it },
                "Expected documentation: $documentation in $contents")
        }
    }
}
