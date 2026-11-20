package slanglsp.server.nativeclient

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.isDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import slanglsp.SlangBundle
import slanglsp.server.ServerConnection
import slanglsp.server.protocol.DocumentUris

/** Isolated LSP session: startup must not need the EDT, and style is fixed before initialization. */
@Service(Service.Level.PROJECT)
internal class NativeFormattingRequests(private val project: Project, private val scope: CoroutineScope) {
    fun format(
        file: VirtualFile,
        source: String,
        ranges: List<TextRange>,
        indentSize: Int,
        insertSpaces: Boolean,
        settings: Map<String?, Any?>,
    ): CompletableFuture<String?> = scope.future(Dispatchers.IO) {
        val executable = checkNotNull(ServerConnection.findExecutable(project)) { SlangBundle.message("server.not.found") }
        val path = checkNotNull(file.takeIf { it.isValid }) { "Slang file is unavailable" }.toNioPath()
        val lines = SourceLines(source)
        val root = project.basePath?.let(Path::of)?.takeIf { it.isDirectory() } ?: path.parent
        val identifier = TextDocumentIdentifier(DocumentUris.fileUri(file).toASCIIString())
        HeadlessSlangSession.start(executable, root, project.name, settings).use { session ->
            val textService = session.server.textDocumentService
            textService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(identifier.uri, "slang", 1, source)))

            val full = enabled(session.capabilities.documentFormattingProvider)
            val partial = enabled(session.capabilities.documentRangeFormattingProvider)
            val options = FormattingOptions(indentSize, insertSpaces)
            val edits = linkedSetOf<Edit>()

            for (range in ranges) {
                val selection = range.takeUnless { it.startOffset == 0 && it.endOffset == source.length }
                val result = if (selection != null && partial) {
                    val start = lines.position(selection.startOffset)
                    val end = lines.position(selection.endOffset)
                    val params = DocumentRangeFormattingParams(identifier, options, Range(start, end))
                    textService.rangeFormatting(params).await()
                } else {
                    check(full) { "Slang language server does not support formatting this range" }
                    val params = DocumentFormattingParams(identifier, options)
                    textService.formatting(params).await()
                }

                for (edit in result.orEmpty()) {
                    val start = lines.offset(edit.range.start)
                    val end = lines.offset(edit.range.end)
                    check(start <= end) { "Slang language server returned an invalid formatting edit" }
                    if (selection == null ||
                        start in selection.startOffset..<selection.endOffset && end <= selection.endOffset) {
                        edits.add(Edit(start, end, edit.newText))
                    }
                }
            }
            val formatted = applyEdits(source, edits)
            session.shutdown()
            formatted
        }
    }

    private fun applyEdits(source: String, edits: Set<Edit>): String {
        val formatted = StringBuilder(source)
        var nextStart = source.length
        for ((start, end, text) in edits.sortedWith(compareByDescending<Edit> { it.start }.thenByDescending { it.end })) {
            check(end <= nextStart) { "Slang language server returned overlapping formatting edits" }
            formatted.replace(start, end, text)
            nextStart = start
        }
        return formatted.toString()
    }

    private fun enabled(capability: Either<Boolean, *>?): Boolean {
        return capability != null && (capability.isRight || capability.left == true)
    }

    /** LSP and JVM string indices both count UTF-16 code units. No IDE read lock is needed. */
    private class SourceLines(private val source: String) {
        private val starts = buildList {
            add(0)
            source.forEachIndexed { index, char ->
                if (char == '\n') {
                    add(index + 1)
                }
            }
        }

        fun position(offset: Int): Position {
            val found = starts.binarySearch(offset)
            val line = if (found >= 0) found else -found - 2
            return Position(line, offset - starts[line])
        }

        fun offset(position: Position): Int {
            if (position.line < 0) {
                return 0
            }
            if (position.line >= starts.size) {
                return source.length
            }

            val start = starts[position.line]
            val end = starts.getOrNull(position.line + 1)?.minus(1) ?: source.length
            return start + position.character.coerceIn(0, end - start)
        }
    }

    private data class Edit(val start: Int, val end: Int, val text: String)
}
