package slanglsp.language

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import java.util.regex.Pattern

class SlangLexer : LexerBase() {
    private var buffer: CharSequence? = null
    private var endOffset = 0
    private var position = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var state = 0
    private var tokenState = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        this.position = startOffset
        this.state = initialState
        advance()
    }

    override fun getState(): Int {
        return tokenState
    }

    override fun getTokenType(): IElementType? {
        return tokenType
    }

    override fun getTokenStart(): Int {
        return tokenStart
    }

    override fun getTokenEnd(): Int {
        return tokenEnd
    }

    override fun advance() {
        if (position >= endOffset) {
            tokenType = null
            return
        }

        tokenState = state
        tokenStart = position
        val c = buffer!![position]

        if (Character.isWhitespace(c)) {
            while (position < endOffset && Character.isWhitespace(buffer!![position])) {
                position++
            }
            tokenType = TokenType.WHITE_SPACE
        } else if (c == '/' && position + 1 < endOffset && buffer!![position + 1] == '/') {
            while (position < endOffset && buffer!![position] != '\n') {
                position++
            }
            tokenType = COMMENT
        } else if (c == '/' && position + 1 < endOffset && buffer!![position + 1] == '*') {
            position += 2

            while (position < endOffset) {
                if (buffer!![position] == '*' && position + 1 < endOffset && buffer!![position + 1] == '/') {
                    position += 2
                    break
                }

                position++
            }

            tokenType = BLOCK_COMMENT
        } else if (c == '"' || c == '\'') {
            position++

            while (position < endOffset && buffer!![position] != c) {
                if (buffer!![position] == '\\' && position + 1 < endOffset) {
                    position++
                }
                position++
            }

            if (position < endOffset) {
                position++
            }
            tokenType = STRING
        } else if (isDigit(c) || (c == '.' && isDigit(peek(1)))) {
            readNumber()
            tokenType = NUMBER
        } else if (c == '#') {
            do {
                position++
            } while (position < endOffset && (buffer!![position] == ' ' || buffer!![position] == '\t'))

            while (position < endOffset && Character.isLetter(buffer!![position])) {
                position++
            }
            tokenType = DIRECTIVE
        } else if (Character.isLetter(c) || c == '_') {
            while (position < endOffset &&
                (Character.isLetterOrDigit(buffer!![position]) || buffer!![position] == '_')) {
                position++
            }
            val word = buffer!!.subSequence(tokenStart, position).toString()

            if (state == MODULE_PATH) {
                while (position + 1 < endOffset &&
                    buffer!![position] == '.' &&
                    (Character.isLetter(buffer!![position + 1]) || buffer!![position + 1] == '_')) {
                    do {
                        position++
                    } while (position < endOffset &&
                        (Character.isLetterOrDigit(buffer!![position]) || buffer!![position] == '_'))
                }

                tokenType = MODULE_NAME
            } else if (isBuiltinType(word)) {
                tokenType = BUILTIN_TYPE
            } else if (KEYWORDS.contains(word)) {
                tokenType = KEYWORD
            } else {
                var peek = position

                while (peek < endOffset && Character.isWhitespace(buffer!![peek])) {
                    peek++
                }
                val isFunction = peek < endOffset && buffer!![peek] == '('

                tokenType =
                    if (isFunction) {
                        FUNCTION
                    } else if (Character.isUpperCase(word[0])) {
                        TYPE
                    } else {
                        VARIABLE
                    }
            }
        } else {
            position++
            if (c == '.' && peek(0) == '.') {
                position++
                if (peek(0) == '.') {
                    position++
                }
            }

            tokenType =
                when (c) {
                    '{' -> LBRACE
                    '}' -> RBRACE
                    '[' -> LBRACKET
                    ']' -> RBRACKET
                    '(' -> LPAREN
                    ')' -> RPAREN
                    ',' -> COMMA
                    '.' -> DOT
                    ';' -> SEMICOLON
                    '+',
                    '-',
                    '*',
                    '/',
                    '%',
                    '=',
                    '!',
                    '<',
                    '>',
                    '&',
                    '|',
                    '^',
                    '~',
                    '?',
                    ':' -> OPERATOR
                    else -> TEXT
                }
        }

        if (tokenType !== TokenType.WHITE_SPACE && tokenType !== COMMENT && tokenType !== BLOCK_COMMENT) {
            val text = buffer!!.subSequence(tokenStart, position).toString()
            state = if (tokenType === KEYWORD && (text == "import" || text == "module")) MODULE_PATH else 0
        }

        tokenEnd = position
    }

    private fun peek(distance: Int): Char {
        val index = position + distance
        return if (index < endOffset) buffer!![index] else '\u0000'
    }

    private fun readNumber() {
        var base = 10
        if (peek(0) == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            base = 16
            position += 2
        } else if (peek(0) == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
            base = 2
            position += 2
        }

        readDigits(base)
        // Preserve scalar swizzles and range punctuation instead of swallowing their dots.
        if (peek(0) == '.' && peek(1) != '.' && peek(1) != 'x' && peek(1) != 'r') {
            position++
            readDigits(base)
        }

        val exponent = peek(0)
        if ((base == 10 && (exponent == 'e' || exponent == 'E')) ||
            (base == 16 && (exponent == 'p' || exponent == 'P'))) {
            position++
            if (peek(0) == '+' || peek(0) == '-') {
                position++
            }
            readDigits(10)
        }
        // Like slangd, keep suffixes in the literal and let the compiler validate them.
        while (isDigit(peek(0)) || (peek(0) in 'a'..'z') || (peek(0) in 'A'..'Z') || peek(0) == '_') {
            position++
        }
    }

    private fun readDigits(base: Int) {
        while (isDigit(peek(0)) || (base == 16 && ((peek(0) in 'a'..'f') || (peek(0) in 'A'..'F')))) {
            position++
        }
    }

    override fun getBufferSequence(): CharSequence {
        return buffer!!
    }

    override fun getBufferEnd(): Int {
        return endOffset
    }

    companion object {
        private const val MODULE_PATH = 1
        private val BUILTIN_TYPE_PATTERN: Pattern = Pattern.compile("(int|float|uint|bool|half|double)[1-4]?(x[1-4])?")

        fun isBuiltinType(word: String): Boolean {
            return BUILTIN_TYPE_PATTERN.matcher(word).matches()
        }

        val MODULE_NAME: IElementType = IElementType("MODULE_NAME", SlangLanguage.INSTANCE)
        val KEYWORD: IElementType = IElementType("KEYWORD", SlangLanguage.INSTANCE)
        val BUILTIN_TYPE: IElementType = IElementType("BUILTIN_TYPE", SlangLanguage.INSTANCE)
        val DIRECTIVE: IElementType = IElementType("DIRECTIVE", SlangLanguage.INSTANCE)
        val OPERATOR: IElementType = IElementType("OPERATOR", SlangLanguage.INSTANCE)
        val LBRACE: IElementType = IElementType("LBRACE", SlangLanguage.INSTANCE)
        val RBRACE: IElementType = IElementType("RBRACE", SlangLanguage.INSTANCE)
        val LBRACKET: IElementType = IElementType("LBRACKET", SlangLanguage.INSTANCE)
        val RBRACKET: IElementType = IElementType("RBRACKET", SlangLanguage.INSTANCE)
        val LPAREN: IElementType = IElementType("LPAREN", SlangLanguage.INSTANCE)
        val RPAREN: IElementType = IElementType("RPAREN", SlangLanguage.INSTANCE)
        val COMMA: IElementType = IElementType("COMMA", SlangLanguage.INSTANCE)
        val DOT: IElementType = IElementType("DOT", SlangLanguage.INSTANCE)
        val SEMICOLON: IElementType = IElementType("SEMICOLON", SlangLanguage.INSTANCE)
        val TYPE: IElementType = IElementType("TYPE", SlangLanguage.INSTANCE)
        val FUNCTION: IElementType = IElementType("FUNCTION", SlangLanguage.INSTANCE)
        @JvmField val VARIABLE: IElementType = IElementType("VARIABLE", SlangLanguage.INSTANCE)
        val STRING: IElementType = IElementType("STRING", SlangLanguage.INSTANCE)
        @JvmField val NUMBER: IElementType = IElementType("NUMBER", SlangLanguage.INSTANCE)
        val COMMENT: IElementType = IElementType("COMMENT", SlangLanguage.INSTANCE)
        val BLOCK_COMMENT: IElementType = IElementType("BLOCK_COMMENT", SlangLanguage.INSTANCE)
        val TEXT: IElementType = IElementType("TEXT", SlangLanguage.INSTANCE)

        // Lexical keywords mirror the official VS Code grammar; slangd does not emit all of them.
        private val KEYWORDS =
            mutableSetOf<String?>(
                "struct",
                "interface",
                "enum",
                "extension",
                "typealias",
                "associatedtype",
                "let",
                "var",
                "void",
                "return",
                "if",
                "else",
                "for",
                "while",
                "do",
                "switch",
                "case",
                "default",
                "break",
                "continue",
                "static",
                "const",
                "true",
                "false",
                "in",
                "out",
                "inout",
                "__init",
                "operator",
                "public",
                "private",
                "protected",
                "internal",
                "import",
                "module",
                "implementing",
                "property",
                "get",
                "set",
                "yield",
                "defer",
                "as",
                "is",
                "reinterpret",
                "sizeof",
                "alignof",
                "typeof",
                "groupshared",
                "uniform",
                "inline",
                "extern",
                "export",
                "class",
                "namespace",
                "using",
                "typedef",
                "this",
                "discard",
                "null",
                "nullptr",
                "mutating",
                "nonmutating",
                "try",
                "throw",
                "throws",
                "catch",
                "spirv_asm",
                "__target_switch",
                "__stage_switch",
                "__intrinsic_asm",
                "__GPU_FOREACH",
                "dynamic_uniform",
                "shared",
                "volatile",
                "coherent",
                "restrict",
                "readonly",
                "writeonly",
                "override",
                "__extern_cpp",
                "param",
                "require",
                "row_major",
                "column_major",
                "nointerpolation",
                "noperspective",
                "linear",
                "sample",
                "centroid",
                "precise",
                "ref",
                "__ref",
                "__constref",
                "dyn",
                "some",
                "implicit",
                "noncopyable",
                "constexpr",
                "highp",
                "lowp",
                "mediump",
                "__builtin",
                "__global",
                "point",
                "line",
                "triangle",
                "lineadj",
                "triangleadj",
                "vertices",
                "indices",
                "primitives",
                "payload",
                "__prefix",
                "__postfix",
                "__exported",
                "layout",
                "hitAttributeEXT",
                "__intrinsic_op",
                "__target_intrinsic",
                "__specialized_for_target",
                "__glsl_extension",
                "__glsl_version",
                "__spirv_version",
                "__wgsl_extension",
                "__cuda_sm_version",
                "__builtin_type",
                "__builtin_requirement",
                "__magic_type",
                "__magic_enum",
                "__intrinsic_type",
                "__implicit_conversion",
                "__attributeTarget",
                "This",
                "countof",
                "no_diff",
                "fwd_diff",
                "bwd_diff",
                "__fwd_diff",
                "__bwd_diff",
                "__dispatch_kernel",
                "each",
                "expand",
                "optional",
                "nonempty",
                "__return_val",
                "__first",
                "__last",
                "__trimFirst",
                "__trimLast",
                "__packBranch",
                "__getAddress",
                "__floatAsInt")

        private fun isDigit(c: Char): Boolean {
            return c in '0'..'9'
        }
    }
}
