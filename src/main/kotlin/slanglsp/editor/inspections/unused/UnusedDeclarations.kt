package slanglsp.editor.inspections.unused

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import java.util.regex.Pattern
import slanglsp.language.SlangLexer

/** Conservative local declaration discovery; symbol identity is checked by the usage search. */
internal object UnusedDeclarations {
    private val EXTERNAL: Pattern =
        Pattern.compile(
            "\\b(export|extern|__extern_cpp|__exported|override)\\b|\\[\\s*(shader|numthreads|extern|__extern|require)\\b")
    private val MODIFIERS = mutableSetOf<String?>("const", "static", "volatile", "precise", "groupshared", "uniform")

    @JvmStatic
    fun isExternal(name: String, prefix: String): Boolean {
        return name == "main" || EXTERNAL.matcher(prefix).find()
    }

    @JvmStatic
    fun locals(text: String, start: Int, end: Int): List<UnusedSymbolsAnnotator.Candidate> {
        val result = ArrayList<UnusedSymbolsAnnotator.Candidate>()
        if (end <= start || text.substring(start, end).contains("#")) {
            return result
        }

        val tokens = getTokens(text, start, end)

        var inBody = false

        var i = 0
        while (i + 2 < tokens.size) {
            val boundary = tokens[i].text
            if (boundary == "{") {
                inBody = true
            }
            if (!inBody ||
                !(boundary == "{" ||
                    boundary == ";" ||
                    boundary == "}" ||
                    boundary == "(" && i > 0 && tokens[i - 1].text == "for")) {
                i++
                continue
            }
            var j = i + 1

            while (j < tokens.size && MODIFIERS.contains(tokens[j].text)) {
                j++
            }

            if (j >= tokens.size) {
                i++
                continue
            }

            val type = tokens[j++]
            if (!type.type && (type.text != "let") && (type.text != "var")) {
                i++
                continue
            }
            // Generic resource/vector types. Reject expressions with unbalanced angle brackets.
            if (j < tokens.size && tokens[j].text == "<") {
                var depth = 0
                do {
                    val token = tokens[j++].text
                    if (token == "<") {
                        depth++
                    }
                    if (token == ">") {
                        depth--
                    }
                    if (token == ";" || token == "{") {
                        break
                    }
                } while (j < tokens.size && depth > 0)

                if (depth != 0) {
                    i++
                    continue
                }
            }

            if (j + 1 >= tokens.size) {
                i++
                continue
            }

            val name = tokens[j]
            if (!name.text.matches("[A-Za-z_][A-Za-z_0-9]*".toRegex())) {
                i++
                continue
            }

            val next = tokens[j + 1].text
            if (!mutableSetOf<String?>("=", ";", "[", ",", ":").contains(next)) {
                i++
                continue
            }

            result.add(
                UnusedSymbolsAnnotator.Candidate(
                    name.text, "variable", TextRange(name.start, name.start + name.text.length)))
            i++
        }

        return result
    }

    private fun getTokens(text: String, start: Int, end: Int): MutableList<Token> {
        val lexer = SlangLexer()
        lexer.start(text, start, end, 0)
        val tokens: MutableList<Token> = ArrayList()

        while (lexer.tokenType != null) {
            val kind = lexer.tokenType

            if (kind !== TokenType.WHITE_SPACE && kind !== SlangLexer.COMMENT && kind !== SlangLexer.BLOCK_COMMENT) {
                val word = text.substring(lexer.tokenStart, lexer.tokenEnd)
                val type =
                    kind === SlangLexer.BUILTIN_TYPE ||
                        kind === SlangLexer.TYPE ||
                        kind === SlangLexer.VARIABLE ||
                        kind === SlangLexer.TEXT
                tokens.add(Token(word, lexer.tokenStart, type))
            }

            lexer.advance()
        }
        return tokens
    }

    @JvmRecord private data class Token(val text: String, val start: Int, val type: Boolean)
}
