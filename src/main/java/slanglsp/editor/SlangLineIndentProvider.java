package slanglsp.editor;

import com.intellij.application.options.CodeStyle;
import com.intellij.lang.Language;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.TokenType;
import com.intellij.psi.codeStyle.joinLines.JoinedLinesSpacingCalculator;
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import slanglsp.language.SlangLanguage;
import slanglsp.language.SlangLexer;

import java.util.ArrayDeque;

/** Local editor indentation; document formatting remains owned by clang-format. */
public final class SlangLineIndentProvider implements LineIndentProvider, JoinedLinesSpacingCalculator {
    @Override
    public boolean isSuitableFor(@Nullable Language language) {
        return language != null && language.isKindOf(SlangLanguage.INSTANCE);
    }

    @Override
    @Nullable
    public String getLineIndent(
            @NotNull Project project, @NotNull Editor editor, @Nullable Language language, int offset) {
        if (!isSuitableFor(language)) {
            return null;
        }

        Document document = editor.getDocument();
        CharSequence text = document.getImmutableCharSequence();
        int line = document.getLineNumber(offset);
        int start = document.getLineStartOffset(line);
        int next = skipSpaces(text, offset);
        boolean memberAccess = false;

        if (next < document.getTextLength()) {
            var token = editor.getHighlighter().createIterator(next);

            if (protectedToken(token.getTokenType())) {
                return DO_NOT_ADJUST;
            }

            memberAccess = token.getTokenType() == SlangLexer.DOT && token.getEnd() - token.getStart() == 1;
        }

        // Do not join code into a line comment or preprocessor directive.
        if (line > 0) {
            int previousStart = document.getLineStartOffset(line - 1);
            int previousEnd = document.getLineEndOffset(line - 1);

            for (var token = editor.getHighlighter().createIterator(previousStart);
                    !token.atEnd() && token.getStart() < previousEnd;
                    token.advance()) {
                if (protectedToken(token.getTokenType()) && token.getTokenType() != SlangLexer.STRING) {
                    return DO_NOT_ADJUST;
                }
            }

            if (previousEnd > previousStart && text.charAt(previousEnd - 1) == '\\') {
                return DO_NOT_ADJUST;
            }
        }

        var options = CodeStyle.getIndentOptions(project, document);
        int tabSize = Math.max(1, options.TAB_SIZE);
        int columns = 0;
        char nextChar = next < text.length() ? text.charAt(next) : '\0';
        char previousChar = '\0';
        boolean inContinuation = false;
        var closers = new ArrayDeque<Character>();
        HighlighterIterator token = editor.getHighlighter().createIterator(Math.max(0, start - 1));

        while (start > 0 && !token.atEnd()) {
            ProgressManager.checkCanceled();
            IElementType type = token.getTokenType();

            if (previousChar == '\0' && type != TokenType.WHITE_SPACE) {
                previousChar = text.charAt(token.getEnd() - 1);
            }

            if (type == SlangLexer.DIRECTIVE) {
                return DO_NOT_ADJUST;
            }

            if (type == SlangLexer.BRACES || type == SlangLexer.PARENTHESES || type == SlangLexer.BRACKETS) {
                char c = text.charAt(token.getStart());

                if (c == '}' || c == ')' || c == ']') {
                    closers.push(c);
                } else if (!closers.isEmpty()) {
                    if (closing(c) != closers.pop()) {
                        return DO_NOT_ADJUST;
                    }
                } else {
                    inContinuation = c != '{';
                    columns = indentWidth(document, text, token.getStart(), tabSize);

                    if (nextChar != closing(c)) {
                        columns += c == '{' ? options.INDENT_SIZE : options.CONTINUATION_INDENT_SIZE;
                    }

                    break;
                }
            }

            token.retreat();
        }

        if (!closers.isEmpty()) {
            return DO_NOT_ADJUST;
        }

        // Without a statement parser, leave unbraced control flow and other
        // incomplete expressions to the existing editor fallback.
        if (!inContinuation && !memberAccess && nextChar != '{' && nextChar != '}'
                && previousChar != '\0' && previousChar != ';' && previousChar != '{' && previousChar != '}') {
            return DO_NOT_ADJUST;
        }

        if (memberAccess && !inContinuation) {
            columns += options.CONTINUATION_INDENT_SIZE;
        }

        columns = Math.max(0, columns);

        return options.USE_TAB_CHARACTER
                ? "\t".repeat(columns / tabSize) + " ".repeat(columns % tabSize)
                : " ".repeat(columns);
    }

    @Override
    public int getJoinedLinesSpacing(
            @NotNull Project project, @NotNull Editor editor, @Nullable Language language, int offset) {
        if (!isSuitableFor(language)) {
            return -1;
        }

        CharSequence text = editor.getDocument().getImmutableCharSequence();
        int previous = offset - 1;

        while (previous >= 0 && Character.isWhitespace(text.charAt(previous))) {
            previous--;
        }

        if (previous < 0 || offset >= text.length()) {
            return 0;
        }

        char left = text.charAt(previous);
        char right = text.charAt(offset);

        if (right == '.' || right == ',' || right == ';' || right == ')' || right == ']'
                || left == '(' || left == '[' || left == '.') {
            return 0;
        }

        return 1;
    }

    private static boolean protectedToken(IElementType type) {
        return type == SlangLexer.COMMENT || type == SlangLexer.BLOCK_COMMENT
                || type == SlangLexer.STRING || type == SlangLexer.DIRECTIVE;
    }

    private static char closing(char opener) {
        return switch (opener) {
            case '{' -> '}';
            case '(' -> ')';
            case '[' -> ']';
            default -> '\0';
        };
    }

    private static int skipSpaces(CharSequence text, int offset) {
        while (offset < text.length() && (text.charAt(offset) == ' ' || text.charAt(offset) == '\t')) {
            offset++;
        }

        return offset;
    }

    private static int indentWidth(Document document, CharSequence text, int offset, int tabSize) {
        int start = document.getLineStartOffset(document.getLineNumber(offset));
        int end = skipSpaces(text, start);
        int width = 0;

        for (int i = start; i < end; i++) {
            width = text.charAt(i) == '\t' ? (width / tabSize + 1) * tabSize : width + 1;
        }

        return width;
    }
}
