package slanglsp.settings;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.PredefinedCodeStyle;
import slanglsp.language.SlangLanguage;

public final class SlangCodeStylePresets {
    private SlangCodeStylePresets() {}

    private static void apply(CodeStyleSettings settings, boolean javaStyle) {
        var common = settings.getCommonSettings(SlangLanguage.INSTANCE);
        var indent = common.initIndentOptions();
        common.RIGHT_MARGIN = 100;
        indent.INDENT_SIZE = 4;
        indent.CONTINUATION_INDENT_SIZE = javaStyle ? 8 : 4;
        indent.TAB_SIZE = javaStyle ? 4 : 8;
        indent.USE_TAB_CHARACTER = false;
        settings.getCustomSettings(SlangCodeStyleSettings.class).BRACES_ON_NEW_LINE = !javaStyle;
    }

    public static final class Slang extends PredefinedCodeStyle {
        public Slang() {
            super("Slang (upstream)", SlangLanguage.INSTANCE);
        }

        @Override
        public void apply(CodeStyleSettings settings) {
            SlangCodeStylePresets.apply(settings, false);
        }
    }

    public static final class Java extends PredefinedCodeStyle {
        public Java() {
            super("Java-style (K&R)", SlangLanguage.INSTANCE);
        }

        @Override
        public void apply(CodeStyleSettings settings) {
            SlangCodeStylePresets.apply(settings, true);
        }
    }
}
