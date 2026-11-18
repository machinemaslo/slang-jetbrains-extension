package slanglsp.server;

import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import com.redhat.devtools.lsp4ij.server.DefaultLauncherBuilder;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.MessageIssueHandler;
import org.eclipse.lsp4j.jsonrpc.MessageProducer;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;

/** Isolated compatibility for LSP4IJ's stale didOpen snapshot and slangd's empty definition result. */
final class ProtocolCompatibility<S extends LanguageServer> extends DefaultLauncherBuilder<S> {
    private final DocumentSynchronization documents = new DocumentSynchronization();

    ProtocolCompatibility(LSPClientFeatures features) {
        super(features);
    }

    @Override
    protected MessageConsumer wrapMessageConsumer(MessageConsumer consumer) {
        var wrapped = super.wrapMessageConsumer(consumer);
        return message -> documents.consume(message, wrapped);
    }

    @Override
    protected @NotNull MessageProducer createMessageProducer(@NotNull InputStream input,
            @NotNull MessageJsonHandler handler, @NotNull MessageIssueHandler issues) {
        return super.createMessageProducer(new ResponseInputStream(input,
                id -> handler.getMethodProvider().resolveMethod(id)), handler, issues);
    }
}
