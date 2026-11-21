package slanglsp.editor.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import java.time.Duration
import java.util.concurrent.CompletionException
import slanglsp.analysis.AnalysisDeadline
import slanglsp.language.SlangLanguage
import slanglsp.language.SlangLexer

/** Slang-only declaration/usage navigation, leaving other languages to their own handlers. */
class SymbolDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val file = source?.containingFile ?: return null
        if (file.language !== SlangLanguage.INSTANCE) {
            return null
        }

        val type = source.node?.elementType
        if (type !== SlangLexer.MODULE_NAME &&
            type !== SlangLexer.BUILTIN_TYPE &&
            type !== SlangLexer.TYPE &&
            type !== SlangLexer.FUNCTION &&
            type !== SlangLexer.VARIABLE) {
            return null
        }

        // IntelliJ calls this same provider for every Ctrl/Cmd-mouse move. Give it a
        // local target now; only activating the link requires the language server.
        if (editor != null && editor.project == source.project && source.isPhysical &&
            file.virtualFile?.isInLocalFileSystem == true) {
            return arrayOf(DeferredNavigationTarget(source, offset, editor, this))
        }

        return resolveTargets(source, offset, editor)
    }

    internal fun resolveTargets(source: PsiElement, offset: Int, editor: Editor?): Array<PsiElement> {
        val site = runReadActionBlocking {
            if (!source.isValid) {
                return@runReadActionBlocking null
            }

            val type = source.node?.elementType
            // slangd resolves a dotted import at its final component.
            type to if (type === SlangLexer.MODULE_NAME) source.textRange.endOffset - 1 else offset
        } ?: return PsiElement.EMPTY_ARRAY

        val (type, position) = site
        val targets = try {
            AnalysisDeadline.open(Duration.ofMillis(500)).use {
                definitions(source, position)
            }
        } catch (unavailable: CompletionException) {
            Logger.getInstance(javaClass).debug("Definition unavailable", unavailable)
            return PsiElement.EMPTY_ARRAY
        }

        return runReadActionBlocking {
            when {
                !source.isValid -> PsiElement.EMPTY_ARRAY
                type === SlangLexer.MODULE_NAME -> targets.mapNotNull { it.containingFile }.distinct().toTypedArray()
                targets.any { !isSource(it, source, offset) } -> targets
                type === SlangLexer.BUILTIN_TYPE -> PsiElement.EMPTY_ARRAY
                else -> arrayOf(UsageNavigationTarget(source, editor))
            }
        }
    }

    fun definitions(source: PsiElement, offset: Int): Array<PsiElement> {
        val (file, document) = runReadActionBlocking {
            val file = source.containingFile ?: return@runReadActionBlocking null
            val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
                ?: return@runReadActionBlocking null
            file to document
        } ?: return PsiElement.EMPTY_ARRAY

        return UsageSearch.definitions(file, document, offset)
            .mapNotNull { NavigationTargets.toPsiElement(it, file.project) }
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
