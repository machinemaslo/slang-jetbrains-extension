package slanglsp.settings;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.PredefinedCodeStyle;
import slanglsp.language.SlangLanguage;

public final class CodeStylePresets {
    private CodeStylePresets() {
    }

    private static void apply(CodeStyleSettings settings) {
        var common = settings.getCommonSettings(SlangLanguage.INSTANCE);
        var indent = common.initIndentOptions();
        common.RIGHT_MARGIN = 100;
        indent.INDENT_SIZE = 4;
        indent.CONTINUATION_INDENT_SIZE = 4;
        indent.TAB_SIZE = 4;
        indent.USE_TAB_CHARACTER = false;
        var custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
        custom.BRACES_ON_NEW_LINE = true;
        custom.resetBraceOverrides();
        common.ELSE_ON_NEW_LINE = common.CATCH_ON_NEW_LINE = common.WHILE_ON_NEW_LINE = true;
        custom.setNamingRules(NamingRule.slang());
        custom.NAMING_ABBREVIATIONS = NamingRule.ABBREVIATIONS;
    }

    public static final class Slang extends PredefinedCodeStyle {
        public Slang() {
            super("Slang", SlangLanguage.INSTANCE);
        }

        @Override
        public void apply(CodeStyleSettings settings) {
            CodeStylePresets.apply(settings);
        }
    }
}
