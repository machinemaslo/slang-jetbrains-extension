package slanglsp.highlighting

import com.intellij.psi.TokenType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import slanglsp.language.SlangLexer

internal class SlangSyntaxHighlighterTest {
    @Test
    fun reusesHighlightArraysForTokensWithTheSameColor() {
        val type = SlangSyntaxHighlighter.getTokenHighlights(SlangLexer.TYPE)
        val brace = SlangSyntaxHighlighter.getTokenHighlights(SlangLexer.LBRACE)

        assertEquals(SyntaxColors.TYPE_NAME, type.single())
        assertSame(type, SlangSyntaxHighlighter.getTokenHighlights(SlangLexer.BUILTIN_TYPE))
        assertEquals(SyntaxColors.BRACES, brace.single())
        assertSame(brace, SlangSyntaxHighlighter.getTokenHighlights(SlangLexer.RBRACE))

        val uncolored = SlangSyntaxHighlighter.getTokenHighlights(TokenType.WHITE_SPACE)
        assertTrue(uncolored.isEmpty())
        assertSame(uncolored, SlangSyntaxHighlighter.getTokenHighlights(TokenType.BAD_CHARACTER))
    }

    @Test
    fun sharingTheHighlighterDoesNotShareLexerState() {
        val factory = SlangSyntaxHighlighterFactory()
        val highlighter = factory.getSyntaxHighlighter(null, null)

        assertSame(highlighter, HighlightingSettingsPage().highlighter)
        assertNotSame(highlighter.highlightingLexer, highlighter.highlightingLexer)
    }
}
