package slanglsp.editor.typing

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import slanglsp.language.SlangLanguage

internal object EditorIndent {
    fun adjustLine(project: Project?, editor: Editor, line: Int) {
        val document = editor.document
        val start = document.getLineStartOffset(line)
        var end = start
        val text = document.immutableCharSequence
        while (end < document.getLineEndOffset(line) && (text[end] == ' ' || text[end] == '\t')) {
            end++
        }
        val indent = EditorIndentProvider().getLineIndent(project!!, editor, SlangLanguage.INSTANCE, end)
        // DO_NOT_ADJUST is an identity sentinel; an ordinary empty string means zero indentation.
        if (indent != null && indent !== LineIndentProvider.DO_NOT_ADJUST) {
            document.replaceString(start, end, indent)
        }
    }
}
