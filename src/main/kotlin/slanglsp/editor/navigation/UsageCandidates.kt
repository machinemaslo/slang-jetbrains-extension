package slanglsp.editor.navigation

import com.intellij.openapi.progress.ProgressManager
import slanglsp.language.SlangLexer

/** Candidates only: each occurrence is subsequently checked with textDocument/definition. */
internal object UsageCandidates {
    fun find(code: String, name: String): List<Int> {
        if (!code.contains(name)) {
            return emptyList()
        }
        val offsets = mutableListOf<Int>()
        val lexer = SlangLexer()
        lexer.start(code)

        while (lexer.tokenType != null) {
            ProgressManager.checkCanceled()
            val type = lexer.tokenType

            if ((type === SlangLexer.VARIABLE || type === SlangLexer.FUNCTION || type === SlangLexer.TYPE) &&
                code.regionMatches(lexer.tokenStart, name, 0, name.length) &&
                lexer.tokenEnd - lexer.tokenStart == name.length) {
                offsets.add(lexer.tokenStart)
            }

            lexer.advance()
        }

        return offsets
    }
}
