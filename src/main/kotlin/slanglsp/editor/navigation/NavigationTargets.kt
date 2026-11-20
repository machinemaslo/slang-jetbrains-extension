package slanglsp.editor.navigation

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import slanglsp.server.BuiltinFiles
import slanglsp.server.protocol.DocumentUris

internal object NavigationTargets {
    fun toPsiElement(location: Location, project: Project): PsiElement? {
        if (project.isDisposed) {
            return null
        }

        val file = if (BuiltinFiles.moduleName(location.uri) != null) {
            project.service<BuiltinFiles>().resolve(location.uri)
        } else {
            DocumentUris.findLocalFile(location.uri)
        } ?: return null

        return runReadActionBlocking {
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@runReadActionBlocking null
            val document = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return@runReadActionBlocking null
            val range = location.range ?: return@runReadActionBlocking null

            fun offset(position: Position): Int? {
                if (position.line !in 0..<document.lineCount || position.character < 0) {
                    return null
                }

                val start = document.getLineStartOffset(position.line)
                val end = document.getLineEndOffset(position.line)
                return start + position.character.coerceAtMost(end - start)
            }

            val start = range.start?.let(::offset) ?: return@runReadActionBlocking null
            val end = range.end?.let(::offset) ?: return@runReadActionBlocking null
            if (end < start) {
                return@runReadActionBlocking null
            }

            NavigationPsiElement(psi, TextRange(start, end))
        }
    }
}
