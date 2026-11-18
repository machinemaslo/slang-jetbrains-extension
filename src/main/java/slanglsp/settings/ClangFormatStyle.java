package slanglsp.settings;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import slanglsp.language.SlangLanguage;

/** clang-format defaults from shader-slang/slang v2026.22's .clang-format. */
public final class ClangFormatStyle {
    private ClangFormatStyle() {
    }

    private static final String BASE = """
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
            """.replace('\n', ' ').trim();

    public static final String DEFAULT_STYLE = style(4, 4, 4, false, 100, true);

    public static String clangFormatStyle(CodeStyleSettings settings) {
        var indent = settings.getIndentOptions(slanglsp.language.SlangFileType.INSTANCE);
        var custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
        return style(indent.INDENT_SIZE, indent.CONTINUATION_INDENT_SIZE, indent.TAB_SIZE,
                indent.USE_TAB_CHARACTER, settings.getRightMargin(SlangLanguage.INSTANCE), custom.BRACES_ON_NEW_LINE);
    }

    /** Export only options with a meaningful mapping, using keys accepted by clang-format. */
    public static String export(CodeStyleSettings settings) {
        var indent = settings.getIndentOptions(slanglsp.language.SlangFileType.INSTANCE);
        var common = settings.getCommonSettings(SlangLanguage.INSTANCE);
        var custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
        var yaml = new StringBuilder();
        option(yaml, "BasedOnStyle", "LLVM");
        if (custom.DISABLE_FORMAT) {
            option(yaml, "DisableFormat", true);
        }
        option(yaml, "IndentWidth", indent.INDENT_SIZE);
        option(yaml, "ContinuationIndentWidth", indent.CONTINUATION_INDENT_SIZE);
        option(yaml, "TabWidth", indent.TAB_SIZE);
        option(yaml, "UseTab", indent.USE_TAB_CHARACTER ? "ForIndentation" : "Never");
        int margin = settings.getRightMargin(SlangLanguage.INSTANCE);
        option(yaml, "ColumnLimit", margin == Integer.MAX_VALUE ? 0 : margin);
        option(yaml, "IndentCaseLabels", common.INDENT_CASE_FROM_SWITCH);
        option(yaml, "NamespaceIndentation", switch (custom.NAMESPACE_INDENTATION) {
            case 1 -> "None";
            case 2 -> "Inner";
            default -> "All";
        });
        option(yaml, "MaxEmptyLinesToKeep", common.KEEP_BLANK_LINES_IN_CODE);
        option(yaml, "KeepEmptyLinesAtTheStartOfBlocks", custom.KEEP_BLANK_LINES_AFTER_LBRACE);
        option(yaml, "SpaceBeforeAssignmentOperators", custom.SPACE_BEFORE_ASSIGNMENT < 0
                ? common.SPACE_AROUND_ASSIGNMENT_OPERATORS : custom.SPACE_BEFORE_ASSIGNMENT == 1);
        option(yaml, "SpacesInSquareBrackets", common.SPACE_WITHIN_BRACKETS);
        option(yaml, "SpacesInParentheses", common.SPACE_WITHIN_PARENTHESES);
        option(yaml, "SpacesInAngles", custom.SPACE_WITHIN_GENERIC_ANGLES ? "Always" : "Never");
        option(yaml, "SpaceAfterLogicalNot", custom.SPACE_AFTER_LOGICAL_NOT);
        boolean controls = common.SPACE_BEFORE_IF_PARENTHESES;
        if (controls == common.SPACE_BEFORE_FOR_PARENTHESES && controls == common.SPACE_BEFORE_WHILE_PARENTHESES
                && controls == common.SPACE_BEFORE_SWITCH_PARENTHESES && controls == common.SPACE_BEFORE_CATCH_PARENTHESES) {
            if (controls || !common.SPACE_BEFORE_METHOD_CALL_PARENTHESES) {
                option(yaml, "SpaceBeforeParens", controls
                        ? common.SPACE_BEFORE_METHOD_CALL_PARENTHESES ? "Always" : "ControlStatements" : "Never");
            }
        }
        option(yaml, "SpaceBeforeCpp11BracedList", custom.INITIALIZER_BRACES != 3);
        option(yaml, "BreakBeforeBraces", "Custom");
        yaml.append("BraceWrapping:\n");
        brace(yaml, "AfterFunction", startsNewLine(custom, custom.FUNCTION_BRACES, 0));
        brace(yaml, "AfterNamespace", startsNewLine(custom, custom.NAMESPACE_BRACES, custom.TYPE_BRACES));
        brace(yaml, "AfterStruct", startsNewLine(custom, custom.STRUCT_BRACES, custom.TYPE_BRACES));
        brace(yaml, "AfterClass", startsNewLine(custom, custom.CLASS_BRACES, custom.TYPE_BRACES));
        brace(yaml, "AfterEnum", startsNewLine(custom, custom.ENUM_BRACES, custom.TYPE_BRACES));
        brace(yaml, "AfterCaseLabel", startsNewLine(custom, custom.CASE_BRACES, custom.CONTROL_BRACES));
        brace(yaml, "AfterControlStatement", startsNewLine(custom, custom.CONTROL_BRACES, 0) ? "Always" : "Never");
        brace(yaml, "BeforeElse", common.ELSE_ON_NEW_LINE);
        brace(yaml, "BeforeCatch", common.CATCH_ON_NEW_LINE);
        brace(yaml, "BeforeWhile", common.WHILE_ON_NEW_LINE);
        return yaml.toString();
    }

    private static boolean startsNewLine(SlangCodeStyleSettings settings, int placement, int inherited) {
        int effective = placement == 0 ? inherited : placement;
        return effective == 0 ? settings.BRACES_ON_NEW_LINE : effective == 2;
    }

    private static void option(StringBuilder yaml, String key, Object value) {
        yaml.append(key).append(": ").append(value).append('\n');
    }

    private static void brace(StringBuilder yaml, String key, Object value) {
        yaml.append("  ");
        option(yaml, key, value);
    }

    private static String style(int indent, int continuation, int tab, boolean tabs, int margin, boolean newLine) {
        return "{" + BASE + ", IndentWidth: " + indent + ", ContinuationIndentWidth: " + continuation
                + ", TabWidth: " + tab + ", UseTab: " + (tabs ? "ForIndentation" : "Never")
                + ", ColumnLimit: " + margin + ", BreakBeforeBraces: " + (newLine ? "Allman" : "Attach") + "}";
    }
}
