package slanglsp.formatting.native

import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.BlockEx
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.formatting.WrapType
import com.intellij.lang.Language
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.tree.IElementType
import slanglsp.language.SlangFileType
import slanglsp.language.SlangLanguage
import slanglsp.language.SlangLexer
import slanglsp.language.generics.GenericContext
import slanglsp.settings.codestyle.SlangCodeStyleSettings

/**
 * The parser exposes token-level PSI. Virtual formatting blocks retain exact token ranges, so formatting never rewrites
 * strings, comments, directives, or the PSI structure.
 */
internal open class NativeFormattingBlock(
    private val source: String,
    settings: CodeStyleSettings,
) : BlockEx {
    private val children = mutableListOf<Leaf>()
    private val common = settings.getCommonSettings(SlangLanguage.INSTANCE)
    private val custom = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
    private val newLineBraces = custom.BRACES_ON_NEW_LINE
    private val functionStarts = mutableSetOf<Int>()

    private class Parenthesis(
        val control: String,
        val wrap: Wrap?,
        val alignment: Alignment?,
    )

    private class Brace(val parentheses: Int, val kind: BraceKind)

    private enum class BraceKind {
        NONE,
        FUNCTION,
        TYPE,
        STRUCT,
        CLASS,
        ENUM,
        NAMESPACE,
        BUFFER,
        CONTROL,
        SWITCH,
        CASE,
        INITIALIZER;

        val isDeclaration: Boolean
            get() =
                when (this) {
                    TYPE,
                    STRUCT,
                    CLASS,
                    ENUM,
                    NAMESPACE,
                    BUFFER -> true
                    else -> false
                }
    }

    init {
        if (!custom.DISABLE_FORMAT) {
            buildChildren(settings)
        }
    }

    private fun buildChildren(settings: CodeStyleSettings) {
        val indent = settings.getIndentOptions(SlangFileType)
        val generic = if ('<' in source) GenericContext(source) else null
        val lexer = SlangLexer()
        lexer.start(source)

        var braces = 0
        var namespaces = 0
        var parentheses = 0
        var unbraced = 0
        val parenStack = ArrayDeque<Parenthesis>()
        val braceStack = ArrayDeque<Brace>()
        var pendingDeclaration = BraceKind.NONE
        var pendingFunction = false
        var pendingSwitch = false
        var statementAssignment = false
        var caseLabel = false
        var formattingOff = false
        var caseDepth = -1

        while (true) {
            val type = lexer.tokenType ?: break
            ProgressManager.checkCanceled()
            val start = lexer.tokenStart
            var end = lexer.tokenEnd

            if (type === TokenType.WHITE_SPACE) {
                lexer.advance()
                continue
            }

            val directive = type === SlangLexer.DIRECTIVE
            if (directive) {
                end = directiveEnd(source, start)
            }

            var text = source.substring(start, end)
            val preserveGap = formattingOff
            if (type === SlangLexer.COMMENT || type === SlangLexer.BLOCK_COMMENT) {
                FORMAT_MARKER.find(text)?.let { marker ->
                    val (mode) = marker.destructured
                    formattingOff = mode == "off"
                }
            }

            val angle =
                generic != null &&
                    ((text == "<" && generic.isOpening(generic.tokenAt(start))) ||
                        (text == ">" && generic.isClosing(generic.tokenAt(start))))
            if (type === SlangLexer.OPERATOR && !angle) {
                // Keep actual multi-character operators atomic, but never merge spaced tokens.
                for (length in 3 downTo 2) {
                    if (start + length > source.length) {
                        continue
                    }
                    val operator = source.substring(start, start + length)
                    if (operator in OPERATORS) {
                        text = operator
                        end = start + length
                        break
                    }
                }
            }

            val previous = children.lastOrNull()
            val before = previous?.text ?: ""

            if (text == "{" && before == "spirv_asm") {
                // Embedded SPIR-V has its own grammar. Keep the whole body as one leaf.
                var depth = 1
                while (depth > 0) {
                    lexer.advance()
                    if (lexer.tokenType == null) {
                        end = source.length
                        break
                    }
                    if (lexer.tokenType === SlangLexer.LBRACE) {
                        depth++
                    } else if (lexer.tokenType === SlangLexer.RBRACE) {
                        depth--
                    }
                    end = lexer.tokenEnd
                }
                text = source.substring(start, end)
            }

            var closesControl = false
            var braceKind = BraceKind.NONE
            pendingDeclaration =
                when (text) {
                    "namespace" -> BraceKind.NAMESPACE
                    "cbuffer",
                    "tbuffer" -> BraceKind.BUFFER
                    "struct" -> BraceKind.STRUCT
                    "class" -> BraceKind.CLASS
                    "enum" -> BraceKind.ENUM
                    "interface",
                    "extension" -> BraceKind.TYPE
                    else -> pendingDeclaration
                }

            if (text == "=") {
                statementAssignment = true
            }

            if (text == ")") {
                parentheses = (parentheses - 1).coerceAtLeast(0)
                val control = parenStack.removeLastOrNull()?.control.orEmpty()
                closesControl = control.isNotEmpty()

                if (parentheses == 0) {
                    pendingSwitch = control == "switch"
                    if (!closesControl && !statementAssignment) {
                        pendingFunction = true
                    }
                }
            }

            if (text == "}") {
                braces = (braces - 1).coerceAtLeast(0)
                braceStack.removeLastOrNull()?.let {
                    parentheses = it.parentheses
                    braceKind = it.kind
                }

                if (braceKind == BraceKind.NAMESPACE) {
                    namespaces--
                }
                unbraced = 0
                if (braces < caseDepth) {
                    caseDepth = -1
                }
            }

            if (text == "case" || text == "default") {
                caseLabel = true
                caseDepth = braces
            }
            val labelColon = caseLabel && text == ":"
            val unindentedNamespaces =
                when (custom.NAMESPACE_INDENTATION) {
                    1 -> namespaces
                    2 -> namespaces.coerceAtMost(1)
                    else -> 0
                }
            var columns =
                (braces - unindentedNamespaces) * indent.INDENT_SIZE + parentheses * indent.CONTINUATION_INDENT_SIZE

            if (unbraced > 0 && (text != "{") && (text != "else")) {
                columns += indent.INDENT_SIZE
            }
            if (caseDepth == braces && !caseLabel && (text != "}")) {
                columns += indent.INDENT_SIZE
            }
            if (caseDepth == braces && text != "}") {
                if (!common.INDENT_CASE_FROM_SWITCH) {
                    columns -= indent.INDENT_SIZE
                }
                if (text == "break" && !common.INDENT_BREAK_FROM_CASE) {
                    columns -= indent.INDENT_SIZE
                }
            }

            val prefix =
                text == "!" || text == "~" ||
                    (text in PREFIX_OPERATORS &&
                        (previous == null ||
                            before in PREFIX_CONTEXTS ||
                            previous.prefix ||
                            previous.type === SlangLexer.OPERATOR &&
                                !previous.angle &&
                                before !in INCREMENT_OPERATORS))
            var wrap: Wrap? = null
            var alignment: Alignment? = null
            val paren = parenStack.lastOrNull()

            if ((before == "," || before == "(") && text != ")" && paren != null) {
                wrap = paren.wrap
                alignment = paren.alignment
            } else if (before == "=" && common.ASSIGNMENT_WRAP != 0) {
                wrap = Wrap.createWrap(WrapType.byLegacyRepresentation(common.ASSIGNMENT_WRAP), true)
            }

            if (text == "{") {
                braceKind = when {
                    pendingDeclaration != BraceKind.NONE -> pendingDeclaration
                    previous?.labelColon == true -> BraceKind.CASE
                    pendingSwitch -> BraceKind.SWITCH
                    before == "=" -> BraceKind.INITIALIZER
                    pendingFunction -> BraceKind.FUNCTION
                    else -> BraceKind.CONTROL
                }
            }

            children.add(
                Leaf(
                    text,
                    type,
                    TextRange(start, end),
                    if (directive) 0 else columns,
                    parentheses,
                    braces,
                    braceKind,
                    braces == 0 || braceStack.lastOrNull()?.kind?.isDeclaration == true,
                    directive,
                    angle,
                    prefix,
                    labelColon,
                    preserveGap,
                    if (preserveGap) null else wrap,
                    if (preserveGap) null else alignment,
                ),
            )

            if (text == "{") {
                if (braceKind == BraceKind.FUNCTION && braces == 0) {
                    var first = children.size - 2
                    while (first >= 0) {
                        val token = children[first]
                        if (token.text == ";" || token.text == "}" ||
                            token.directive ||
                            token.type === SlangLexer.COMMENT ||
                            token.type === SlangLexer.BLOCK_COMMENT) {
                            break
                        }
                        first--
                    }
                    functionStarts.add(children[first + 1].range.startOffset)
                }

                braces++
                if (braceKind == BraceKind.NAMESPACE) {
                    namespaces++
                }
                braceStack.addLast(Brace(parentheses, braceKind))
                parentheses = 0
                unbraced = 0
            } else if (text == "(") {
                parentheses++
                val control = before.takeIf { it in CONTROL }.orEmpty()
                val call = control.isEmpty() && IDENTIFIER.matches(before)
                val callWrap =
                    if (call && common.CALL_PARAMETERS_WRAP != 0) {
                        Wrap.createWrap(WrapType.byLegacyRepresentation(common.CALL_PARAMETERS_WRAP), true)
                    } else {
                        null
                    }
                val callAlignment =
                    if (call && common.ALIGN_MULTILINE_PARAMETERS_IN_CALLS) {
                        Alignment.createAlignment()
                    } else {
                        null
                    }

                parenStack.addLast(Parenthesis(control, callWrap, callAlignment))
            } else if (closesControl) {
                var next = end
                while (next < source.length && Character.isWhitespace(source[next])) {
                    next++
                }
                if (next < source.length && source[next] != '{' && source[next] != ';') {
                    unbraced++
                }
            } else if (text == ";" && parentheses == 0) {
                unbraced = 0
            }

            if (text in STATEMENT_BOUNDARIES) {
                pendingDeclaration = BraceKind.NONE
                pendingFunction = false
                pendingSwitch = false
                statementAssignment = false
            }

            if (labelColon) {
                caseLabel = false
            }

            do {
                lexer.advance()
            } while (lexer.tokenType != null && lexer.tokenStart < end)
        }
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 !is Leaf || child2 !is Leaf) {
            return null
        }

        if (child2.preserveGap) {
            return Spacing.getReadOnlySpacing()
        }

        val a = child1.text
        val b = child2.text
        val keptBlankLines =
            if (child1.declarationContext) common.KEEP_BLANK_LINES_IN_DECLARATIONS else common.KEEP_BLANK_LINES_IN_CODE

        if (child1.directive || child2.directive || child1.type === SlangLexer.COMMENT) {
            return lines(1, keptBlankLines)
        }
        if (child2.type === SlangLexer.COMMENT ||
            child2.type === SlangLexer.BLOCK_COMMENT ||
            child1.type === SlangLexer.BLOCK_COMMENT
        ) {
            return spaces(1, true)
        }

        if (child2.range.startOffset in functionStarts && common.BLANK_LINES_AROUND_METHOD > 0) {
            return lines(common.BLANK_LINES_AROUND_METHOD + 1, common.KEEP_BLANK_LINES_IN_DECLARATIONS)
        }
        if (a == "{" && b == "}") {
            return spaces(0, false)
        }
        if (b == "{") {
            return when (bracePlacement(child2.braceKind)) {
                2 -> line()
                3 -> spaces(0, false)
                else -> spaces(1, false)
            }
        }
        if (b == "}") {
            return lines(1, common.KEEP_BLANK_LINES_BEFORE_RBRACE)
        }
        if (a == "{") {
            return lines(1, if (custom.KEEP_BLANK_LINES_AFTER_LBRACE) common.KEEP_BLANK_LINES_IN_CODE else 0)
        }
        if (a == ";") {
            return if (child1.parentheses == 0) {
                lines(1, keptBlankLines)
            } else {
                spaces(if (common.SPACE_AFTER_SEMICOLON) 1 else 0, false)
            }
        }
        if (child1.labelColon) {
            return line()
        }
        if (child2.labelColon) {
            return spaces(0, false)
        }
        if (a == "}") {
            if (b in ATTACHED_AFTER_BRACE) {
                return spaces(0, false)
            }

            when (b) {
                "else" -> {
                    return if (common.ELSE_ON_NEW_LINE) line() else spaces(1, false)
                }

                "catch" -> {
                    return if (common.CATCH_ON_NEW_LINE) line() else spaces(1, false)
                }

                "while" -> {
                    return if (common.WHILE_ON_NEW_LINE) line() else spaces(1, false)
                }

            }

            if (child1.braceKind == BraceKind.FUNCTION && child1.braces == 0) {
                return lines(common.BLANK_LINES_AROUND_METHOD + 1, common.KEEP_BLANK_LINES_IN_DECLARATIONS)
            }

            return lines(1, keptBlankLines)
        }

        if (b == ",") {
            return spaces(if (common.SPACE_BEFORE_COMMA) 1 else 0, false)
        }
        if (a == "(" || b == ")") {
            return spaces(if (a == "(" && b == ")") 0 else if (common.SPACE_WITHIN_PARENTHESES) 1 else 0, false)
        }
        if (a == "[" || b == "]") {
            return spaces(if (a == "[" && b == "]") 0 else if (common.SPACE_WITHIN_BRACKETS) 1 else 0, false)
        }
        if (b == ";" || b in MEMBER_OPERATORS || a in MEMBER_OPERATORS) {
            return spaces(0, false)
        }
        if (b == "(") {
            return spaces(if (spaceBeforeParentheses(a)) 1 else 0, false)
        }

        // Keep shader attribute lines separate.
        if (a == "]" &&
            (child1.range.endOffset..<child2.range.startOffset).any { source[it] == '\n' }
        ) {
            return line()
        }

        if (b == "[") {
            return spaces(if (a == "]") 1 else 0, true)
        }
        if (a == ",") {
            return spaces(if (common.SPACE_AFTER_COMMA) 1 else 0, true)
        }
        if (child2.angle && b == "<") {
            return spaces(0, false)
        }
        if (child1.angle && a == "<" || child2.angle && b == ">") {
            val empty = child1.angle && child2.angle && a == "<" && b == ">"
            val nestedClosers = child1.angle && child2.angle && a == ">"
            return spaces(if (custom.SPACE_WITHIN_GENERIC_ANGLES && !empty && !nestedClosers) 1 else 0, false)
        }
        if (child1.angle && a == ">") {
            return spaces(if (b == ">") 0 else 1, true)
        }
        if (child1.prefix) {
            val space = child2.type === SlangLexer.OPERATOR || a == "!" && custom.SPACE_AFTER_LOGICAL_NOT
            return spaces(if (space) 1 else 0, false)
        }
        if (b in INCREMENT_OPERATORS && !child2.prefix) {
            return spaces(0, false)
        }

        // Pointer declarations and dereferences need semantic information.
        // Preserve ambiguous gaps rather than inventing a different token sequence.
        if (a in POINTER_OPERATORS || b in POINTER_OPERATORS) {
            return Spacing.getReadOnlySpacing()
        }
        if (child1.type === SlangLexer.OPERATOR || child2.type === SlangLexer.OPERATOR) {
            var space =
                operatorSpace(if (child1.type === SlangLexer.OPERATOR) a else b, child1.type !== SlangLexer.OPERATOR)
            // Removing whitespace between separate operators may create ++, --, etc.
            if (child1.type === SlangLexer.OPERATOR && child2.type === SlangLexer.OPERATOR) {
                space = true
            }
            return spaces(if (space) 1 else 0, true)
        }
        return spaces(1, true)
    }

    private fun bracePlacement(kind: BraceKind): Int {
        val value =
            when (kind) {
                BraceKind.FUNCTION -> custom.FUNCTION_BRACES
                BraceKind.TYPE -> custom.TYPE_BRACES
                BraceKind.STRUCT -> inherit(custom.STRUCT_BRACES, custom.TYPE_BRACES)
                BraceKind.CLASS -> inherit(custom.CLASS_BRACES, custom.TYPE_BRACES)
                BraceKind.ENUM -> inherit(custom.ENUM_BRACES, custom.TYPE_BRACES)
                BraceKind.NAMESPACE -> inherit(custom.NAMESPACE_BRACES, custom.TYPE_BRACES)
                BraceKind.BUFFER -> inherit(custom.BUFFER_BRACES, custom.TYPE_BRACES)
                BraceKind.SWITCH -> inherit(custom.SWITCH_BRACES, custom.CONTROL_BRACES)
                BraceKind.CASE -> inherit(custom.CASE_BRACES, custom.CONTROL_BRACES)
                BraceKind.INITIALIZER -> inherit(custom.INITIALIZER_BRACES, custom.CONTROL_BRACES)
                else -> custom.CONTROL_BRACES
            }
        return inherit(value, if (newLineBraces) 2 else 1)
    }

    private fun spaceBeforeParentheses(token: String): Boolean {
        return when (token) {
            "if" -> common.SPACE_BEFORE_IF_PARENTHESES
            "for" -> common.SPACE_BEFORE_FOR_PARENTHESES
            "while" -> common.SPACE_BEFORE_WHILE_PARENTHESES
            "switch" -> common.SPACE_BEFORE_SWITCH_PARENTHESES
            "catch" -> common.SPACE_BEFORE_CATCH_PARENTHESES
            else -> common.SPACE_BEFORE_METHOD_CALL_PARENTHESES
        }
    }

    private fun operatorSpace(token: String, before: Boolean): Boolean {
        if (token in ASSIGNMENTS) {
            return if (custom.SPACE_BEFORE_ASSIGNMENT < 0) {
                common.SPACE_AROUND_ASSIGNMENT_OPERATORS
            } else {
                !before || custom.SPACE_BEFORE_ASSIGNMENT == 1
            }
        }

        return when (token) {
            "&&",
            "||" -> common.SPACE_AROUND_LOGICAL_OPERATORS
            "==",
            "!=" -> common.SPACE_AROUND_EQUALITY_OPERATORS
            "<",
            ">",
            "<=",
            ">=" -> common.SPACE_AROUND_RELATIONAL_OPERATORS
            "+",
            "-" -> common.SPACE_AROUND_ADDITIVE_OPERATORS
            "<<",
            ">>" -> common.SPACE_AROUND_SHIFT_OPERATORS
            else -> true
        }
    }

    private fun spaces(count: Int, keepBreaks: Boolean): Spacing? {
        return Spacing.createSpacing(
            count,
            count,
            0,
            keepBreaks && common.KEEP_LINE_BREAKS,
            common.KEEP_BLANK_LINES_IN_CODE,
        )
    }

    private fun line(): Spacing? {
        return lines(1, common.KEEP_BLANK_LINES_IN_CODE)
    }

    override fun getTextRange(): TextRange {
        return TextRange(0, source.length)
    }

    override fun getLanguage(): Language {
        return SlangLanguage.INSTANCE
    }

    override fun getSubBlocks(): List<Block> {
        return children
    }

    override fun getWrap(): Wrap? {
        return null
    }

    override fun getIndent(): Indent? {
        return Indent.getAbsoluteNoneIndent()
    }

    override fun getAlignment(): Alignment? {
        return null
    }

    override fun getChildAttributes(index: Int): ChildAttributes {
        return ChildAttributes(Indent.getNoneIndent(), null)
    }

    override fun isIncomplete(): Boolean {
        return false
    }

    override fun isLeaf(): Boolean {
        return children.isEmpty()
    }

    private data class Leaf(
        val text: String,
        val type: IElementType,
        val range: TextRange,
        val columns: Int,
        val parentheses: Int,
        val braces: Int,
        val braceKind: BraceKind,
        val declarationContext: Boolean,
        val directive: Boolean,
        val angle: Boolean,
        val prefix: Boolean,
        val labelColon: Boolean,
        val preserveGap: Boolean,
        private val tokenWrap: Wrap?,
        private val tokenAlignment: Alignment?,
    ) : BlockEx {
        override fun getTextRange(): TextRange {
            return range
        }

        override fun getLanguage(): Language {
            return SlangLanguage.INSTANCE
        }

        override fun getSubBlocks(): List<Block> {
            return emptyList()
        }

        override fun getWrap(): Wrap? {
            return tokenWrap
        }

        override fun getIndent(): Indent? {
            return Indent.getSpaceIndent(columns, true)
        }

        override fun getAlignment(): Alignment? {
            return tokenAlignment
        }

        override fun getSpacing(left: Block?, right: Block): Spacing? {
            return null
        }

        override fun getChildAttributes(index: Int): ChildAttributes {
            return ChildAttributes(Indent.getNoneIndent(), null)
        }

        override fun isIncomplete(): Boolean {
            return false
        }

        override fun isLeaf(): Boolean {
            return true
        }
    }

    companion object {
        private val CONTROL = setOf("if", "for", "while", "switch", "catch")
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z_0-9]*")
        private val PREFIX_OPERATORS = setOf("+", "-", "++", "--", "*", "&")
        private val PREFIX_CONTEXTS = setOf("(", "[", "{", "=", ",", ";", "return", ":", "?")
        private val INCREMENT_OPERATORS = setOf("++", "--")
        private val STATEMENT_BOUNDARIES = setOf(";", "{", "}")
        private val ATTACHED_AFTER_BRACE = setOf(";", ",", ")", "]", ".", "(", "::")
        private val MEMBER_OPERATORS = setOf(".", "::", "->")
        private val POINTER_OPERATORS = setOf("*", "&")
        private val ASSIGNMENTS =
            setOf("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=")
        private val FORMAT_MARKER = Regex("""^(?://|/\*)\s*clang-format (off|on)\b""")

        private val OPERATORS =
            setOf(
                "==",
                "!=",
                "<=",
                ">=",
                "&&",
                "||",
                "++",
                "--",
                "+=",
                "-=",
                "*=",
                "/=",
                "%=",
                "&=",
                "|=",
                "^=",
                "<<",
                ">>",
                "<<=",
                ">>=",
                "->",
                "::",
            )

        private fun directiveEnd(source: String, start: Int): Int {
            var end = start

            while (end < source.length) {
                val newline = source.indexOf('\n', end)
                if (newline < 0) {
                    return source.length
                }
                var last = newline - 1
                if (last >= start && source[last] == '\r') {
                    last--
                }
                if (last < start || source[last] != '\\') {
                    return newline
                }
                end = newline + 1
            }
            return end
        }

        private fun inherit(value: Int, fallback: Int): Int {
            return if (value == 0) fallback else value
        }

        private fun lines(count: Int, keepBlankLines: Int): Spacing? {
            return Spacing.createSpacing(0, 0, count.coerceAtLeast(1), true, keepBlankLines.coerceAtLeast(0))
        }
    }
}
