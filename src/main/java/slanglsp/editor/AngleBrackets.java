package slanglsp.editor;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.editorActions.TabOutScopesTracker;
import com.intellij.openapi.editor.Editor;

/** Angle brackets are contextual operators in the lexer, unlike (), [] and {}. */
final class AngleBrackets {
    private AngleBrackets() {
    }

    static boolean surroundSelection(Editor editor) {
        var settings = CodeInsightSettings.getInstance();
        var selection = editor.getSelectionModel();
        if (!selection.hasSelection() || !settings.SURROUND_SELECTION_ON_QUOTE_TYPED
                || !settings.AUTOINSERT_PAIR_BRACKET) {
            return false;
        }
        int start = selection.getSelectionStart();
        int end = selection.getSelectionEnd();
        var document = editor.getDocument();
        String text = document.getText();
        String surrounded = text.substring(0, start) + "<" + text.substring(start, end) + ">" + text.substring(end);
        var context = GenericResolver.context(editor, surrounded);
        if (!context.isOpening(context.tokenAt(start))) {
            return false;
        }
        document.insertString(end, ">");
        document.insertString(start, "<");
        selection.setSelection(start + 1, end + 1);
        return true;
    }

    static boolean beforeTyped(char c, Editor editor) {
        if (!CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET) {
            return false;
        }
        var document = editor.getDocument();
        var text = document.getImmutableCharSequence();
        int offset = editor.getCaretModel().getOffset();
        if (offset >= text.length() || text.charAt(offset) != '>') {
            return false;
        }
        if (c != '>' && c != '<' && c != '=') {
            return false;
        }
        var context = GenericResolver.context(editor);
        if (c == '>' && context.isClosing(context.tokenAt(offset))) {
            editor.getCaretModel().moveToOffset(offset + 1);
            int line = document.getLineNumber(offset);
            if (editor.getProject() != null
                    && text.subSequence(document.getLineStartOffset(line), offset).toString().isBlank()) {
                EditorIndent.adjustLine(editor.getProject(), editor, line);
            }
            return true;
        }
        if ((c == '<' || c == '=') && offset > 0 && text.charAt(offset - 1) == '<'
                && context.isOpening(context.tokenAt(offset - 1))) {
            // Recover naturally if the user intended << or <= instead.
            document.deleteString(offset, offset + 1);
        }
        return false;
    }

    static void afterTyped(Editor editor) {
        if (!CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET) {
            return;
        }
        var document = editor.getDocument();
        var text = document.getImmutableCharSequence();
        int offset = editor.getCaretModel().getOffset();
        if (offset == 0 || text.charAt(offset - 1) != '<') {
            return;
        }
        if (offset < text.length() && !Character.isWhitespace(text.charAt(offset))
                && ">)]};,({[".indexOf(text.charAt(offset)) < 0) {
            return;
        }
        var context = GenericResolver.context(editor);
        int opening = context.tokenAt(offset - 1);
        if (!context.isOpening(opening)) {
            return;
        }
        if (context.needsClosing(opening)) {
            document.insertString(offset, ">");
        }
        if (offset < document.getTextLength() && document.getCharsSequence().charAt(offset) == '>') {
            TabOutScopesTracker.getInstance().registerEmptyScope(editor, offset);
        }
    }

    static boolean emptyPair(Editor editor) {
        if (!CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET) {
            return false;
        }
        var text = editor.getDocument().getImmutableCharSequence();
        int offset = editor.getCaretModel().getOffset();
        if (offset == 0 || offset >= text.length() || text.charAt(offset - 1) != '<' || text.charAt(offset) != '>') {
            return false;
        }
        var context = GenericResolver.context(editor);
        return context.isOpening(context.tokenAt(offset - 1));
    }
}
