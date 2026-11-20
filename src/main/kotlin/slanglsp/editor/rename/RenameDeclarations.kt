package slanglsp.editor.rename

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.features.documentSymbol.DocumentSymbolData
import kotlin.math.min
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import slanglsp.analysis.AnalysisDeadline
import slanglsp.analysis.DocumentSymbolRequests
import slanglsp.editor.inspections.naming.NamingAnnotator
import slanglsp.editor.inspections.naming.NamingParameters
import slanglsp.editor.inspections.unused.UnusedDeclarations

/** Declaration-site definition requests are optional in slangd; document symbols are independent evidence. */
internal object RenameDeclarations {
    fun contains(file: PsiFile, document: Document, text: String, name: String, offset: Int, check: Runnable): Boolean {
        val future = runReadActionBlocking {
            check.run()
            DocumentSymbolRequests.get(file, document, document.modificationStamp, false)
        }
        val symbols = AnalysisDeadline.await<MutableList<DocumentSymbolData?>?>(future)
        return runReadActionBlocking {
            check.run()
            symbols.orEmpty().any {
                contains(it?.documentSymbol, document, text, name, offset)
            }
        }
    }

    private fun contains(
        symbol: DocumentSymbol?,
        document: Document,
        text: String,
        name: String,
        offset: Int
    ): Boolean {
        if (symbol?.selectionRange == null || symbol.range == null) {
            return false
        }
        val start = LSPIJUtils.toOffset(symbol.selectionRange.start, document)
        val end = start + symbol.name.length
        if (start < 0 || end > text.length || (text.substring(start, end) != symbol.name)) {
            return false
        }
        if (start == offset && name == symbol.name) {
            return true
        }
        val bodyEnd = min(text.length, LSPIJUtils.toOffset(symbol.range.end, document))
        if (bodyEnd > end) {
            if (symbol.kind == SymbolKind.Function || symbol.kind == SymbolKind.Method) {
                if (UnusedDeclarations.locals(text, end, bodyEnd).any { local ->
                    local.range.startOffset == offset && local.name == name
                }) {
                    return true
                }
            }
            if (symbol.kind == SymbolKind.Function ||
                symbol.kind == SymbolKind.Method ||
                symbol.kind == SymbolKind.Struct ||
                symbol.kind == SymbolKind.Class ||
                symbol.kind == SymbolKind.Interface) {
                val parameters = LinkedHashMap<Int?, NamingAnnotator.Candidate?>()
                NamingParameters.collect(text, end, bodyEnd, parameters)
                val parameter = parameters[offset]
                if (parameter != null && parameter.name == name) {
                    return true
                }
            }
        }
        return symbol.children.orEmpty().any { contains(it, document, text, name, offset) }
    }
}
