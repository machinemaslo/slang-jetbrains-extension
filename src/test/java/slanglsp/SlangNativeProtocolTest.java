package slanglsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional end-to-end test against a real slangd, using the plugin's LSP4J transport. */
class SlangNativeProtocolTest {
    @TempDir Path project;

    @Test void dottedImportResolvesOverLspWithWorkspaceInitializationAndConfiguration() throws Exception {
        assertImportResolves(true);
    }

    @Test void configuredSearchPathsResolveImportsWithWorkspaceSearchDisabled() throws Exception {
        assertImportResolves(false);
    }

    private void assertImportResolves(boolean searchWorkspace) throws Exception {
        String executable = System.getenv("SLANGD_TEST_EXECUTABLE");
        assumeTrue(executable != null, "Set SLANGD_TEST_EXECUTABLE to run the native protocol test");
        Path scene = project.resolve("src/shader/shared/scene.slang");
        Path source = project.resolve("src/shader/page/common/bounds.slang");
        Files.createDirectories(scene.getParent());
        Files.createDirectories(source.getParent());
        Files.writeString(scene, """
                module scene;
                /// Stores **scene lighting** data.
                public struct Scene { float value; };
                /// Computes the surface lighting.
                /// @param value Input intensity.
                /// @return The shaded intensity.
                public float shade(float value) { return value; }
                /// Holds material properties.
                public class Material { public float roughness; }
                """);
        Files.writeString(source, "import shared.scene;\nScene scene;\nfloat result = shade(1.0);\nMaterial material;\n");

        var state = new SlangPersistentStateConfig.State();
        state.enableSearchingSubDirectoriesOfWorkspace = searchWorkspace;
        // Exercise both VS Code's default workspace discovery and explicit user paths.
        if (!searchWorkspace) state.additionalIncludePaths = List.of(project.resolve("src/shader").toString());
        var configRequests = new AtomicInteger();
        Map<String, Object> settings = state.createServerSettings();
        var client = new LanguageClient() {
            @Override public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
                configRequests.incrementAndGet();
                return CompletableFuture.completedFuture(SlangServerConfiguration.select(settings, params));
            }
            @Override public void telemetryEvent(Object object) { }
            @Override public void publishDiagnostics(PublishDiagnosticsParams params) { }
            @Override public void showMessage(MessageParams params) { }
            @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public void logMessage(MessageParams params) { }
            @Override public CompletableFuture<Void> registerCapability(RegistrationParams params) {
                return CompletableFuture.completedFuture(null);
            }
        };
        Process process = new ProcessBuilder(executable).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var launcher = LSPLauncher.createClientLauncher(client, new SlangResponseInputStream(process.getInputStream()), process.getOutputStream());
        var listening = launcher.startListening();
        var server = launcher.getRemoteProxy();
        try {
            var params = new InitializeParams();
            var capabilities = new ClientCapabilities();
            var workspace = new WorkspaceClientCapabilities();
            workspace.setConfiguration(true);
            capabilities.setWorkspace(workspace);
            params.setCapabilities(capabilities);
            SlangClientFeatures.configureWorkspace(params, project.toString(), "Shaders");
            server.initialize(params).get(15, TimeUnit.SECONDS);
            server.initialized(new InitializedParams());
            server.getWorkspaceService().didChangeConfiguration(new DidChangeConfigurationParams(settings));
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(
                    source.toUri().toString(), "slang", 1, Files.readString(source))));
            var definition = server.getTextDocumentService().definition(new DefinitionParams(
                    new TextDocumentIdentifier(source.toUri().toString()), new Position(1, 2)))
                    .get(15, TimeUnit.SECONDS);
            assertNotNull(definition);
            var uris = definition.isLeft()
                    ? definition.getLeft().stream().map(Location::getUri).toList()
                    : definition.getRight().stream().map(LocationLink::getTargetUri).toList();
            assertTrue(uris.stream().anyMatch(uri -> Path.of(java.net.URI.create(uri)).equals(scene)),
                    "Go to definition must resolve Scene to shared/scene.slang: " + uris);
            var imported = server.getTextDocumentService().definition(new DefinitionParams(
                    new TextDocumentIdentifier(source.toUri().toString()), new Position(0, 18)))
                    .get(15, TimeUnit.SECONDS);
            assertNotNull(imported);
            var importUris = imported.isLeft() ? imported.getLeft().stream().map(Location::getUri).toList()
                    : imported.getRight().stream().map(LocationLink::getTargetUri).toList();
            assertTrue(importUris.stream().anyMatch(uri -> Path.of(java.net.URI.create(uri)).equals(scene)));
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(
                    scene.toUri().toString(), "slang", 1, Files.readString(scene))));
            assertReferencesContain(server, scene, new Position(2, 15), source, 1);
            assertReferencesContain(server, scene, new Position(6, 15), source, 2);
            assertReferencesContain(server, scene, new Position(8, 15), source, 3);
            assertReferencesContain(server, scene, new Position(6, 27), scene, 6);
            assertTrue(configRequests.get() > 0, "slangd must request the client's settings");
            assertHoverContains(server, source, new Position(1, 2), "scene lighting");
            assertHoverContains(server, source, new Position(2, 17), "surface lighting");
            assertHoverContains(server, source, new Position(3, 2), "material properties");
            server.shutdown().get(5, TimeUnit.SECONDS);
            server.exit();
        } finally {
            process.destroyForcibly();
            listening.cancel(true);
        }
    }

    private static void assertHoverContains(org.eclipse.lsp4j.services.LanguageServer server,
                                           Path source, Position position, String documentation) throws Exception {
        Hover hover = server.getTextDocumentService().hover(new HoverParams(
                new TextDocumentIdentifier(source.toUri().toString()), position)).get(15, TimeUnit.SECONDS);
        assertNotNull(hover, "slangd should return symbol documentation");
        var contents = com.redhat.devtools.lsp4ij.features.documentation.LSPDocumentationHelper.getValidMarkupContents(hover);
        assertTrue(contents.stream().anyMatch(content -> content.getValue().contains(documentation)),
                "Expected documentation: " + documentation + " in " + contents);
    }

    private static void assertReferencesContain(org.eclipse.lsp4j.services.LanguageServer server, Path declaration,
                                                Position position, Path usageFile, int usageLine) throws Exception {
        var references = server.getTextDocumentService().references(new ReferenceParams(
                new TextDocumentIdentifier(declaration.toUri().toString()), position, new ReferenceContext(false)))
                .get(15, TimeUnit.SECONDS);
        assertNotNull(references);
        assertTrue(references.stream().anyMatch(location ->
                Path.of(java.net.URI.create(location.getUri())).equals(usageFile)
                        && location.getRange().getStart().getLine() == usageLine), "Expected usages: " + references);
    }

}
