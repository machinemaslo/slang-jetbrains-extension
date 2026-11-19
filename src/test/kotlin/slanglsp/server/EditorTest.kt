package slanglsp.server

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import com.redhat.devtools.lsp4ij.LanguageServersRegistry
import com.redhat.devtools.lsp4ij.ServerStatus
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import slanglsp.editor.navigation.SymbolDeclarationHandler
import slanglsp.replaceAllText
import slanglsp.server.client.ClientFeatures
import slanglsp.server.client.FormattingSettings
import slanglsp.server.client.ServerClient
import slanglsp.server.protocol.DocumentSynchronization

internal class EditorTest {
    private var fixture: IdeaProjectTestFixture? = null

    @BeforeEach
    @Throws(Exception::class)
    fun setUp() {
        fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("SlangEditorTest").getFixture()
        runInEdtAndWait { fixture!!.setUp() }
    }

    @AfterEach
    @Throws(Exception::class)
    fun tearDown() {
        if (fixture != null) {
            runInEdtAndWait { fixture!!.tearDown() }
        }
    }

    @Test
    fun slangClientAndNavigationNeverClaimCppFiles() {
        runReadActionBlocking {
            val cpp = LightVirtualFile("test.cpp", "int main() { return 0; }")
            val features = ClientFeatures()
            Assertions.assertFalse(features.isEnabled(cpp))
            val file = PsiManager.getInstance(fixture!!.project).findFile(cpp)
            Assertions.assertNotNull(file)
            Assertions.assertFalse(LanguageServersRegistry.getInstance().isFileSupported(file))
            Assertions.assertNull(
                SymbolDeclarationHandler().getGotoDeclarationTargets(file!!.findElementAt(0), 0, null))
        }
    }

    @Test
    @Throws(Exception::class)
    fun formattingOptionsSurviveConfigurationRequestsAndCancelledContenders() {
        val settings = fixture!!.project.getService(FormattingSettings::class.java)
        val client = ServerClient(fixture!!.project)
        val key = "slang.format.clangFormatStyle"
        val original = client.findSettings(key)
        try {
            Executors.newSingleThreadExecutor().use { executor ->
                settings
                    .open(mapOf(key to "file:/first/_clang-format")) { false }
                    .use {
                        Assertions.assertEquals(
                            "file:/first/_clang-format",
                            executor.submit<Any> { client.findSettings(key) }.get(5, TimeUnit.SECONDS))
                        val contender =
                            executor.submit<Any?> {
                                settings
                                    .open(mapOf(key to "LLVM")) { true }
                                    .use {
                                        Assertions.fail<Any?>(
                                            "Cancelled contender must not replace the active formatting settings")
                                    }
                                null
                            }
                        val failure =
                            Assertions.assertThrows(ExecutionException::class.java) {
                                contender.get(5, TimeUnit.SECONDS)
                            }
                        Assertions.assertInstanceOf<ProcessCanceledException?>(
                            ProcessCanceledException::class.java, failure.cause)
                        Assertions.assertEquals("file:/first/_clang-format", client.findSettings(key))
                    }
                Assertions.assertEquals(original, client.findSettings(key))
                settings
                    .open(mapOf(key to "LLVM")) { false }
                    .use {
                        Assertions.assertEquals("LLVM", client.findSettings(key))
                    }
            }
        } finally {
            client.dispose()
        }
    }

    @Test
    fun clientRegistrationEndsOnDisposal() {
        val client: ServerClient =
            object : ServerClient(fixture!!.project) {
                // This lifecycle test has no remote language server.
                override fun triggerChangeConfiguration() {}
            }

        try {
            Assertions.assertFalse(ServerClient.activeClients.contains(client))
            client.handleServerStatusChanged(ServerStatus.started)
            client.handleServerStatusChanged(ServerStatus.started)
            Assertions.assertEquals(
                1, ServerClient.activeClients.stream().filter { c: ServerClient? -> c === client }.count())
            client.handleServerStatusChanged(ServerStatus.stopping)
            Assertions.assertTrue(ServerClient.activeClients.contains(client))
            client.dispose()
            Assertions.assertTrue(client.isDisposed)
            Assertions.assertFalse(ServerClient.activeClients.contains(client))
            client.handleServerStatusChanged(ServerStatus.started)
            Assertions.assertFalse(ServerClient.activeClients.contains(client))
        } finally {
            client.dispose()
        }
    }

    @Test
    fun startupAndQueuedChangesUseTheCurrentDocumentBeforeFormatting() {
        runInEdtAndWait {
            val document = EditorFactory.getInstance().createDocument("// é🙂\nfloat value=2;")
            val uri = "file:///sample.slang"
            val synchronization = DocumentSynchronization { document }
            val messages = ArrayList<Message?>()
            val open = NotificationMessage()
            open.method = "textDocument/didOpen"
            val item = TextDocumentItem(uri, "slang", 1, "old snapshot")
            open.params = DidOpenTextDocumentParams(item)
            synchronization.consume(open) { e: Message? -> messages.add(e) }
            Assertions.assertEquals(document.text, item.text)

            WriteCommandAction.runWriteCommandAction(fixture!!.project) {
                document.replaceAllText("// é🙂\nfloat value=3;")
            }
            val formatting = RequestMessage()
            formatting.id = "format"
            formatting.method = "textDocument/formatting"
            formatting.params = DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(4, true))
            synchronization.consume(formatting) { e: Message? -> messages.add(e) }
            Assertions.assertEquals(3, messages.size)
            val change = (messages[1] as NotificationMessage).params as DidChangeTextDocumentParams
            Assertions.assertEquals(2, change.textDocument.version)
            val edit: TextDocumentContentChangeEvent = change.contentChanges.first()
            Assertions.assertEquals(Range(Position(1, 12), Position(1, 13)), edit.range)
            Assertions.assertEquals("3", edit.text)
            Assertions.assertSame(formatting, messages[2])

            // A delayed incremental event already covered by that snapshot must not be applied twice.
            val delayed =
                NotificationMessage().apply {
                    method = "textDocument/didChange"
                    params =
                        DidChangeTextDocumentParams(
                            VersionedTextDocumentIdentifier(uri, 2),
                            listOf(
                                TextDocumentContentChangeEvent(Range(Position(0, 0), Position(0, 12)), "stale edit")))
                }
            synchronization.consume(delayed) { e: Message? -> messages.add(e) }
            Assertions.assertEquals(3, messages.size)

            // Batch readiness and a delayed editor continuation share one wire-level open.
            synchronization.consume(open) { e: Message? -> messages.add(e) }
            Assertions.assertEquals(
                3, messages.size, "A queued editor didOpen must not reopen an already connected document")

            val close = NotificationMessage()
            close.method = "textDocument/didClose"
            close.params = DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
            synchronization.consume(close) { e: Message? -> messages.add(e) }
            synchronization.consume(open) { e: Message? -> messages.add(e) }
            Assertions.assertEquals(5, messages.size, "An actual close/reopen must still be transmitted")
        }
    }
}
