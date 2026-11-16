package slanglsp.editor;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.TokenType;
import slanglsp.language.SlangLexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Finds a complete declaration without crossing siblings or dropping initializer side effects. */
final class UnusedRemoval {
    private record Token(String text, int start, int end) {}

    static TextRange range(String text, UnusedSymbolsAnnotator.Candidate candidate) {
        var lexer = new SlangLexer();
        lexer.start(text);
        List<Token> tokens = new ArrayList<>();
        int nameIndex = -1;
        while (lexer.getTokenType() != null) {
            var type = lexer.getTokenType();
            if (type != TokenType.WHITE_SPACE && type != SlangLexer.COMMENT && type != SlangLexer.BLOCK_COMMENT) {
                if (lexer.getTokenStart() == candidate.range().getStartOffset()) {
                    nameIndex = tokens.size();
                }
                tokens.add(new Token(text.substring(lexer.getTokenStart(), lexer.getTokenEnd()),
                        lexer.getTokenStart(), lexer.getTokenEnd()));
            }
            lexer.advance();
        }
        if (nameIndex < 0 || !tokens.get(nameIndex).text().equals(candidate.name())) {
            return null;
        }
        int first = nameIndex;
        while (first > 0 && !Set.of(";", "{", "}").contains(tokens.get(first - 1).text())) {
            first--;
        }
        boolean bodyDeclaration = Set.of("function", "method", "struct", "class", "enum").contains(candidate.kind());
        String prefix = text.substring(tokens.get(first).start(), candidate.range().getStartOffset());
        if (prefix.contains("#") || prefix.contains("=") || prefix.contains(",")
                || !bodyDeclaration && (prefix.contains("(") || prefix.contains(")"))) {
            return null;
        }
        int parens = 0;
        int brackets = 0;
        int braces = 0;
        boolean body = false;
        boolean initializer = false;
        for (int i = nameIndex + 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            String word = token.text();
            if (word.equals("#")) {
                return null;
            }
            if (word.equals(";") && parens == 0 && brackets == 0 && braces == 0) {
                return expandLine(text, tokens.get(first).start(), token.end());
            }
            if (!bodyDeclaration && word.equals(",") && parens == 0 && brackets == 0 && braces == 0) {
                return null; // Removing one declarator must not remove its siblings.
            }
            if (!bodyDeclaration && word.equals("=")) {
                initializer = true;
                continue;
            }
            if (initializer && !word.matches("[0-9][A-Za-z0-9_.]*|true|false|[+\\-]")) {
                return null; // Calls, reads, and overloaded expressions need a preserve-side-effects action.
            }
            switch (word) {
                case "(" -> parens++;
                case ")" -> parens--;
                case "[" -> brackets++;
                case "]" -> brackets--;
                case "{" -> {
                    if (!bodyDeclaration) {
                        return null;
                    }
                    braces++;
                    body = true;
                }
                case "}" -> {
                    braces--;
                    if (body && braces == 0 && parens == 0 && brackets == 0) {
                        int end = token.end();
                        if (i + 1 < tokens.size() && tokens.get(i + 1).text().equals(";")) {
                            end = tokens.get(i + 1).end();
                        } else if (Set.of("struct", "class", "enum").contains(candidate.kind())
                                && i + 1 < tokens.size() && !tokens.get(i + 1).text().equals("}")) {
                            if (!text.substring(end, tokens.get(i + 1).start()).contains("\n")
                                    || Set.of("*", "&", "(").contains(tokens.get(i + 1).text())
                                    || i + 2 < tokens.size()
                                    && Set.of(";", "=", ",", "[").contains(tokens.get(i + 2).text())) {
                                return null;
                            }
                        }
                        return expandLine(text, tokens.get(first).start(), end);
                    }
                }
                default -> {
                }
            }
            if (parens < 0 || brackets < 0 || braces < 0) {
                return null;
            }
        }
        return null;
    }

    private static TextRange expandLine(String text, int start, int end) {
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        int newline = text.indexOf('\n', end);
        int lineEnd = newline < 0 ? text.length() : newline;
        if (text.substring(lineStart, start).isBlank() && text.substring(end, lineEnd).isBlank()) {
            return new TextRange(lineStart, newline < 0 ? lineEnd : newline + 1);
        }
        return new TextRange(start, end);
    }
}
