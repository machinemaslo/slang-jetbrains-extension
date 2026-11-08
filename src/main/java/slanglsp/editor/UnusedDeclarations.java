package slanglsp.editor;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.TokenType;
import slanglsp.language.SlangLexer;

import java.util.*;
import java.util.regex.Pattern;

/** Conservative local declaration discovery; symbol identity is checked by the usage search. */
final class UnusedDeclarations {
    private static final Pattern EXTERNAL = Pattern.compile(
            "\\b(export|extern|__extern_cpp|__exported|override)\\b|\\[\\s*(shader|numthreads|extern|__extern|require)\\b");
    private static final Set<String> MODIFIERS =
            Set.of("const", "static", "volatile", "precise", "groupshared", "uniform");

    private record Token(String text, int start, boolean type) {}

    static boolean isExternal(String name, String prefix) {
        return name.equals("main") || EXTERNAL.matcher(prefix).find();
    }

    static List<UnusedSymbolsAnnotator.Candidate> locals(String text, int start, int end) {
        List<UnusedSymbolsAnnotator.Candidate> result = new ArrayList<>();
        if (end <= start || text.substring(start, end).contains("#")) {
            return result;
        }

        var lexer = new SlangLexer();
        lexer.start(text, start, end, 0);
        List<Token> tokens = new ArrayList<>();

        while (lexer.getTokenType() != null) {
            var kind = lexer.getTokenType();

            if (kind != TokenType.WHITE_SPACE && kind != SlangLexer.COMMENT && kind != SlangLexer.BLOCK_COMMENT) {
                String word = text.substring(lexer.getTokenStart(), lexer.getTokenEnd());
                boolean type = kind == SlangLexer.BUILTIN_TYPE || kind == SlangLexer.TYPE ||
                               kind == SlangLexer.VARIABLE || kind == SlangLexer.TEXT;
                tokens.add(new Token(word, lexer.getTokenStart(), type));
            }

            lexer.advance();
        }

        boolean inBody = false;

        for (int i = 0; i + 2 < tokens.size(); i++) {
            String boundary = tokens.get(i).text;
            if (boundary.equals("{")) {
                inBody = true;
            }
            if (!inBody || !(boundary.equals("{") || boundary.equals(";") || boundary.equals("}") ||
                                   boundary.equals("(") && i > 0 && tokens.get(i - 1).text.equals("for")))
                continue;
            int j = i + 1;

            while (j < tokens.size() && MODIFIERS.contains(tokens.get(j).text)) {
                j++;
            }

            if (j >= tokens.size()) {
                continue;
            }

            Token type = tokens.get(j++);
            if (!type.type && !type.text.equals("let") && !type.text.equals("var")) {
                continue;
            }
            // Generic resource/vector types. Reject expressions with unbalanced angle brackets.
            if (j < tokens.size() && tokens.get(j).text.equals("<")) {
                int depth = 0;
                do {
                    String token = tokens.get(j++).text;
                    if (token.equals("<")) {
                        depth++;
                    }
                    if (token.equals(">")) {
                        depth--;
                    }
                    if (token.equals(";") || token.equals("{")) {
                        break;
                    }
                } while (j < tokens.size() && depth > 0);

                if (depth != 0) {
                    continue;
                }
            }

            if (j + 1 >= tokens.size()) {
                continue;
            }

            Token name = tokens.get(j);
            if (!name.text.matches("[A-Za-z_][A-Za-z_0-9]*")) {
                continue;
            }

            String next = tokens.get(j + 1).text;
            if (!Set.of("=", ";", "[", ",", ":").contains(next)) {
                continue;
            }

            result.add(new UnusedSymbolsAnnotator.Candidate(
                    name.text, "variable", new TextRange(name.start, name.start + name.text.length())));
        }

        return result;
    }
}
