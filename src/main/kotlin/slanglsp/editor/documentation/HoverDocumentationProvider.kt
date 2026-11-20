package slanglsp.editor.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService.Companion.isDumb
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.features.documentation.LSPDocumentationHelper
import java.time.Duration
import java.util.concurrent.CompletionException
import slanglsp.analysis.AnalysisDeadline
import slanglsp.language.SlangLanguage

/** Connects Ctrl+hover on Slang PSI to the same LSP hover used by Quick Documentation. */
class HoverDocumentationProvider : AbstractDocumentationProvider() {
    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        return documentation(element, originalElement)
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        return documentation(element, originalElement)
    }

    private fun documentation(element: PsiElement?, originalElement: PsiElement?): String? {
        // Ask at the usage site, not the resolved declaration: overloads and generic
        // substitutions can have different documentation at different call sites.
        val source = originalElement ?: element ?: return null
        if (!source.isValid) {
            return null
        }
        val file = source.containingFile ?: return null
        if (!file.language.isKindOf(SlangLanguage.INSTANCE)) {
            return null
        }

        return hoverDocumentation(file, source.textOffset)
            .filterNotNull()
            .filter { it.isNotBlank() }
            .joinToString("<hr />")
            .takeIf { it.isNotEmpty() }
    }

    // The scope bounds hover latency.
    private fun hoverDocumentation(file: PsiFile, offset: Int): MutableList<String?> {
        if (file.project.isDisposed || isDumb(file.project)) {
            return mutableListOf()
        }

        // Legacy Ctrl-hover callbacks can hold a platform read lock. They must yield
        // promptly; the platform's normal LSP documentation target remains available.
        try {
            AnalysisDeadline.open(Duration.ofMillis(500)).use {
                return documentationAt(file, offset)
            }
        } catch (unavailable: CompletionException) {
            Logger.getInstance(javaClass).debug("Hover unavailable", unavailable)
            return mutableListOf()
        }
    }

    private fun documentationAt(file: PsiFile, offset: Int): MutableList<String?> {
        val position =
            runReadActionBlocking {
                val document = LSPIJUtils.getDocument(file)
                if (document == null || file.virtualFile == null || offset !in 0..document.textLength) {
                    null
                } else {
                    LSPIJUtils.toPosition(offset, document)
                }
            } ?: return mutableListOf()

        val requests = HoverRequests(file)
        val result = mutableListOf<String?>()
        for (server in requests.connectedServers()) {
            val hover = requests.requestHover(server, position) ?: continue
            result.add(
                LSPDocumentationHelper.convertToHtml(
                    LSPDocumentationHelper.getValidMarkupContents(hover), server, file))
        }
        return result
    }
}
