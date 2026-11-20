package slanglsp.server.nativeclient

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageServer

/** Live editor requests and snapshot verification for files that may not have an open editor. */
@Service(Service.Level.PROJECT)
internal class NativeNavigationRequests(private val project: Project, private val scope: CoroutineScope) {
    fun definitions(
        file: VirtualFile,
        document: Document,
        offset: Int,
        verify: Boolean,
    ): CompletableFuture<List<Location>?> {
        return request(file, document, offset, verify, send = { server, identifier, position ->
            server.textDocumentService.definition(DefinitionParams(identifier, position))
        }) { response ->
            when {
                response == null -> null
                response.isLeft -> response.left.mapNotNull { location(it.uri, it.range) }
                else -> response.right.mapNotNull { location(it.targetUri, it.targetSelectionRange) }
            }
        }
    }

    fun references(file: VirtualFile, document: Document, offset: Int): CompletableFuture<List<Location>?> {
        return request(file, document, offset, supported = { client ->
            val capability = client.initializeResult?.capabilities?.referencesProvider
            // Older slangd versions only support definition requests. Keep the indexed fallback.
            capability != null && (capability.isRight || capability.left == true)
        }, send = { server, identifier, position ->
            val params = ReferenceParams(identifier, position, ReferenceContext(false))
            server.textDocumentService.references(params)
        }) { response ->
            response?.mapNotNull { location(it.uri, it.range) }
        }
    }

    fun hover(
        file: VirtualFile,
        document: Document,
        offset: Int,
        verify: Boolean = false,
    ): CompletableFuture<List<HoverText>?> {
        return request(file, document, offset, verify, send = { server, identifier, position ->
            server.textDocumentService.hover(HoverParams(identifier, position))
        }) { response ->
            val contents = response?.contents ?: return@request null

            if (contents.isRight) {
                val markup = contents.right
                listOf(HoverText(markup.value, markup.kind == MarkupKind.MARKDOWN))
            } else {
                contents.left.mapNotNull { entry ->
                    when {
                        entry.isLeft -> entry.left?.let { HoverText(it, markdown = true) }
                        else -> entry.right?.let {
                            HoverText("```${it.language}\n${it.value}\n```", markdown = true)
                        }
                    }
                }
            }
        }
    }

    fun verificationSymbols(file: VirtualFile, document: Document, stamp: Long): CompletableFuture<List<DocumentSymbol>?> {
        return scope.future {
            project.service<NativeVerification>().request(file, document, stamp) { server, identifier ->
                val symbols = server.textDocumentService.documentSymbol(DocumentSymbolParams(identifier)).await()
                    ?: return@request null

                symbols.map { it.right ?: return@request null }
            }
        }
    }

    private fun <Wire, T> request(
        file: VirtualFile,
        document: Document,
        offset: Int,
        verify: Boolean = false,
        supported: (LspClient) -> Boolean = { true },
        send: (LanguageServer, TextDocumentIdentifier, Position) -> CompletableFuture<Wire>,
        convert: (Wire?) -> T?,
    ): CompletableFuture<T?> {
        val stamp = document.modificationStamp
        return scope.future {
            val position = readAction {
                if (!file.isValid || stamp != document.modificationStamp || offset !in 0..document.textLength) {
                    return@readAction null
                }

                getLsp4jPosition(document, offset)
            } ?: return@future null

            if (verify) {
                return@future project.service<NativeVerification>().request(file, document, stamp) { server, identifier ->
                    convert(send(server, identifier, position).await())
                }
            }

            val client = runningSlangClient(project) ?: return@future null
            if (!client.descriptor.isSupportedFile(file) || !supported(client)) {
                return@future null
            }

            val identifier = client.getDocumentIdentifier(file)
            convert(client.sendRequest { send(it, identifier, position) })
        }
    }

    private fun location(uri: String?, range: Range?): Location? {
        val start = range?.start ?: return null
        val end = range.end ?: return null

        return uri?.let { Location(it, Range(start, end)) }
    }
}

internal data class HoverText(val text: String, val markdown: Boolean)
