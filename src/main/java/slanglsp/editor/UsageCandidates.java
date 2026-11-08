package slanglsp.editor;

import slanglsp.language.SlangLexer;

import com.intellij.openapi.progress.ProgressManager;
import java.util.ArrayList;
import java.util.List;

/** Candidates only: each occurrence is subsequently checked with textDocument/definition. */
final class UsageCandidates {
    static List<Integer> find(String code, String name) {
        if (!code.contains(name))
            return List.of();
        var offsets = new ArrayList<Integer>();
        var lexer = new SlangLexer();
        lexer.start(code);

        while (lexer.getTokenType() != null) {
            ProgressManager.checkCanceled();
            var type = lexer.getTokenType();

            if ((type == SlangLexer.VARIABLE || type == SlangLexer.FUNCTION || type == SlangLexer.TYPE) &&
                    code.regionMatches(lexer.getTokenStart(), name, 0, name.length()) &&
                    lexer.getTokenEnd() - lexer.getTokenStart() == name.length()) {
                offsets.add(lexer.getTokenStart());
            }

            lexer.advance();
        }

        return offsets;
    }
}
