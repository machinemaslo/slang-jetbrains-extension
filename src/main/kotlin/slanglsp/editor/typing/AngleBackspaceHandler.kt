package slanglsp.editor.typing

import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import kotlin.Char
import slanglsp.language.SlangLanguage

class AngleBackspaceHandler : BackspaceHandlerDelegate() {
    override fun beforeCharDeleted(c: Char, file: PsiFile, editor: Editor) {
        editor.putUserData(
            DELETE_CLOSER,
            c == '<' && file.language.isKindOf(SlangLanguage.INSTANCE) && AngleBrackets.emptyPair(editor))
    }

    override fun charDeleted(c: Char, file: PsiFile, editor: Editor): Boolean {
        val paired = editor.getUserData(DELETE_CLOSER) == true
        editor.putUserData(DELETE_CLOSER, null)
        if (paired) {
            val offset = editor.caretModel.offset
            val document = editor.document
            if (offset < document.textLength && document.charsSequence[offset] == '>') {
                document.deleteString(offset, offset + 1)
                return true
            }
        }
        return false
    }
}

private val DELETE_CLOSER = Key.create<Boolean?>("slang.angles.deleteCloser")
