package slanglsp.editor;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import slanglsp.language.SlangLanguage;
import slanglsp.language.SlangLexer;

public final class SlangTypedHandler extends TypedHandlerDelegate {
    @Override
    public @NotNull Result beforeSelectionRemoved(
            char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (c == '<' && file.getLanguage().isKindOf(SlangLanguage.INSTANCE)
                && AngleBrackets.surroundSelection(editor)) {
            return Result.STOP;
        }
        return Result.CONTINUE;
    }

    @Override
    public @NotNull Result beforeCharTyped(
            char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file,
            @NotNull FileType fileType) {
        if (file.getLanguage().isKindOf(SlangLanguage.INSTANCE) && AngleBrackets.beforeTyped(c, editor)) {
            return Result.STOP;
        }
        if (file.getLanguage().isKindOf(SlangLanguage.INSTANCE) && c == '}') {
            int offset = editor.getCaretModel().getOffset();
            if (offset < editor.getDocument().getTextLength()
                    && editor.getHighlighter().createIterator(offset).getTokenType() == SlangLexer.RBRACE) {
                reindentBlock(project, editor, offset);
            }
        }
        return Result.CONTINUE;
    }

    @Override
    public @NotNull Result charTyped(
            char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (!file.getLanguage().isKindOf(SlangLanguage.INSTANCE)) {
            return Result.CONTINUE;
        }
        int offset = editor.getCaretModel().getOffset();
        var document = editor.getDocument();
        if (c == '<') {
            AngleBrackets.afterTyped(editor);
        } else if (c == '}' || c == ')' || c == ']') {
            var token = editor.getHighlighter().createIterator(offset - 1);
            if (token.getTokenType() == SlangLexer.RBRACE) {
                reindentBlock(project, editor, offset - 1);
            } else if (token.getTokenType() == SlangLexer.RPAREN || token.getTokenType() == SlangLexer.RBRACKET) {
                int line = document.getLineNumber(offset - 1);
                if (document.getImmutableCharSequence().subSequence(
                        document.getLineStartOffset(line), offset - 1).toString().isBlank()) {
                    EditorIndent.adjustLine(project, editor, line);
                }
            }
        } else if (c == ':') {
            var token = editor.getHighlighter().createIterator(offset - 1);
            int line = document.getLineNumber(offset - 1);
            String prefix = document.getImmutableCharSequence().subSequence(
                    document.getLineStartOffset(line), offset).toString().stripLeading();
            if (token.getTokenType() == SlangLexer.OPERATOR
                    && (prefix.startsWith("case ") || prefix.startsWith("default:"))) {
                EditorIndent.adjustLine(project, editor, line);
            }
        } else if (c == '*' && offset >= 2 && CodeInsightSettings.getInstance().CLOSE_COMMENT_ON_ENTER) {
            var token = editor.getHighlighter().createIterator(offset - 1);
            if (token.getTokenType() == SlangLexer.BLOCK_COMMENT && token.getStart() == offset - 2
                    && !document.getText().substring(token.getStart(), token.getEnd()).endsWith("*/")) {
                document.insertString(offset, "*/");
            }
        }
        return Result.CONTINUE;
    }

    private static void reindentBlock(Project project, Editor editor, int close) {
        var document = editor.getDocument();
        int endLine = document.getLineNumber(close);
        int startLine = endLine;
        if (CodeInsightSettings.getInstance().REFORMAT_BLOCK_ON_RBRACE) {
            var token = editor.getHighlighter().createIterator(close);
            int depth = 0;
            while (!token.atEnd()) {
                if (token.getTokenType() == SlangLexer.RBRACE) {
                    depth++;
                } else if (token.getTokenType() == SlangLexer.LBRACE && --depth == 0) {
                    startLine = document.getLineNumber(token.getStart()) + 1;
                    break;
                }
                token.retreat();
            }
        }
        for (int line = startLine; line <= endLine; line++) {
            EditorIndent.adjustLine(project, editor, line);
        }
    }
}
