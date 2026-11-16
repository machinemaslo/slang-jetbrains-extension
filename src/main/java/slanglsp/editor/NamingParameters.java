package slanglsp.editor;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.TokenType;
import slanglsp.language.SlangLexer;
import slanglsp.settings.NamingRule;
import java.util.*;

/** Only simple, syntactically unambiguous named parameters are supplemented locally. */
final class NamingParameters {
    private record Token(String text, int offset, boolean identifier) {}

    static void collect(String text, int start, int end, Map<Integer, NamingAnnotator.Candidate> result) {
        var lexer = new SlangLexer();
        lexer.start(text, start, end, 0);
        List<Token> segment = new ArrayList<>();
        int depth = 0;
        int angles = 0;
        boolean started = false;
        boolean generic = false;
        while (lexer.getTokenType() != null) {
            var type = lexer.getTokenType();
            String token = text.substring(lexer.getTokenStart(), lexer.getTokenEnd());
            if (type == TokenType.WHITE_SPACE || type == SlangLexer.COMMENT || type == SlangLexer.BLOCK_COMMENT) {
                lexer.advance();
                continue;
            }
            if (!started) {
                if (token.equals("<") && !generic) {
                    generic = true;
                    angles = 1;
                    lexer.advance();
                    continue;
                }
                if (generic) {
                    if (token.equals(">") && angles == 1) {
                        addGeneric(segment, result);
                        segment.clear();
                        generic = false;
                        angles = 0;
                    } else if (token.equals(",") && angles == 1) {
                        addGeneric(segment, result);
                        segment.clear();
                    } else {
                        segment.add(new Token(token, lexer.getTokenStart(), token.matches("[A-Za-z_][A-Za-z_0-9]*")));
                        if (token.equals("<")) {
                            angles++;
                        } else if (token.equals(">")) {
                            angles--;
                        }
                    }
                    lexer.advance();
                    continue;
                }
                if (!token.equals("(")) {
                    return;
                }
                started = true;
                depth = 1;
            } else if (token.equals(")") && depth == 1) {
                add(segment, result);
                return;
            } else if (token.equals(",") && depth == 1 && angles == 0) {
                add(segment, result);
                segment.clear();
            } else {
                segment.add(new Token(token, lexer.getTokenStart(), token.matches("[A-Za-z_][A-Za-z_0-9]*")));
                switch (token) {
                    case "(", "[" -> depth++;
                    case ")", "]" -> depth--;
                    case "<" -> angles++;
                    case ">" -> angles--;
                    default -> {
                    }
                }
            }
            lexer.advance();
        }
    }

    private static void addGeneric(List<Token> tokens, Map<Integer, NamingAnnotator.Candidate> result) {
        if (tokens.isEmpty()) {
            return;
        }
        int index = Set.of("let", "typename", "class").contains(tokens.getFirst().text()) ? 1 : 0;
        if (index >= tokens.size()) {
            return;
        }
        var name = tokens.get(index);
        if (name.identifier() && (index + 1 == tokens.size()
                || Set.of(":", "=").contains(tokens.get(index + 1).text()))) {
            result.put(name.offset(), new NamingAnnotator.Candidate(name.text(), NamingRule.Category.GENERIC_PARAMETERS,
                    TextRange.from(name.offset(), name.text().length())));
        }
    }

    private static void add(List<Token> tokens, Map<Integer, NamingAnnotator.Candidate> result) {
        int end = tokens.size();
        for (int i = 0; i < end; i++) {
            if (Set.of(":", "=", "[").contains(tokens.get(i).text())) {
                end = i;
                break;
            }
        }
        if (end < 2) {
            return;
        }
        var modifiers = Set.of("in", "out", "inout", "const", "uniform", "no_diff", "ref", "constref");
        boolean hasType = false;
        for (int i = 0; i < end - 1; i++) {
            if (!modifiers.contains(tokens.get(i).text())) {
                hasType = true;
                break;
            }
        }
        if (!hasType) {
            return;
        }
        var name = tokens.get(end - 1);
        var before = tokens.get(end - 2);
        if (name.identifier() && (before.identifier() || before.text().equals(">")
                || before.text().matches("(bool|int|uint|float|double|half)[0-9x]*"))) {
            result.put(name.offset(), new NamingAnnotator.Candidate(name.text(), NamingRule.Category.PARAMETERS,
                    TextRange.from(name.offset(), name.text().length())));
        }
    }
}
