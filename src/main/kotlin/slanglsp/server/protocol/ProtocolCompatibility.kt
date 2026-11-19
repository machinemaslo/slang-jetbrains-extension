package slanglsp.server.protocol

import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.DefaultLauncherBuilder
import java.io.InputStream
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.MessageIssueHandler
import org.eclipse.lsp4j.jsonrpc.MessageProducer
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.services.LanguageServer

/** Normalizes stale didOpen snapshots and empty definitions through the LSP4IJ launcher extension. */
@Suppress("UnstableApiUsage")
internal class ProtocolCompatibility<S : LanguageServer?>(features: LSPClientFeatures) :
    DefaultLauncherBuilder<S?>(features) {
    private val documents = DocumentSynchronization()

    override fun wrapMessageConsumer(consumer: MessageConsumer?): MessageConsumer {
        val wrapped = super.wrapMessageConsumer(consumer)
        return MessageConsumer { message: Message? -> documents.consume(message, wrapped) }
    }

    override fun createMessageProducer(
        input: InputStream,
        handler: MessageJsonHandler,
        issues: MessageIssueHandler
    ): MessageProducer {
        return super.createMessageProducer(
            ResponseInputStream(input) { id: String? -> handler.methodProvider.resolveMethod(id) }, handler, issues)
    }
}
