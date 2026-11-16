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

    private static String style(int indent, int continuation, int tab, boolean tabs, int margin, boolean newLine) {
        return "{" + BASE + ", IndentWidth: " + indent + ", ContinuationIndentWidth: " + continuation
                + ", TabWidth: " + tab + ", UseTab: " + (tabs ? "ForIndentation" : "Never")
                + ", ColumnLimit: " + margin + ", BreakBeforeBraces: " + (newLine ? "Allman" : "Attach") + "}";
    }
}
