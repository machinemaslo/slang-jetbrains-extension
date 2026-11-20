package slanglsp.language

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/** Reads source-level shader stages without resolving build flags or preprocessing the module. */
internal object EntryPointStages {
    fun collect(source: CharSequence): List<String> {
        val cursor = Cursor(source)
        val stages = linkedSetOf<String>()

        while (cursor.type != null) {
            if (cursor.type === SlangLexer.LBRACKET) {
                val attributes = cursor.readAttributes()
                if (attributes.isNotEmpty() && cursor.functionFollows()) {
                    stages.addAll(attributes)
                }
            } else {
                cursor.advance()
            }
        }

        return stages.toList()
    }

    private class Cursor(source: CharSequence) {
        private val lexer = SlangLexer().apply { start(source) }
        val type: IElementType?
            get() = lexer.tokenType
        private val text: String
            get() = lexer.tokenText

        init {
            skipTrivia()
        }

        fun advance() {
            ProgressManager.checkCanceled()
            lexer.advance()
            skipTrivia()
        }

        private fun skipTrivia() {
            while (type === TokenType.WHITE_SPACE || type === SlangLexer.COMMENT || type === SlangLexer.BLOCK_COMMENT) {
                lexer.advance()
            }
        }

        private fun take(expected: IElementType): Boolean {
            if (type !== expected) {
                return false
            }

            advance()
            return true
        }

        fun readAttributes(): List<String> {
            val stages = mutableListOf<String>()

            while (take(SlangLexer.LBRACKET)) {
                if (type != null && text == "shader") {
                    advance()
                    if (take(SlangLexer.LPAREN) && type === SlangLexer.STRING) {
                        val stage = stageName(text)
                        advance()
                        if (take(SlangLexer.RPAREN) && take(SlangLexer.RBRACKET)) {
                            if (stage != null) {
                                stages.add(stage)
                            }
                            continue
                        }
                    }
                }
                skipAttribute()
            }

            return stages
        }

        private fun skipAttribute() {
            var depth = 1
            while (type != null && depth > 0) {
                when (type) {
                    SlangLexer.LBRACKET -> depth++
                    SlangLexer.RBRACKET -> depth--
                    SlangLexer.SEMICOLON, SlangLexer.LBRACE, SlangLexer.RBRACE -> {
                        if (depth == 1) {
                            return
                        }
                    }
                }
                advance()
            }
        }

        fun functionFollows(): Boolean {
            var words = 0
            var angles = 0
            var afterName = false

            while (type != null) {
                when {
                    type === SlangLexer.LPAREN && angles == 0 -> return words >= 2 && afterName
                    text == "<" -> angles++
                    text == ">" && angles > 0 -> angles--
                    angles > 0 && (type === SlangLexer.COMMA || type === SlangLexer.NUMBER) -> Unit
                    type in DECLARATION_WORDS && text !in NON_FUNCTION_KEYWORDS -> {
                        if (angles == 0) {
                            words++
                            afterName =
                                type === SlangLexer.FUNCTION ||
                                    type === SlangLexer.VARIABLE ||
                                    type === SlangLexer.TYPE
                        }
                    }
                    text == ":" || type === SlangLexer.DOT -> afterName = false
                    else -> return false
                }
                advance()
            }

            return false
        }
    }

    private fun stageName(literal: String): String? {
        if (literal.length < 2 || literal.first() != '"' || literal.last() != '"') {
            return null
        }

        return when (val name = literal.substring(1, literal.lastIndex)) {
            "pixel" -> "fragment"
            "task" -> "amplification"
            "tesscontrol" -> "hull"
            "tesseval" -> "domain"
            else -> name.takeIf { it in STAGES }
        }
    }

    private val DECLARATION_WORDS =
        setOf(SlangLexer.KEYWORD, SlangLexer.BUILTIN_TYPE, SlangLexer.TYPE, SlangLexer.VARIABLE, SlangLexer.FUNCTION)

    private val NON_FUNCTION_KEYWORDS =
        setOf("struct", "class", "interface", "enum", "namespace", "extension", "typedef", "typealias", "return")

    private val STAGES =
        setOf(
            "vertex",
            "fragment",
            "compute",
            "geometry",
            "hull",
            "domain",
            "raygeneration",
            "intersection",
            "anyhit",
            "closesthit",
            "miss",
            "callable",
            "mesh",
            "amplification",
            "dispatch",
            "node",
        )
}
