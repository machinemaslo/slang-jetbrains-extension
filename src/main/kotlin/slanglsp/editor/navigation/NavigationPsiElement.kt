package slanglsp.editor.navigation

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement

internal open class NavigationPsiElement(
    private val file: PsiFile,
    private val range: TextRange,
) : FakePsiElement() {
    override fun getParent(): PsiFile = file

    override fun getContainingFile(): PsiFile = file

    override fun getTextRange(): TextRange = range

    override fun getTextOffset(): Int = range.startOffset

    override fun getTextLength(): Int = range.length

    override fun getName(): String = text

    override fun getText(): String {
        val content = file.text
        return if (range.endOffset <= content.length) range.substring(content) else ""
    }

    override fun isValid(): Boolean = file.isValid

    override fun canNavigate(): Boolean = file.isValid && file.virtualFile?.isValid == true

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        val virtualFile = file.virtualFile ?: return
        if (canNavigate()) {
            OpenFileDescriptor(file.project, virtualFile, range.startOffset).navigate(requestFocus)
        }
    }
}
