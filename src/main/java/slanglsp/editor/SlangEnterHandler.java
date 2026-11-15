package slanglsp.editor;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider;
import org.jetbrains.annotations.NotNull;
import slanglsp.language.SlangLanguage;
import slanglsp.language.SlangLexer;

/** Expands a populated inline body before the platform inserts the requested line break. */
public final class SlangEnterHandler extends EnterHandlerDelegateAdapter {
    @Override
    public Result postProcessEnter(@NotNull PsiFile file, @NotNull Editor editor, @NotNull DataContext context) {
        if (!file.getLanguage().isKindOf(SlangLanguage.INSTANCE)
                || !CodeInsightSettings.getInstance().SMART_INDENT_ON_ENTER) {
            return Result.Continue;
        }
        var document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        int nextLine = document.getLineNumber(offset) + 1;
        if (nextLine < document.getLineCount()) {
            int nextStart = document.getLineStartOffset(nextLine);
            int nextEnd = document.getLineEndOffset(nextLine);
            String nextText = document.getImmutableCharSequence().subSequence(nextStart, nextEnd).toString();
            if (nextText.stripLeading().startsWith(">")) {
                var generics = GenericResolver.context(editor);
                int closing = nextStart + nextText.length() - nextText.stripLeading().length();
                if (generics.isClosing(generics.tokenAt(closing))) {
                    EditorIndent.adjustLine(file.getProject(), editor, nextLine);
                }
            }
        }
        var token = editor.getHighlighter().createIterator(Math.max(0, offset - 1));
        if (!token.atEnd() && token.getTokenType() == SlangLexer.BLOCK_COMMENT) {
            int openingLine = document.getLineNumber(token.getStart());
            int closingLine = document.getLineNumber(token.getEnd() - 1);
            int currentLine = document.getLineNumber(offset);
            int start = document.getLineStartOffset(openingLine);
            String prefix = document.getText().substring(start, token.getStart());
            if (prefix.isBlank() && currentLine > openingLine) {
                indentCommentLine(editor, currentLine, prefix + " ");
                if (currentLine + 1 <= closingLine) {
                    indentCommentLine(editor, currentLine + 1, prefix + " ");
                }
            }
        }
        return Result.Continue;
    }

    private static void indentCommentLine(Editor editor, int line, String indent) {
        var document = editor.getDocument();
        int start = document.getLineStartOffset(line);
        int end = document.getLineEndOffset(line);
        var text = document.getImmutableCharSequence();
        int content = start;
        while (content < end && (text.charAt(content) == ' ' || text.charAt(content) == '\t')) {
            content++;
        }
        if (content < end && text.charAt(content) == '*') {
            document.replaceString(start, content, indent);
        }
    }

    @Override
    public Result preprocessEnter(
            @NotNull PsiFile file, @NotNull Editor editor, @NotNull Ref<Integer> caretOffset,
            @NotNull Ref<Integer> caretAdvance, @NotNull DataContext context,
            EditorActionHandler originalHandler) {
        if (!file.getLanguage().isKindOf(SlangLanguage.INSTANCE)
                || !CodeInsightSettings.getInstance().SMART_INDENT_ON_ENTER) {
            return Result.Continue;
        }

        var document = editor.getDocument();
        int offset = caretOffset.get();
        if (offset == 0) {
            return Result.Continue;
        }

        var text = document.getImmutableCharSequence();
        if (offset < text.length() && text.charAt(offset - 1) == '<' && text.charAt(offset) == '>') {
            var generics = GenericResolver.context(editor);
            if (generics.isOpening(generics.tokenAt(offset - 1))) {
                document.insertString(offset, "\n");
                editor.getCaretModel().moveToOffset(offset);
                return Result.Continue;
            }
        }
        var token = editor.getHighlighter().createIterator(offset - 1);
        if (token.getTokenType() == SlangLexer.COMMENT
                && text.subSequence(token.getStart(), token.getEnd()).toString().startsWith("///")) {
            int start = document.getLineStartOffset(document.getLineNumber(offset));
            String indent = text.subSequence(start, token.getStart()).toString();
            if (indent.isBlank()) {
                String continuation = "\n" + indent + "/// ";
                document.insertString(offset, continuation);
                editor.getCaretModel().moveToOffset(offset + continuation.length());
                return Result.Stop;
            }
        }
        if (token.getTokenType() == SlangLexer.STRING || token.getTokenType() == SlangLexer.COMMENT
                || token.getTokenType() == SlangLexer.BLOCK_COMMENT) {
            return Result.Continue;
        }

        int lineStart = document.getLineStartOffset(document.getLineNumber(offset));
        int depth = 0;
        while (!token.atEnd() && token.getStart() >= lineStart) {
            if (token.getTokenType() == SlangLexer.RBRACE) {
                depth++;
            } else if (token.getTokenType() == SlangLexer.LBRACE) {
                if (depth-- == 0) {
                    int bodyStart = token.getEnd();
                    int content = bodyStart;
                    while (content < offset && Character.isWhitespace(text.charAt(content))) {
                        content++;
                    }

                    boolean atBodyEnd = splitTrailingBrace(editor, token.getStart(), offset, lineStart, content < offset);
                    if (content == offset) {
                        document.deleteString(bodyStart, content);
                        caretOffset.set(bodyStart);
                        editor.getCaretModel().moveToOffset(bodyStart);
                        return Result.Continue;
                    }

                    document.replaceString(bodyStart, content, "\n");
                    int bodyOffset = bodyStart + 1;
                    String indent = new SlangLineIndentProvider().getLineIndent(
                            file.getProject(), editor, SlangLanguage.INSTANCE, bodyOffset);
                    // DO_NOT_ADJUST is an identity sentinel; an ordinary empty string means zero indentation.
                    //noinspection StringEquality
                    if (indent == null || indent == LineIndentProvider.DO_NOT_ADJUST) {
                        indent = "";
                    }
                    document.insertString(bodyOffset, indent);
                    int newOffset = offset + 1 + indent.length() - (content - bodyStart);
                    caretOffset.set(newOffset);
                    editor.getCaretModel().moveToOffset(newOffset);
                    return atBodyEnd ? Result.Stop : Result.Continue;
                }
            }
            token.retreat();
        }
        return Result.Continue;
    }

    private static boolean splitTrailingBrace(Editor editor, int opening, int caret, int lineStart, boolean populated) {
        var document = editor.getDocument();
        var text = document.getImmutableCharSequence();
        var token = editor.getHighlighter().createIterator(opening);
        int depth = 0;
        while (!token.atEnd() && document.getLineNumber(token.getStart()) == document.getLineNumber(opening)) {
            if (token.getTokenType() == SlangLexer.LBRACE) {
                depth++;
            } else if (token.getTokenType() == SlangLexer.RBRACE && --depth == 0) {
                int close = token.getStart();
                int end = close;
                while (end > caret && Character.isWhitespace(text.charAt(end - 1))) {
                    end--;
                }
                if (end > caret || (populated && end == caret)) {
                    int indentEnd = lineStart;
                    while (indentEnd < opening && Character.isWhitespace(text.charAt(indentEnd))) {
                        indentEnd++;
                    }
                    document.replaceString(end, close, "\n" + text.subSequence(lineStart, indentEnd));
                }
                return populated && end == caret;
            }
            token.advance();
        }
        return false;
    }
}
