package slanglsp.editor.typing

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.DefaultTypingActionsExtension
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actions.EditorActionUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import kotlin.Int
import kotlin.math.max
import slanglsp.language.SlangLanguage

/** Supplies local indentation to paste and closing-delimiter actions through the experimental typing extension. */
@Suppress("UnstableApiUsage")
class SmartTypingActions : DefaultTypingActionsExtension() {
    override fun startPaste(project: Project, editor: Editor) {
        editor.putUserData(PasteState.PASTING, true)
    }

    override fun endPaste(project: Project, editor: Editor) {
        editor.putUserData(PasteState.PASTING, null)
    }

    override fun format(
        project: Project,
        editor: Editor,
        mode: Int,
        start: Int,
        end: Int,
        anchorColumn: Int,
        indentBeforeReformat: Boolean,
        formatInjected: Boolean
    ) {
        // The platform downgrades paste to INDENT_BLOCK when no PSI formatting model exists.
        var mode = mode
        if (editor.getUserData(PasteState.PASTING) == true && mode != CodeInsightSettings.NO_REFORMAT) {
            mode = CodeInsightSettings.getInstance().REFORMAT_ON_PASTE
        }
        if (mode == CodeInsightSettings.INDENT_BLOCK) {
            val document = editor.document
            val first = document.getLineNumber(start)
            val last = document.getLineNumber(max(start, end - 1))
            val tabSize = max(1, CodeStyle.getIndentOptions(project, document).TAB_SIZE)
            val oldIndent = PasteState.indentWidth(editor, first, tabSize)
            EditorIndent.adjustLine(project, editor, first)
            val delta = PasteState.indentWidth(editor, first, tabSize) - oldIndent
            for (line in first + 1..last) {
                EditorActionUtil.indentLine(project, editor, line, delta)
            }
            return
        }
        super.format(project, editor, mode, start, end, anchorColumn, indentBeforeReformat, formatInjected)
    }

    override fun isSuitableContext(project: Project, editor: Editor): Boolean {
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        return file != null && file.language.isKindOf(SlangLanguage.INSTANCE)
    }

    override fun adjustLineIndent(project: Project, editor: Editor, offset: Int) {
        EditorIndent.adjustLine(project, editor, editor.document.getLineNumber(offset))
    }

    override fun adjustLineIndent(project: Project, editor: Editor, startOffset: Int, endOffset: Int) {
        val document = editor.document
        val first = document.getLineNumber(startOffset)
        val last = document.getLineNumber(endOffset)
        for (line in first..last) {
            EditorIndent.adjustLine(project, editor, line)
        }
    }

    private object PasteState {
        val PASTING = Key.create<Boolean?>("slang.editor.pasting")

        fun indentWidth(editor: Editor, line: Int, tabSize: Int): Int {
            val document = editor.document
            val text = document.immutableCharSequence
            var width = 0
            for (i in document.getLineStartOffset(line)..<document.getLineEndOffset(line)) {
                if (text[i] == ' ') {
                    width++
                } else if (text[i] == '\t') {
                    width = (width / tabSize + 1) * tabSize
                } else {
                    break
                }
            }
            return width
        }
    }
}
