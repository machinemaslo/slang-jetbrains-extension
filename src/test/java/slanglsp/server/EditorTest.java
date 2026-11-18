package slanglsp.server;

import slanglsp.editor.SlangGotoDeclarationHandler;

import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EditorTest {
    private IdeaProjectTestFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("SlangEditorTest").getFixture();
        com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(() -> fixture.setUp());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fixture != null)
            com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(() -> fixture.tearDown());
    }

    @Test
    void slangClientAndNavigationNeverClaimCppFiles() {
        com.intellij.openapi.application.ReadAction.run(() -> {
            var cpp = new com.intellij.testFramework.LightVirtualFile("test.cpp", "int main() { return 0; }");
            var features = new ClientFeatures();
            assertFalse(features.isEnabled(cpp));
            var file = com.intellij.psi.PsiManager.getInstance(fixture.getProject()).findFile(cpp);
            assertNotNull(file);
            assertFalse(com.redhat.devtools.lsp4ij.LanguageServersRegistry.getInstance().isFileSupported(file));
            assertNull(new SlangGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(0), 0, null));
        });
    }

    @Test
    @SuppressWarnings("try") // The sessions own the temporary formatting settings.
    void formattingOptionsSurviveConfigurationRequestsAndCancelledContenders() throws Exception {
        var settings = fixture.getProject().getService(FormattingSettings.class);
        var client = new ServerClient(fixture.getProject());
        String key = "slang.format.clangFormatStyle";
        Object original = client.findSettings(key);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            try (@SuppressWarnings("unused") var session =
                         settings.open(java.util.Map.of(key, "file:/first/_clang-format"), () -> false)) {
                assertEquals("file:/first/_clang-format",
                        executor.submit(() -> client.findSettings(key)).get(5, java.util.concurrent.TimeUnit.SECONDS));
                var contender = executor.submit(() -> {
                    try (@SuppressWarnings("unused") var ignored = settings.open(java.util.Map.of(key, "LLVM"), () -> true)) {
                        fail("Cancelled contender must not replace the active formatting settings");
                    }
                    return null;
                });
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> contender.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertInstanceOf(com.intellij.openapi.progress.ProcessCanceledException.class, failure.getCause());
                assertEquals("file:/first/_clang-format", client.findSettings(key));
            }
            assertEquals(original, client.findSettings(key));
            try (@SuppressWarnings("unused") var session = settings.open(java.util.Map.of(key, "LLVM"), () -> false)) {
                assertEquals("LLVM", client.findSettings(key));
            }
        } finally {
            client.dispose();
        }
    }

    @Test
    void clientRegistrationEndsOnDisposal() {
        var client = new ServerClient(fixture.getProject()) {
            // This lifecycle test has no remote language server.
            @Override
            public void triggerChangeConfiguration() {
            }
        };

        try {
            assertFalse(ServerClient.activeClients.contains(client));
            client.handleServerStatusChanged(com.redhat.devtools.lsp4ij.ServerStatus.started);
            client.handleServerStatusChanged(com.redhat.devtools.lsp4ij.ServerStatus.started);
            assertEquals(1, ServerClient.activeClients.stream().filter(c -> c == client).count());
            client.handleServerStatusChanged(com.redhat.devtools.lsp4ij.ServerStatus.stopping);
            assertTrue(ServerClient.activeClients.contains(client));
            client.dispose();
            assertTrue(client.isDisposed());
            assertFalse(ServerClient.activeClients.contains(client));
            client.handleServerStatusChanged(com.redhat.devtools.lsp4ij.ServerStatus.started);
            assertFalse(ServerClient.activeClients.contains(client));
        } finally {
            client.dispose();
        }
    }

    @Test
    void startupAndQueuedChangesUseTheCurrentDocumentBeforeFormatting() {
        com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(() -> {
            var document = com.intellij.openapi.editor.EditorFactory.getInstance()
                    .createDocument("// é🙂\nfloat value=2;");
            String uri = "file:///sample.slang";
            var synchronization = new DocumentSynchronization(ignored -> document);
            var messages = new java.util.ArrayList<org.eclipse.lsp4j.jsonrpc.messages.Message>();
            var open = new org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage();
            open.setMethod("textDocument/didOpen");
            var item = new org.eclipse.lsp4j.TextDocumentItem(uri, "slang", 1, "old snapshot");
            open.setParams(new org.eclipse.lsp4j.DidOpenTextDocumentParams(item));
            synchronization.consume(open, messages::add);
            assertEquals(document.getText(), item.getText());

            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(fixture.getProject(),
                    () -> document.setText("// é🙂\nfloat value=3;"));
            var formatting = new org.eclipse.lsp4j.jsonrpc.messages.RequestMessage();
            formatting.setId("format");
            formatting.setMethod("textDocument/formatting");
            formatting.setParams(new org.eclipse.lsp4j.DocumentFormattingParams(
                    new org.eclipse.lsp4j.TextDocumentIdentifier(uri), new org.eclipse.lsp4j.FormattingOptions(4, true)));
            synchronization.consume(formatting, messages::add);
            assertEquals(3, messages.size());
            var change = (org.eclipse.lsp4j.DidChangeTextDocumentParams)
                    ((org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage) messages.get(1)).getParams();
            assertEquals(2, change.getTextDocument().getVersion());
            var edit = change.getContentChanges().getFirst();
            assertEquals(new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(1, 12),
                    new org.eclipse.lsp4j.Position(1, 13)), edit.getRange());
            assertEquals("3", edit.getText());
            assertSame(formatting, messages.get(2));

            // A delayed incremental event already covered by that snapshot must not be applied twice.
            synchronization.consume(delayedChange(uri), messages::add);
            assertEquals(3, messages.size());

            // Batch readiness and a delayed editor continuation share one wire-level open.
            synchronization.consume(open, messages::add);
            assertEquals(3, messages.size(), "A queued editor didOpen must not reopen an already connected document");

            var close = new org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage();
            close.setMethod("textDocument/didClose");
            close.setParams(new org.eclipse.lsp4j.DidCloseTextDocumentParams(
                    new org.eclipse.lsp4j.TextDocumentIdentifier(uri)));
            synchronization.consume(close, messages::add);
            synchronization.consume(open, messages::add);
            assertEquals(5, messages.size(), "An actual close/reopen must still be transmitted");
        });
    }

    private static org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage delayedChange(String uri) {
        var delayed = new org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage();
        delayed.setMethod("textDocument/didChange");
        delayed.setParams(new org.eclipse.lsp4j.DidChangeTextDocumentParams(
                new org.eclipse.lsp4j.VersionedTextDocumentIdentifier(uri, 2),
                java.util.List.of(new org.eclipse.lsp4j.TextDocumentContentChangeEvent(
                        new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(0, 0),
                                new org.eclipse.lsp4j.Position(0, 12)), "stale edit"))));
        return delayed;
    }
}
