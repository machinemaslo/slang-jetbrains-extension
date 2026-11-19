package slanglsp.formatting.clang

import com.intellij.psi.codeStyle.CodeStyleSettings
import slanglsp.language.SlangFileType
import slanglsp.language.SlangLanguage
import slanglsp.settings.codestyle.SlangCodeStyleSettings

/** clang-format defaults from shader-slang/slang v2026.22's .clang-format. */
object ClangFormatStyle {
    private val BASE =
        """
            BasedOnStyle: LLVM, MaxEmptyLinesToKeep: 2,
            AlignEscapedNewlines: Left, IncludeBlocks: Regroup,
            AllowAllArgumentsOnNextLine: false, BinPackArguments: false, BinPackParameters: false,
            AllowAllParametersOfDeclarationOnNextLine: false,
            PenaltyReturnTypeOnItsOwnLine: 9999999, PenaltyBreakBeforeFirstCallParameter: 6,
            AllowShortFunctionsOnASingleLine: Inline, AlignAfterOpenBracket: AlwaysBreak,
            PointerAlignment: Left, PackConstructorInitializers: NextLineOnly,
            BreakConstructorInitializers: BeforeComma, AccessModifierOffset: -4,
            IndentCaseBlocks: true, SpaceAfterTemplateKeyword: false,
            AlwaysBreakTemplateDeclarations: true, SortUsingDeclarations: false
            
            """
            .trimIndent()
            .replace('\n', ' ')
            .trim { it <= ' ' }

    @JvmField val DEFAULT_STYLE: String = style(4, 4, 4, false, 100, true)

    @JvmStatic
    fun clangFormatStyle(settings: CodeStyleSettings): String {
        val indent = settings.getIndentOptions(SlangFileType)
        val custom = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
        return style(
            indent.INDENT_SIZE,
            indent.CONTINUATION_INDENT_SIZE,
            indent.TAB_SIZE,
            indent.USE_TAB_CHARACTER,
            settings.getRightMargin(SlangLanguage.INSTANCE),
            custom.BRACES_ON_NEW_LINE)
    }

