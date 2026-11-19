package slanglsp.server.protocol

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.net.URI
import java.nio.file.Path
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage

/**
 * LSP4IJ 0.21 can lose edits between capturing didOpen and installing its listener. Rebase outgoing changes on the text
 * actually sent, using immutable document snapshots. Slangd also requires explicit ranges even for full-document
 * changes.
 */
internal class DocumentSynchronization(private val documents: (String) -> Document? = ::cachedDocument) {
    @JvmRecord private data class State(val document: Document, val text: String, val version: Int)

    private val opened = mutableMapOf<String?, State>()

    @Synchronized
    fun consume(message: Message?, next: MessageConsumer) {
        if (message is NotificationMessage) {
            val params = message.params
            if (params is DidOpenTextDocumentParams) {
                val item = params.textDocument
                val document = documents(item.uri)
                if (document != null) {
                    val existing = opened[item.uri]
                    if (existing != null && existing.document === document) {
                        // A batch connection may complete didOpen while the original
                        // editor continuation is still queued. Never open the URI twice.
                        synchronize(item.uri, next)
                        return
                    }
                    item.text = document.immutableCharSequence.toString()
                    opened[item.uri] = State(document, item.text, item.version)
                }
            } else if (params is DidChangeTextDocumentParams && opened.containsKey(params.textDocument.uri)) {
                synchronize(params.textDocument.uri, next)
                return
            } else if (params is DidCloseTextDocumentParams) {
                opened.remove(params.textDocument.uri)
            }
        } else if (message is RequestMessage) {
            // Formatting must not overtake changes queued on LSP4IJ's notification dispatcher.
            when (val params = message.params) {
                is DocumentFormattingParams -> synchronize(params.textDocument.uri, next)
                is DocumentRangeFormattingParams -> synchronize(params.textDocument.uri, next)
            }
        }
        next.consume(message)
    }

    private fun synchronize(uri: String?, next: MessageConsumer) {
        val state = opened[uri] ?: return
        val text = state.document.immutableCharSequence.toString()
        if (text == state.text) {
            return
        }
        var start = 0
        var oldEnd = state.text.length
        var newEnd = text.length
        while (start < oldEnd && start < newEnd && state.text[start] == text[start]) {
            start++
        }
        if (start > 0 && start < text.length && Character.isLowSurrogate(text[start])) {
            start--
        }
        while (oldEnd > start && newEnd > start && state.text[oldEnd - 1] == text[newEnd - 1]) {
            oldEnd--
            newEnd--
        }
        if (newEnd < text.length && newEnd > start && Character.isLowSurrogate(text[newEnd])) {
            oldEnd++
            newEnd++
        }
        val edit =
            TextDocumentContentChangeEvent(
                Range(position(state.text, start), position(state.text, oldEnd)), text.substring(start, newEnd))
        val notification = NotificationMessage()
        notification.method = "textDocument/didChange"
        notification.params =
            DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, state.version + 1), listOf(edit))
        next.consume(notification)
        opened[uri] = State(state.document, text, state.version + 1)
    }

    companion object {
        private fun position(text: String, offset: Int): Position {
            var line = 0
            var start = 0
            for (i in 0..<offset) {
                if (text[i] == '\n') {
                    line++
                    start = i + 1
                }
            }
            return Position(line, offset - start)
        }

        private fun cachedDocument(value: String): Document? {
            if (ApplicationManager.getApplication() == null) {
                return null
            }
            val uri = URI.create(value)
            if ("file" != uri.scheme) {
                return null
            }
            val file = LocalFileSystem.getInstance().findFileByPath(Path.of(uri).toString())
            return if (file == null) null else FileDocumentManager.getInstance().getCachedDocument(file)
        }
    }
}
