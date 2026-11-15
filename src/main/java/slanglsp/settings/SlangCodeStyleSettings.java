package slanglsp.settings;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;

public final class SlangCodeStyleSettings extends CustomCodeStyleSettings {
    public boolean BRACES_ON_NEW_LINE = true;
    // 0: follow the common brace setting, 1: same line, 2: next line.
    public int FUNCTION_BRACES = 0;
    public int TYPE_BRACES = 0;
    public int CONTROL_BRACES = 0;
    public boolean USE_CLANG_FORMAT = false;
    public boolean READ_CLANG_FORMAT = false;
    public boolean USE_CUSTOM_CLANG_FORMAT = false;
    public boolean USE_BUNDLED_CLANG_FORMAT = true;
    public String CLANG_FORMAT_PATH = "";

    public SlangCodeStyleSettings(CodeStyleSettings container) {
        super("SlangCodeStyleSettings", container);
    }
}
