package slanglsp.editor.typing

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.TabOutScopesTracker
import com.intellij.openapi.editor.Editor

/** Angle brackets are contextual operators in the lexer, unlike (), [] and {}. */
internal object AngleBrackets {
    fun surroundSelection(editor: Editor): Boolean {
        val settings = CodeInsightSettings.getInstance()
        val selection = editor.selectionModel
        if (!selection.hasSelection() ||
            !settings.SURROUND_SELECTION_ON_QUOTE_TYPED ||
            !settings.AUTOINSERT_PAIR_BRACKET) {
            return false
        }
        val start = selection.selectionStart
        val end = selection.selectionEnd
        val document = editor.document
        val text = document.text
        val surrounded = text.substring(0, start) + "<" + text.substring(start, end) + ">" + text.substring(end)
        val context = GenericResolver.context(editor, surrounded)
        if (!context.isOpening(context.tokenAt(start))) {
            return false
        }
        document.insertString(end, ">")
        document.insertString(start, "<")
        selection.setSelection(start + 1, end + 1)
        return true
    }

    fun beforeTyped(c: Char, editor: Editor): Boolean {
        if (!CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET) {
            return false
        }
        val document = editor.document
        val text = document.immutableCharSequence
        val offset = editor.caretModel.offset
        if (offset >= text.length || text[offset] != '>') {
            return false
        }
        if (c != '>' && c != '<' && c != '=') {
            return false
        }
        val context = GenericResolver.context(editor)
        if (c == '>' && context.isClosing(context.tokenAt(offset))) {
            editor.caretModel.moveToOffset(offset + 1)
            val line = document.getLineNumber(offset)
            if (editor.project != null &&
                text.subSequence(document.getLineStartOffset(line), offset).toString().isBlank()) {
                EditorIndent.adjustLine(editor.project, editor, line)
            }
            return true
        }
        if ((c == '<' || c == '=') &&
            offset > 0 &&
            text[offset - 1] == '<' &&
            context.isOpening(context.tokenAt(offset - 1))) {
            // Recover naturally if the user intended << or <= instead.
            document.deleteString(offset, offset + 1)
        }
        return false
    }

    fun afterTyped(editor: Editor) {
        if (!CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET) {
            return
        }
        val document = editor.document
        val text = document.immutableCharSequence
        val offset = editor.caretModel.offset
        if (offset == 0 || text[offset - 1] != '<') {
            return
        }
        if (offset < text.length && !Character.isWhitespace(text[offset]) && ">)]};,({[".indexOf(text[offset]) < 0) {
            return
        }
        val context = GenericResolver.context(editor)
        val opening = context.tokenAt(offset - 1)
        if (!context.isOpening(opening)) {
            return
        }
        if (context.needsClosing(opening)) {
            document.insertString(offset, ">")
        }
        if (offset < document.textLength && document.charsSequence[offset] == '>') {
            TabOutScopesTracker.getInstance().registerEmptyScope(editor, offset)
        }
    }

    fun emptyPair(editor: Editor): Boolean {
        if (!CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET) {
            return false
        }
        val text = editor.document.immutableCharSequence
        val offset = editor.caretModel.offset
        if (offset == 0 || offset >= text.length || text[offset - 1] != '<' || text[offset] != '>') {
            return false
        }
        val context = GenericResolver.context(editor)
        return context.isOpening(context.tokenAt(offset - 1))
    }
}
