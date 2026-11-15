package slanglsp.editor;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider;
import slanglsp.language.SlangLanguage;

final class EditorIndent {
    private EditorIndent() {
    }

    static void adjustLine(Project project, Editor editor, int line) {
        var document = editor.getDocument();
        int start = document.getLineStartOffset(line);
        int end = start;
        var text = document.getImmutableCharSequence();
        while (end < document.getLineEndOffset(line) && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
            end++;
        }
        String indent = new SlangLineIndentProvider().getLineIndent(project, editor, SlangLanguage.INSTANCE, end);
        // DO_NOT_ADJUST is an identity sentinel; an ordinary empty string means zero indentation.
        //noinspection StringEquality
        if (indent != null && indent != LineIndentProvider.DO_NOT_ADJUST) {
            document.replaceString(start, end, indent);
        }
    }
}
