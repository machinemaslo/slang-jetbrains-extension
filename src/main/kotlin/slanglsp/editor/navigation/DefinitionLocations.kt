package slanglsp.editor.navigation

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.usages.LocationData
import java.net.URI
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import slanglsp.language.SlangFileType
import slanglsp.server.BuiltinFiles.BuiltinFile
import slanglsp.server.protocol.DocumentUris

/** Some slangd versions report import-dependent columns for the same declaration. */
internal object DefinitionLocations {
    fun normalize(source: PsiFile, offset: Int, name: String?, locations: List<LocationData>): List<LocationData> {
        if (name == null || locations.isEmpty()) {
            return locations
        }

        val result: MutableList<LocationData> = ArrayList()

        for (data in locations) {
            val location = data.location()
            val target = runReadActionBlocking {
                val file = LSPIJUtils.findResourceFor(location.uri)
                if (file == null || file is BuiltinFile || file.fileType !== SlangFileType) {
                    return@runReadActionBlocking null
                }

                val psi = PsiManager.getInstance(source.project).findFile(file) ?: return@runReadActionBlocking null
                val range = location.range?.let { identifierRange(psi, name, it) }
                val declarations =
                    identifierRanges(psi, name, location.range).filter {
                        OverloadMatcher.isFunctionDeclaration(psi, it)
                    }
                Target(psi, DocumentUris.fileUri(file).toString(), range, declarations)
            }

            if (target == null) {
                if ("file".equals(URI.create(location.uri).scheme, ignoreCase = true)) {
                    // An unresolved local file cannot prove that this is another declaration.
                    return mutableListOf()
                }
                result.add(data)
                continue
            }

            var range = target.range

            if (target.declarations.size > 1) {
                // Even a wrong column can land exactly on another overload.
                range =
                    OverloadMatcher.resolve(
                        source, offset, target.file, name, target.declarations, data.languageServer())
            } else if (range == null && target.declarations.size == 1) {
                range = target.declarations.first()
            }

            if (range == null) {
                // A partial/ambiguous result cannot establish that a candidate is unrelated.
                return mutableListOf()
            }

            result.add(LocationData(Location(target.uri, range), data.languageServer()))
        }

        return result
    }

    @JvmStatic
    fun identifierRange(file: PsiFile, name: String, reported: Range): Range? {
        val candidates = identifierRanges(file, name, reported)

        for (candidate in candidates) {
            if (candidate.start == reported.start) {
                return candidate
            }
        }

        return candidates.singleOrNull()
    }

    private fun identifierRanges(file: PsiFile, name: String, reported: Range?): MutableList<Range> {
        val document = LSPIJUtils.getDocument(file)

        if (document == null || reported == null || reported.start == null) {
            return mutableListOf()
        }

        val line = reported.start.line

        if (line < 0 || line >= document.lineCount) {
            return mutableListOf()
        }

        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val text = document.immutableCharSequence
        val result: MutableList<Range> = ArrayList()

        var offset = start
        while (offset < end) {
            val leaf = file.findElementAt(offset) ?: return mutableListOf()

            val tokenStart = leaf.textRange.startOffset
            val tokenEnd = leaf.textRange.endOffset

            if (tokenStart >= start &&
                tokenEnd <= end &&
                isSlangIdentifier(leaf) &&
                name.contentEquals(text.subSequence(tokenStart, tokenEnd))) {
                result.add(Range(Position(line, tokenStart - start), Position(line, tokenEnd - start)))
            }

            offset = tokenEnd
        }

        return result
    }

    @JvmRecord
    private data class Target(val file: PsiFile, val uri: String?, val range: Range?, val declarations: List<Range>)
}
