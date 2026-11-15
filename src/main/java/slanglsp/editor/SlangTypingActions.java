package slanglsp.editor;

import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.editorActions.DefaultTypingActionsExtension;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actions.EditorActionUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.annotations.NotNull;
import slanglsp.language.SlangLanguage;

/** Supplies local indentation to native paste and closing-delimiter actions. */
public final class SlangTypingActions extends DefaultTypingActionsExtension {
    private static final Key<Boolean> PASTING = Key.create("slang.editor.pasting");

    @Override
    public void startPaste(@NotNull Project project, @NotNull Editor editor) {
        editor.putUserData(PASTING, true);
    }

    @Override
    public void endPaste(@NotNull Project project, @NotNull Editor editor) {
        editor.putUserData(PASTING, null);
    }

    @Override
    public void format(
            @NotNull Project project, @NotNull Editor editor, int mode, int start, int end,
            int anchorColumn, boolean indentBeforeReformat, boolean formatInjected) {
        // The platform downgrades paste to INDENT_BLOCK when no PSI formatting model exists.
        if (Boolean.TRUE.equals(editor.getUserData(PASTING)) && mode != CodeInsightSettings.NO_REFORMAT) {
            mode = CodeInsightSettings.getInstance().REFORMAT_ON_PASTE;
        }
        if (mode == CodeInsightSettings.INDENT_BLOCK) {
            var document = editor.getDocument();
            int first = document.getLineNumber(start);
            int last = document.getLineNumber(Math.max(start, end - 1));
            int tabSize = Math.max(1, CodeStyle.getIndentOptions(project, document).TAB_SIZE);
            int oldIndent = indentWidth(editor, first, tabSize);
            EditorIndent.adjustLine(project, editor, first);
            int delta = indentWidth(editor, first, tabSize) - oldIndent;
            for (int line = first + 1; line <= last; line++) {
                EditorActionUtil.indentLine(project, editor, line, delta);
            }
            return;
        }
        super.format(project, editor, mode, start, end, anchorColumn, indentBeforeReformat, formatInjected);
    }

    private static int indentWidth(Editor editor, int line, int tabSize) {
        var document = editor.getDocument();
        var text = document.getImmutableCharSequence();
        int width = 0;
        for (int i = document.getLineStartOffset(line); i < document.getLineEndOffset(line); i++) {
            if (text.charAt(i) == ' ') {
                width++;
            } else if (text.charAt(i) == '\t') {
                width = (width / tabSize + 1) * tabSize;
            } else {
                break;
            }
        }
        return width;
    }

    @Override
    public boolean isSuitableContext(@NotNull Project project, @NotNull Editor editor) {
        var file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        return file != null && file.getLanguage().isKindOf(SlangLanguage.INSTANCE);
    }

    @Override
    protected void adjustLineIndent(@NotNull Project project, @NotNull Editor editor, int offset) {
        EditorIndent.adjustLine(project, editor, editor.getDocument().getLineNumber(offset));
    }

    @Override
    protected void adjustLineIndent(
            @NotNull Project project, @NotNull Editor editor, int startOffset, int endOffset) {
        var document = editor.getDocument();
        int first = document.getLineNumber(startOffset);
        int last = document.getLineNumber(endOffset);
        for (int line = first; line <= last; line++) {
            EditorIndent.adjustLine(project, editor, line);
        }
    }
}
