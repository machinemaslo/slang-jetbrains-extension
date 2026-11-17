package slanglsp.server;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * LSP4IJ 0.21 can lose edits between capturing didOpen and installing its listener.
 * Rebase outgoing changes on the text actually sent, using immutable document snapshots.
 * Slangd also requires explicit ranges even for full-document changes.
 */
final class DocumentSynchronization {
    private record State(Document document, String text, int version) {}

    private final Map<String, State> opened = new HashMap<>();
    private final Function<String, Document> documents;

    DocumentSynchronization() {
        this(DocumentSynchronization::cachedDocument);
    }

    DocumentSynchronization(Function<String, Document> documents) {
        this.documents = documents;
    }

    synchronized void consume(Message message, MessageConsumer next) {
        if (message instanceof NotificationMessage notification) {
            if (notification.getParams() instanceof DidOpenTextDocumentParams open) {
                var item = open.getTextDocument();
                Document document = documents.apply(item.getUri());
                if (document != null) {
                    State existing = opened.get(item.getUri());
                    if (existing != null && existing.document() == document) {
                        // A batch connection may complete didOpen while the original
                        // editor continuation is still queued. Never open the URI twice.
                        synchronize(item.getUri(), next);
                        return;
                    }
                    item.setText(document.getImmutableCharSequence().toString());
                    opened.put(item.getUri(), new State(document, item.getText(), item.getVersion()));
                }
            } else if (notification.getParams() instanceof DidChangeTextDocumentParams change
                    && opened.containsKey(change.getTextDocument().getUri())) {
                synchronize(change.getTextDocument().getUri(), next);
                return;
            } else if (notification.getParams() instanceof DidCloseTextDocumentParams close) {
                opened.remove(close.getTextDocument().getUri());
            }
        } else if (message instanceof RequestMessage request) {
            // Formatting must not overtake changes queued on LSP4IJ's notification dispatcher.
            if (request.getParams() instanceof DocumentFormattingParams format) {
                synchronize(format.getTextDocument().getUri(), next);
            } else if (request.getParams() instanceof DocumentRangeFormattingParams format) {
                synchronize(format.getTextDocument().getUri(), next);
            }
        }
        next.consume(message);
    }

    private void synchronize(String uri, MessageConsumer next) {
        State state = opened.get(uri);
        if (state == null) {
            return;
        }
        String text = state.document().getImmutableCharSequence().toString();
        if (text.equals(state.text())) {
            return;
        }
        int start = 0;
        int oldEnd = state.text().length();
        int newEnd = text.length();
        while (start < oldEnd && start < newEnd && state.text().charAt(start) == text.charAt(start)) {
            start++;
        }
        if (start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))) {
            start--;
        }
        while (oldEnd > start && newEnd > start && state.text().charAt(oldEnd - 1) == text.charAt(newEnd - 1)) {
            oldEnd--;
            newEnd--;
        }
        if (newEnd < text.length() && newEnd > start && Character.isLowSurrogate(text.charAt(newEnd))) {
            oldEnd++;
            newEnd++;
        }
        var edit = new TextDocumentContentChangeEvent(
                new Range(position(state.text(), start), position(state.text(), oldEnd)),
                text.substring(start, newEnd));
        var notification = new NotificationMessage();
        notification.setMethod("textDocument/didChange");
        notification.setParams(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(uri, state.version() + 1), List.of(edit)));
        next.consume(notification);
        opened.put(uri, new State(state.document(), text, state.version() + 1));
    }

    private static Position position(String text, int offset) {
        int line = 0;
        int start = 0;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                start = i + 1;
            }
        }
        return new Position(line, offset - start);
    }

    private static Document cachedDocument(String value) {
        if (ApplicationManager.getApplication() == null) {
            return null;
        }
        URI uri = URI.create(value);
        if (!"file".equals(uri.getScheme())) {
            return null;
        }
        var file = LocalFileSystem.getInstance().findFileByPath(Path.of(uri).toString());
        return file == null ? null : FileDocumentManager.getInstance().getCachedDocument(file);
    }
}
