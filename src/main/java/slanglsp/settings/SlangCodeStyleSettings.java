package slanglsp.settings;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;

public final class SlangCodeStyleSettings extends CustomCodeStyleSettings {
    public boolean BRACES_ON_NEW_LINE = true;

    public SlangCodeStyleSettings(CodeStyleSettings container) {
        super("SlangCodeStyleSettings", container);
    }
}
