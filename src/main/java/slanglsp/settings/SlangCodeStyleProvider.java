package slanglsp.settings;

import com.intellij.application.options.CodeStyleAbstractConfigurable;
import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.application.options.IndentOptionsEditor;
import com.intellij.application.options.SmartIndentOptionsEditor;
import com.intellij.application.options.TabbedLanguageCodeStylePanel;
import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.*;
import org.jetbrains.annotations.NotNull;
import slanglsp.language.SlangLanguage;
import slanglsp.SlangBundle;

import javax.swing.*;
import java.awt.BorderLayout;

public final class SlangCodeStyleProvider extends LanguageCodeStyleSettingsProvider {
    @Override
    public @NotNull Language getLanguage() {
        return SlangLanguage.INSTANCE;
    }

    @Override
    public CustomCodeStyleSettings createCustomSettings(CodeStyleSettings settings) {
        return new SlangCodeStyleSettings(settings);
    }

    @Override
    protected void customizeDefaults(@NotNull CommonCodeStyleSettings common,
            @NotNull CommonCodeStyleSettings.IndentOptions indent) {
        common.RIGHT_MARGIN = 100;
        indent.INDENT_SIZE = 4;
        indent.CONTINUATION_INDENT_SIZE = 4;
        indent.TAB_SIZE = 8;
        indent.USE_TAB_CHARACTER = false;
    }

    @Override
    public IndentOptionsEditor getIndentOptionsEditor() {
        return new SmartIndentOptionsEditor();
    }

    @Override
    public void customizeSettings(@NotNull CodeStyleSettingsCustomizable consumer, @NotNull SettingsType type) {
        if (type == SettingsType.INDENT_SETTINGS) {
            consumer.showStandardOptions("INDENT_SIZE", "CONTINUATION_INDENT_SIZE", "TAB_SIZE", "USE_TAB_CHARACTER");
        } else if (type == SettingsType.WRAPPING_AND_BRACES_SETTINGS) {
            consumer.showStandardOptions("RIGHT_MARGIN");
            consumer.showCustomOption(SlangCodeStyleSettings.class, "BRACES_ON_NEW_LINE",
                    SlangBundle.message("settings.code.style.braces.new.line"),
                    SlangBundle.message("settings.code.style.braces"));
        }
    }

    @Override
    public String getCodeSample(@NotNull SettingsType type) {
        return """
                struct Material
                {
                    float3 color;
                    float evaluate(float intensity)
                    {
                        if (intensity > 0)
                        {
                            return intensity;
                        }
                        return 0;
                    }
                };
                """;
    }

    @Override
    public @NotNull CodeStyleConfigurable createConfigurable(@NotNull CodeStyleSettings base,
            @NotNull CodeStyleSettings model) {
        return new CodeStyleAbstractConfigurable(base, model, "Slang") {
            @Override
            protected @NotNull CodeStyleAbstractPanel createPanel(@NotNull CodeStyleSettings settings) {
                var panel = new TabbedLanguageCodeStylePanel(SlangLanguage.INSTANCE, getCurrentSettings(), settings) {
                    private JPanel container;

                    @Override
                    protected void initTabs(CodeStyleSettings settings) {
                        addIndentOptionsTab(settings);
                        addWrappingAndBracesTab(settings);
                    }

                    @Override
                    public JComponent getPanel() {
                        if (container == null) {
                            container = new JPanel(new BorderLayout());
                            container.add(new JLabel(SlangBundle.message("settings.code.style.formatter.note")),
                                    BorderLayout.NORTH);
                            container.add(super.getPanel(), BorderLayout.CENTER);
                        }
                        return container;
                    }
                };
                panel.reset(settings);
                return panel;
            }
        };
    }
}