    /** Export only options with a meaningful mapping, using keys accepted by clang-format. */
    @JvmStatic
    fun export(settings: CodeStyleSettings): String {
        val indent = settings.getIndentOptions(SlangFileType)
        val common = settings.getCommonSettings(SlangLanguage.INSTANCE)
        val custom = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
        val yaml = StringBuilder()
        option(yaml, "BasedOnStyle", "LLVM")
        if (custom.DISABLE_FORMAT) {
            option(yaml, "DisableFormat", true)
        }
        option(yaml, "IndentWidth", indent.INDENT_SIZE)
        option(yaml, "ContinuationIndentWidth", indent.CONTINUATION_INDENT_SIZE)
        option(yaml, "TabWidth", indent.TAB_SIZE)
        option(yaml, "UseTab", if (indent.USE_TAB_CHARACTER) "ForIndentation" else "Never")
        val margin = settings.getRightMargin(SlangLanguage.INSTANCE)
        option(yaml, "ColumnLimit", if (margin == Int.MAX_VALUE) 0 else margin)
        option(yaml, "IndentCaseLabels", common.INDENT_CASE_FROM_SWITCH)
        option(
            yaml,
            "NamespaceIndentation",
            when (custom.NAMESPACE_INDENTATION) {
                1 -> "None"
                2 -> "Inner"
                else -> "All"
            })
        option(yaml, "MaxEmptyLinesToKeep", common.KEEP_BLANK_LINES_IN_CODE)
        option(yaml, "KeepEmptyLinesAtTheStartOfBlocks", custom.KEEP_BLANK_LINES_AFTER_LBRACE)
        option(
            yaml,
            "SpaceBeforeAssignmentOperators",
            if (custom.SPACE_BEFORE_ASSIGNMENT < 0) common.SPACE_AROUND_ASSIGNMENT_OPERATORS
            else custom.SPACE_BEFORE_ASSIGNMENT == 1)
        option(yaml, "SpacesInSquareBrackets", common.SPACE_WITHIN_BRACKETS)
        option(yaml, "SpacesInParentheses", common.SPACE_WITHIN_PARENTHESES)
        option(yaml, "SpacesInAngles", if (custom.SPACE_WITHIN_GENERIC_ANGLES) "Always" else "Never")
        option(yaml, "SpaceAfterLogicalNot", custom.SPACE_AFTER_LOGICAL_NOT)
        val controls = common.SPACE_BEFORE_IF_PARENTHESES
        if (controls == common.SPACE_BEFORE_FOR_PARENTHESES &&
            controls == common.SPACE_BEFORE_WHILE_PARENTHESES &&
            controls == common.SPACE_BEFORE_SWITCH_PARENTHESES &&
            controls == common.SPACE_BEFORE_CATCH_PARENTHESES) {
            if (controls || !common.SPACE_BEFORE_METHOD_CALL_PARENTHESES) {
                option(
                    yaml,
                    "SpaceBeforeParens",
                    if (controls) if (common.SPACE_BEFORE_METHOD_CALL_PARENTHESES) "Always" else "ControlStatements"
                    else "Never")
            }
        }
        option(yaml, "SpaceBeforeCpp11BracedList", custom.INITIALIZER_BRACES != 3)
        option(yaml, "BreakBeforeBraces", "Custom")
        yaml.append("BraceWrapping:\n")
        brace(yaml, "AfterFunction", startsNewLine(custom, custom.FUNCTION_BRACES, 0))
        brace(yaml, "AfterNamespace", startsNewLine(custom, custom.NAMESPACE_BRACES, custom.TYPE_BRACES))
        brace(yaml, "AfterStruct", startsNewLine(custom, custom.STRUCT_BRACES, custom.TYPE_BRACES))
        brace(yaml, "AfterClass", startsNewLine(custom, custom.CLASS_BRACES, custom.TYPE_BRACES))
        brace(yaml, "AfterEnum", startsNewLine(custom, custom.ENUM_BRACES, custom.TYPE_BRACES))
        brace(yaml, "AfterCaseLabel", startsNewLine(custom, custom.CASE_BRACES, custom.CONTROL_BRACES))
        brace(yaml, "AfterControlStatement", if (startsNewLine(custom, custom.CONTROL_BRACES, 0)) "Always" else "Never")
        brace(yaml, "BeforeElse", common.ELSE_ON_NEW_LINE)
        brace(yaml, "BeforeCatch", common.CATCH_ON_NEW_LINE)
        brace(yaml, "BeforeWhile", common.WHILE_ON_NEW_LINE)
        return yaml.toString()
    }

    private fun startsNewLine(settings: SlangCodeStyleSettings, placement: Int, inherited: Int): Boolean {
        val effective = if (placement == 0) inherited else placement
        return if (effective == 0) settings.BRACES_ON_NEW_LINE else effective == 2
    }

    private fun option(yaml: StringBuilder, key: String, value: Any) {
        yaml.append(key).append(": ").append(value).append('\n')
    }

    private fun brace(yaml: StringBuilder, key: String, value: Any) {
        yaml.append("  ")
        option(yaml, key, value)
    }

    private fun style(indent: Int, continuation: Int, tab: Int, tabs: Boolean, margin: Int, newLine: Boolean): String {
        return ("{" +
            BASE +
            ", IndentWidth: " +
            indent +
            ", ContinuationIndentWidth: " +
            continuation +
            ", TabWidth: " +
            tab +
            ", UseTab: " +
            (if (tabs) "ForIndentation" else "Never") +
            ", ColumnLimit: " +
            margin +
            ", BreakBeforeBraces: " +
            (if (newLine) "Allman" else "Attach") +
            "}")
    }
}
