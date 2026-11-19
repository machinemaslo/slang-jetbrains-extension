package slanglsp.language

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class SlangLexerTest {
    @Test
    fun numericLiteralsDoNotBecomeIdentifiersOrSwallowOperators() {
        val code = "0xFFu 0X1.Ap-2f 0b101u 0755 1e-3 0E+2 .5h 1.0f 42UL 1. 1+2 3-4 0.x 1..2"
        val numbers = ArrayList<String?>()
        val identifiers = ArrayList<String?>()
        val lexer = SlangLexer()
        lexer.start(code)

        while (lexer.tokenType != null) {
            val token = code.substring(lexer.tokenStart, lexer.tokenEnd)

            if (lexer.tokenType === SlangLexer.NUMBER) {
                numbers.add(token)
            }

            if (lexer.tokenType === SlangLexer.VARIABLE) {
                identifiers.add(token)
            }
            lexer.advance()
        }
        Assertions.assertEquals(
            mutableListOf<String?>(
                "0xFFu",
                "0X1.Ap-2f",
                "0b101u",
                "0755",
                "1e-3",
                "0E+2",
                ".5h",
                "1.0f",
                "42UL",
                "1.",
                "1",
                "2",
                "3",
                "4",
                "0",
                "1",
                "2"),
            numbers)
        Assertions.assertEquals(mutableListOf<String?>("x"), identifiers)
    }

    @Test
    fun restartingLexerAtEveryTokenPreservesDottedImports() {
        val code = "import /* comment */ shared.scene;\nmodule other.module;\nitem.value;"
        val lexer = SlangLexer()
        lexer.start(code)

        while (lexer.tokenType != null) {
            val restarted = SlangLexer()
            restarted.start(code, lexer.tokenStart, code.length, lexer.state)
            Assertions.assertSame(lexer.tokenType, restarted.tokenType)
            Assertions.assertEquals(lexer.tokenEnd, restarted.tokenEnd)
            lexer.advance()
        }
    }
}
