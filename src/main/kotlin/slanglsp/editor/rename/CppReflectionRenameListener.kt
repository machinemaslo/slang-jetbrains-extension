package slanglsp.editor.rename

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.listeners.RefactoringElementListenerProvider

/** Watches successful native C++ renames; the registration decides which editable inputs matter. */
class CppReflectionRenameListener : RefactoringElementListenerProvider {
    override fun getListener(element: PsiElement): RefactoringElementListener? {
        val named = element as? PsiNamedElement ?: return null
        if (named is PsiFile) {
            return null
        }
        val oldName = named.name ?: return null
        val file = named.containingFile?.virtualFile?.takeIf {
            it.isInLocalFileSystem && it.extension?.lowercase() in cppExtensions
        } ?: return null
        return object : RefactoringElementListener {
            override fun elementMoved(newElement: PsiElement) {}

            override fun elementRenamed(newElement: PsiElement) {
                val result = newElement as? PsiNamedElement ?: return
                val newName = result.name ?: return
                if (newName == oldName || result.containingFile?.virtualFile != file) {
                    return
                }
                val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return
                result.project.service<ReflectionRenameRegeneration>().renamed(listOf(document))
            }
        }
    }
}

private val cppExtensions = setOf("c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx", "inc", "inl", "ixx", "cppm")
