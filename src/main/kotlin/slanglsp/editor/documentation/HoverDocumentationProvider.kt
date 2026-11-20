package slanglsp.editor.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.project.DumbService.Companion.isDumb
import com.intellij.psi.PsiElement
import java.time.Duration
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

        if (file.project.isDisposed || isDumb(file.project)) {
            return null
        }

        return AnalysisDeadline.open(Duration.ofMillis(500)).use {
            NativeHoverDocumentation.html(file, source.textOffset)
        }
    }
}
