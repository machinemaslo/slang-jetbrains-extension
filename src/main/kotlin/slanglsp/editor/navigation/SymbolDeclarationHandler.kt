package slanglsp.editor.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.redhat.devtools.lsp4ij.LSPIJUtils
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletionException
import slanglsp.analysis.AnalysisDeadline
import slanglsp.language.SlangLanguage
import slanglsp.language.SlangLexer
import slanglsp.server.client.ClientFeatureAccess

/** Slang-only declaration/usage navigation, leaving other languages to their own handlers. */
class SymbolDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        if (source?.containingFile == null || source.containingFile.language !== SlangLanguage.INSTANCE) {
            return null
        }
        val type = if (source.node == null) null else source.node.elementType
        val module = type === SlangLexer.MODULE_NAME
        val builtinType = type === SlangLexer.BUILTIN_TYPE

        if (!module &&
            !builtinType &&
            type !== SlangLexer.TYPE &&
            type !== SlangLexer.FUNCTION &&
            type !== SlangLexer.VARIABLE) {
            return null
        }
        // slangd resolves a dotted import at its final component.
        val position = if (module) source.textRange.endOffset - 1 else offset
        val targets: Array<PsiElement>
        // The legacy navigation callback can run under a platform-owned read lock.
        try {
            AnalysisDeadline.open(Duration.ofMillis(500)).use {
                targets = definitions(source, position)
            }
        } catch (unavailable: CompletionException) {
            Logger.getInstance(javaClass).debug("Definition unavailable", unavailable)
            return PsiElement.EMPTY_ARRAY
        }

        if (module) {
            return targets.mapNotNull { it.containingFile }.distinct().toTypedArray<PsiElement>()
        }

        if (targets.isNotEmpty() &&
            Arrays.stream<PsiElement?>(targets).anyMatch { target: PsiElement? ->
                !isSource(target!!, source, offset)
            }) {
            return targets
        }

        // This method also runs on Ctrl-hover: never launch a project search here.
        if (builtinType) {
            return PsiElement.EMPTY_ARRAY
        }

        return arrayOf(UsageNavigationTarget(source, editor))
    }

    // PSI conversion uses LSP4IJ's experimental client-feature API.
    fun definitions(source: PsiElement, offset: Int): Array<PsiElement> {
        val file = source.containingFile
        val document = LSPIJUtils.getDocument(file) ?: return PsiElement.EMPTY_ARRAY

        return UsageSearch.definitions(file, document, offset)
            .mapNotNull { ClientFeatureAccess.toPsiElement(it, file.project) }
            .toTypedArray<PsiElement>()
    }
}

private fun isSource(target: PsiElement, source: PsiElement, offset: Int): Boolean {
    val targetFile = target.containingFile
    val sourceFile = source.containingFile
    val sameFile =
        targetFile === sourceFile ||
            (targetFile != null &&
                sourceFile != null &&
                targetFile.virtualFile != null &&
                targetFile.virtualFile == sourceFile.virtualFile)

    return sameFile && target.textRange?.containsOffset(offset) == true
}
