package slanglsp.editor.documentation

import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.features.documentation.LSPHoverSupport
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import slanglsp.analysis.AnalysisDeadline

/** Connects the document through LSP4IJ so hover requests include unsaved edits. */
internal class HoverRequests(file: PsiFile) : LSPHoverSupport(file) {
    // Discover servers through LSP4IJ's own hover feature checks before sending requests.
    @Suppress("UnstableApiUsage")
    fun connectedServers(): List<LanguageServerItem> {
        val file = getFile()
        val pending =
            getLanguageServers(file, { it.hoverFeature.isEnabled(file) }, { it.hoverFeature.isSupported(file) })
        return AnalysisDeadline.await(pending).orEmpty()
    }

    fun requestHover(server: LanguageServerItem, position: Position): Hover? {
        val identifier = TextDocumentIdentifier()
        updateTextDocumentUri(identifier, file, server)
        val request = server.textDocumentService.hover(HoverParams(identifier, position))

        try {
            return AnalysisDeadline.await(request)
        } finally {
            if (!request.isDone) {
                request.cancel(true)
            }
        }
    }
}
