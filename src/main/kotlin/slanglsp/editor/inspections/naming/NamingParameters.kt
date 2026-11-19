package slanglsp.editor.inspections.naming

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import slanglsp.language.SlangLexer
import slanglsp.settings.codestyle.NamingRule

/** Only simple, syntactically unambiguous named parameters are supplemented locally. */
internal object NamingParameters {
    fun collect(text: String, start: Int, end: Int, result: MutableMap<Int?, NamingAnnotator.Candidate?>) {
        val lexer = SlangLexer()
        lexer.start(text, start, end, 0)
        val segment: MutableList<Token> = ArrayList()
        var depth = 0
        var angles = 0
        var started = false
        var generic = false
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            val token = text.substring(lexer.tokenStart, lexer.tokenEnd)
            if (type === TokenType.WHITE_SPACE || type === SlangLexer.COMMENT || type === SlangLexer.BLOCK_COMMENT) {
                lexer.advance()
                continue
            }
            if (!started) {
                if (token == "<" && !generic) {
                    generic = true
                    angles = 1
                    lexer.advance()
                    continue
                }
                if (generic) {
                    if (token == ">" && angles == 1) {
                        addGeneric(segment, result)
                        segment.clear()
                        generic = false
                        angles = 0
                    } else if (token == "," && angles == 1) {
                        addGeneric(segment, result)
                        segment.clear()
                    } else {
                        segment.add(Token(token, lexer.tokenStart, token.matches("[A-Za-z_][A-Za-z_0-9]*".toRegex())))
                        if (token == "<") {
                            angles++
                        } else if (token == ">") {
                            angles--
                        }
                    }
                    lexer.advance()
                    continue
                }
                if (token != "(") {
                    return
                }
                started = true
                depth = 1
            } else if (token == ")" && depth == 1) {
                add(segment, result)
                return
            } else if (token == "," && depth == 1 && angles == 0) {
                add(segment, result)
                segment.clear()
            } else {
                segment.add(Token(token, lexer.tokenStart, token.matches("[A-Za-z_][A-Za-z_0-9]*".toRegex())))
                when (token) {
                    "(",
                    "[" -> depth++
                    ")",
                    "]" -> depth--
                    "<" -> angles++
                    ">" -> angles--
                    else -> {}
                }
            }
            lexer.advance()
        }
    }

    private fun addGeneric(tokens: MutableList<Token>, result: MutableMap<Int?, NamingAnnotator.Candidate?>) {
        if (tokens.isEmpty()) {
            return
        }
        val index = if (tokens.first().text in setOf("let", "typename", "class")) 1 else 0
        if (index >= tokens.size) {
            return
        }
        val name = tokens[index]
        if (name.identifier &&
            (index + 1 == tokens.size || mutableSetOf<String?>(":", "=").contains(tokens[index + 1].text))) {
            result[name.offset] =
                NamingAnnotator.Candidate(
                    name.text, NamingRule.Category.GENERIC_PARAMETERS, TextRange.from(name.offset, name.text.length))
        }
    }

    private fun add(tokens: MutableList<Token>, result: MutableMap<Int?, NamingAnnotator.Candidate?>) {
        var end = tokens.size
        run {
            var i = 0
            while (i < end) {
                if (mutableSetOf<String?>(":", "=", "[").contains(tokens[i].text)) {
                    end = i
                    break
                }
                i++
            }
        }
        if (end < 2) {
            return
        }
        val modifiers: MutableSet<String> =
            mutableSetOf("in", "out", "inout", "const", "uniform", "no_diff", "ref", "constref")
        var hasType = false
        for (i in 0..<end - 1) {
            if (!modifiers.contains(tokens[i].text)) {
                hasType = true
                break
            }
        }
        if (!hasType) {
            return
        }
        val name = tokens[end - 1]
        val before = tokens[end - 2]
        if (name.identifier &&
            (before.identifier ||
                before.text == ">" ||
                before.text.matches("(bool|int|uint|float|double|half)[0-9x]*".toRegex()))) {
            result[name.offset] =
                NamingAnnotator.Candidate(
                    name.text, NamingRule.Category.PARAMETERS, TextRange.from(name.offset, name.text.length))
        }
    }

    @JvmRecord private data class Token(val text: String, val offset: Int, val identifier: Boolean)
}
