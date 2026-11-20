package slanglsp.editor.documentation

import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.util.concurrent.CompletionException
import org.eclipse.lsp4j.MarkupKind
import slanglsp.analysis.AnalysisDeadline
import slanglsp.language.SlangLanguage
import slanglsp.server.nativeclient.NativeNavigationRequests

internal object NativeHoverDocumentation {
    private val logger = Logger.getInstance(NativeHoverDocumentation::class.java)

    fun html(file: PsiFile, offset: Int): String? {
        val project = file.project
        if (project.isDisposed) {
            return null
        }

        val virtualFile = file.virtualFile ?: return null
        val document = runReadActionBlocking {
            if (file.isValid) PsiDocumentManager.getInstance(project).getDocument(file) else null
        } ?: return null
        val stamp = document.modificationStamp
        val request = project.service<NativeNavigationRequests>().hover(virtualFile, document, offset)

        return try {
            val markup = AnalysisDeadline.await(request) ?: return null
            if (document.modificationStamp != stamp) {
                return null
            }

            val text = markup.value?.takeIf { it.isNotBlank() } ?: return null
            val html = if (markup.kind == MarkupKind.MARKDOWN) {
                val markdown = BuiltinHoverDocumentation.enrich(project, text)
                runReadActionBlocking {
                    DocMarkdownToHtmlConverter.convert(project, markdown, SlangLanguage.INSTANCE)
                }
            } else {
                "<pre>${StringUtil.escapeXmlEntities(text)}</pre>"
            }
            html.takeIf { it.isNotBlank() }
        } catch (unavailable: CompletionException) {
            logger.debug("Native hover unavailable", unavailable)
            null
        } finally {
            if (!request.isDone) {
                request.cancel(true)
            }
        }
    }
}
