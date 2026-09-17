package slanglsp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SlangLexerTest {
    @Test
    public void testLexerHeuristics() {
        SlangLexer lexer = new SlangLexer();
        String code = "struct Tensor { int a; void main() { } }";
        lexer.start(code);
        assertNotNull(lexer.getTokenType(), "Lexer should start with a valid token");
    }
    @Test
    public void restartingLexerAtEveryTokenPreservesDottedImports() {
        String code = "import /* comment */ shared.scene;\nmodule other.module;\nitem.value;";
        SlangLexer lexer = new SlangLexer();
        lexer.start(code);
        while (lexer.getTokenType() != null) {
            SlangLexer restarted = new SlangLexer();
            restarted.start(code, lexer.getTokenStart(), code.length(), lexer.getState());
            org.junit.jupiter.api.Assertions.assertSame(lexer.getTokenType(), restarted.getTokenType());
            org.junit.jupiter.api.Assertions.assertEquals(lexer.getTokenEnd(), restarted.getTokenEnd());
            lexer.advance();
        }
    }

}
