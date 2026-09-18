package slanglsp;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SlangLexerTest {
    @Test void numericLiteralsDoNotBecomeIdentifiersOrSwallowOperators() {
        String code = "0xFFu 0X1.Ap-2f 0b101u 0755 1e-3 0E+2 .5h 1.0f 42UL 1. 1+2 3-4 0.x 1..2";
        var numbers = new ArrayList<String>();
        var identifiers = new ArrayList<String>();
        var lexer = new SlangLexer();
        lexer.start(code);
        while (lexer.getTokenType() != null) {
            String token = code.substring(lexer.getTokenStart(), lexer.getTokenEnd());
            if (lexer.getTokenType() == SlangLexer.NUMBER) numbers.add(token);
            if (lexer.getTokenType() == SlangLexer.VARIABLE) identifiers.add(token);
            lexer.advance();
        }
        assertEquals(List.of("0xFFu", "0X1.Ap-2f", "0b101u", "0755", "1e-3", "0E+2", ".5h",
                "1.0f", "42UL", "1.", "1", "2", "3", "4", "0", "1", "2"), numbers);
        assertEquals(List.of("x"), identifiers);
    }

    @Test void restartingLexerAtEveryTokenPreservesDottedImports() {
        String code = "import /* comment */ shared.scene;\nmodule other.module;\nitem.value;";
        var lexer = new SlangLexer();
        lexer.start(code);
        while (lexer.getTokenType() != null) {
            var restarted = new SlangLexer();
            restarted.start(code, lexer.getTokenStart(), code.length(), lexer.getState());
            assertSame(lexer.getTokenType(), restarted.getTokenType());
            assertEquals(lexer.getTokenEnd(), restarted.getTokenEnd());
            lexer.advance();
        }
    }
}
