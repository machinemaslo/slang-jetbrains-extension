package slanglsp.formatting.native

import com.intellij.formatting.*
import com.intellij.lang.Language
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.tree.IElementType
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min
import slanglsp.language.SlangFileType
import slanglsp.language.SlangLanguage
import slanglsp.language.SlangLexer
import slanglsp.language.generics.GenericContext
import slanglsp.settings.codestyle.SlangCodeStyleSettings

/**
 * The parser exposes token-level PSI. Virtual formatting blocks retain exact token ranges, so formatting never rewrites
 * strings, comments, directives, or the PSI structure.
 */
internal open class NativeFormattingBlock(private val source: String, settings: CodeStyleSettings) : BlockEx {
    private val children: MutableList<Block?> = ArrayList()
    private val newLineBraces: Boolean
    private val common: CommonCodeStyleSettings = settings.getCommonSettings(SlangLanguage.INSTANCE)
    private val custom: SlangCodeStyleSettings =
        settings.getCustomSettings<SlangCodeStyleSettings>(SlangCodeStyleSettings::class.java)
    private val functionStarts: MutableSet<Int?> = HashSet()

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
        newLineBraces = custom.BRACES_ON_NEW_LINE
        if (!custom.DISABLE_FORMAT) {
            buildChildren(settings)
        }
    }

    private fun buildChildren(settings: CodeStyleSettings) {
        val indent = settings.getIndentOptions(SlangFileType)
        val generic = if (source.indexOf('<') < 0) null else GenericContext(source)
        val lexer = SlangLexer()
        lexer.start(source)
        var braces = 0
        var namespaces = 0
        var parentheses = 0
        var unbraced = 0
        val parenKinds = ArrayList<String?>()
        val parameterWraps = ArrayList<Wrap?>()
        val parameterAlignments = ArrayList<Alignment?>()
        val braceParens = ArrayList<Int?>()
        val braceKinds: ArrayList<BraceKind?> = ArrayList()
        var pendingDeclaration = BraceKind.NONE
        var pendingFunction = false
        var pendingSwitch = false
        var statementAssignment = false
        var caseLabel = false
        var formattingOff = false
        var caseDepth = -1
        while (lexer.tokenType != null) {
            ProgressManager.checkCanceled()
            val type = lexer.tokenType
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
                val marker: Matcher = FORMAT_MARKER.matcher(text)
                if (marker.find()) {
                    formattingOff = marker.group(1) == "off"
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
                    if (OPERATORS.contains(operator)) {
                        text = operator
                        end = start + length
                        break
                    }
                }
            }
            val previous = children.lastOrNull() as Leaf?
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
                parentheses = max(0, parentheses - 1)
                val control: String = (if (parenKinds.isEmpty()) "" else parenKinds.removeLast())!!
                closesControl = control.isNotEmpty()
                if (parameterWraps.isNotEmpty()) {
                    parameterWraps.removeLast()
                }
                if (parameterAlignments.isNotEmpty()) {
                    parameterAlignments.removeLast()
                }
                if (parentheses == 0) {
                    pendingSwitch = control == "switch"
                    if (!closesControl && !statementAssignment) {
                        pendingFunction = true
                    }
                }
            }
            if (text == "}") {
                braces = max(0, braces - 1)
                if (braceParens.isNotEmpty()) {
                    parentheses = braceParens.removeLast()!!
                }
                if (braceKinds.isNotEmpty()) {
                    braceKind = braceKinds.removeLast()!!
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
                    2 -> min(1, namespaces)
                    else -> 0
                }
            var columns =
                ((braces - unindentedNamespaces) * indent.INDENT_SIZE + parentheses * indent.CONTINUATION_INDENT_SIZE)
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
                mutableSetOf<String?>("!", "~").contains(text) ||
                    (mutableSetOf<String?>("+", "-", "++", "--", "*", "&").contains(text) &&
                        (previous == null ||
                            mutableSetOf<String?>("(", "[", "{", "=", ",", ";", "return", ":", "?").contains(before) ||
                            previous.prefix ||
                            previous.type === SlangLexer.OPERATOR &&
                                !previous.angle &&
                                !mutableSetOf<String?>("++", "--").contains(before)))
            var wrap: Wrap? = null
            var alignment: Alignment? = null
            if ((before == "," || before == "(") && (text != ")") && parameterWraps.isNotEmpty()) {
                wrap = parameterWraps.last()
                alignment = parameterAlignments.last()
            } else if (before == "=" && common.ASSIGNMENT_WRAP != 0) {
                wrap = Wrap.createWrap(WrapType.byLegacyRepresentation(common.ASSIGNMENT_WRAP), true)
            }
            if (text == "{") {
                braceKind =
                    if (pendingDeclaration != BraceKind.NONE) {
                        pendingDeclaration
                    } else if (previous != null && previous.labelColon) {
                        BraceKind.CASE
                    } else if (pendingSwitch) {
                        BraceKind.SWITCH
                    } else if (before == "=") {
                        BraceKind.INITIALIZER
                    } else {
                        if (pendingFunction) {
                            BraceKind.FUNCTION
                        } else {
                            BraceKind.CONTROL
                        }
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
                    braces == 0 || braceKinds.isNotEmpty() && braceKinds.last()!!.isDeclaration,
                    directive,
                    angle,
                    prefix,
                    labelColon,
                    preserveGap,
                    if (preserveGap) null else wrap,
                    if (preserveGap) null else alignment))
            if (text == "{") {
                if (braceKind == BraceKind.FUNCTION && braces == 0) {
                    var first = children.size - 2
                    while (first >= 0) {
                        val token = children[first] as Leaf
                        if (mutableSetOf<String?>(";", "}").contains(token.text) ||
                            token.directive ||
                            token.type === SlangLexer.COMMENT ||
                            token.type === SlangLexer.BLOCK_COMMENT) {
                            break
                        }
                        first--
                    }
                    functionStarts.add(children[first + 1]!!.textRange.startOffset)
                }
                braces++
                if (braceKind == BraceKind.NAMESPACE) {
                    namespaces++
                }
                braceParens.add(parentheses)
                braceKinds.add(braceKind)
                parentheses = 0
                unbraced = 0
            } else if (text == "(") {
                parentheses++
                parenKinds.add(if (CONTROL.contains(before)) before else "")
                parameterWraps.add(
                    if (!CONTROL.contains(before) &&
                        before.matches("[A-Za-z_][A-Za-z_0-9]*".toRegex()) &&
                        common.CALL_PARAMETERS_WRAP != 0)
                        Wrap.createWrap(WrapType.byLegacyRepresentation(common.CALL_PARAMETERS_WRAP), true)
                    else null)
                parameterAlignments.add(
                    if (!CONTROL.contains(before) &&
                        before.matches("[A-Za-z_][A-Za-z_0-9]*".toRegex()) &&
                        common.ALIGN_MULTILINE_PARAMETERS_IN_CALLS)
                        Alignment.createAlignment()
                    else null)
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
            if (mutableSetOf<String?>(";", "{", "}").contains(text)) {
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
        val gap = source.substring(child1.range.endOffset, child2.range.startOffset)
        val keptBlankLines =
            if (child1.declarationContext) common.KEEP_BLANK_LINES_IN_DECLARATIONS else common.KEEP_BLANK_LINES_IN_CODE
        if (child1.directive || child2.directive || child1.type === SlangLexer.COMMENT) {
            return lines(1, keptBlankLines)
        }
        if (child2.type === SlangLexer.COMMENT ||
            child2.type === SlangLexer.BLOCK_COMMENT ||
            child1.type === SlangLexer.BLOCK_COMMENT) {
            return spaces(1, true)
        }
        if (functionStarts.contains(child2.range.startOffset) && common.BLANK_LINES_AROUND_METHOD > 0) {
            return lines(common.BLANK_LINES_AROUND_METHOD + 1, common.KEEP_BLANK_LINES_IN_DECLARATIONS)
        }
        if (a == "{" && b == "}") {
            return spaces(0, false)
        }
        if (b == "{") {
            return when (bracePlacement(child2.braceKind!!)) {
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
            return if (child1.parentheses == 0) lines(1, keptBlankLines)
            else spaces(if (common.SPACE_AFTER_SEMICOLON) 1 else 0, false)
        }
        if (child1.labelColon) {
            return line()
        }
        if (child2.labelColon) {
            return spaces(0, false)
        }
        if (a == "}") {
            if (mutableSetOf<String?>(";", ",", ")", "]", ".", "(", "::").contains(b)) {
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

                else -> {}
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
        if (mutableSetOf<String?>(";", ".", "::", "->").contains(b) ||
            mutableSetOf<String?>(".", "::", "->").contains(a)) {
            return spaces(0, false)
        }
        if (b == "(") {
            return spaces(if (spaceBeforeParentheses(a)) 1 else 0, false)
        }
        if (a == "]" && gap.contains("\n")) {
            return line()
        } // Keep shader attribute lines separate.

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
        if (mutableSetOf<String?>("++", "--").contains(b) && !child2.prefix) {
            return spaces(0, false)
        }
        // Pointer declarations and dereferences need semantic information.
        // Preserve ambiguous gaps rather than inventing a different token sequence.
        if (mutableSetOf<String?>("*", "&").contains(a) || mutableSetOf<String?>("*", "&").contains(b)) {
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
        if (a == "]" && gap.contains("\n")) {
            return line()
        } // Attributes preceding declarations.

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
        if (ASSIGNMENTS.contains(token)) {
            return if (custom.SPACE_BEFORE_ASSIGNMENT < 0) common.SPACE_AROUND_ASSIGNMENT_OPERATORS
            else !before || custom.SPACE_BEFORE_ASSIGNMENT == 1
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
            count, count, 0, keepBreaks && common.KEEP_LINE_BREAKS, common.KEEP_BLANK_LINES_IN_CODE)
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

    override fun getSubBlocks(): MutableList<Block?> {
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

    @JvmRecord
    private data class Leaf(
        val text: String,
        val type: IElementType?,
        val range: TextRange,
        val columns: Int,
        val parentheses: Int,
        val braces: Int,
        val braceKind: BraceKind?,
        val declarationContext: Boolean,
        val directive: Boolean,
        val angle: Boolean,
        val prefix: Boolean,
        val labelColon: Boolean,
        val preserveGap: Boolean,
        val wrap: Wrap?,
        val alignment: Alignment?
    ) : BlockEx {
        override fun getTextRange(): TextRange {
            return range
        }

        override fun getLanguage(): Language {
            return SlangLanguage.INSTANCE
        }

        override fun getSubBlocks(): MutableList<Block?> {
            return mutableListOf()
        }

        override fun getWrap(): Wrap? {
            return wrap
        }

        override fun getIndent(): Indent? {
            return Indent.getSpaceIndent(columns, true)
        }

        override fun getAlignment(): Alignment? {
            return alignment
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
        private val CONTROL = mutableSetOf<String?>("if", "for", "while", "switch", "catch")
        private val ASSIGNMENTS =
            mutableSetOf<String?>("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=")
        private val FORMAT_MARKER: Pattern = Pattern.compile("^(?://|/\\*)\\s*clang-format (off|on)\\b")
        private val OPERATORS =
            mutableSetOf<String?>(
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
                "::")

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
            return Spacing.createSpacing(0, 0, max(1, count), true, max(0, keepBlankLines))
        }
    }
}
