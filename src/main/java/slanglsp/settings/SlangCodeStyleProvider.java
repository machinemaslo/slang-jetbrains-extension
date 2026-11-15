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
        common.ELSE_ON_NEW_LINE = true;
        common.CATCH_ON_NEW_LINE = true;
        common.WHILE_ON_NEW_LINE = true;
        common.CALL_PARAMETERS_WRAP = CommonCodeStyleSettings.WRAP_AS_NEEDED;
        common.ASSIGNMENT_WRAP = CommonCodeStyleSettings.WRAP_AS_NEEDED;
        common.KEEP_BLANK_LINES_IN_CODE = 2;
        common.KEEP_BLANK_LINES_IN_DECLARATIONS = 2;
        common.KEEP_BLANK_LINES_BEFORE_RBRACE = 2;
        common.BLANK_LINES_AROUND_METHOD = 0;
        common.INDENT_CASE_FROM_SWITCH = true;
        common.INDENT_BREAK_FROM_CASE = true;
    }

    @Override
    public IndentOptionsEditor getIndentOptionsEditor() {
        return new SmartIndentOptionsEditor();
    }

    @Override
    public void customizeSettings(@NotNull CodeStyleSettingsCustomizable consumer, @NotNull SettingsType type) {
        if (type == SettingsType.INDENT_SETTINGS) {
            consumer.showStandardOptions("INDENT_SIZE", "CONTINUATION_INDENT_SIZE", "TAB_SIZE", "USE_TAB_CHARACTER", "SMART_TABS");
        } else if (type == SettingsType.WRAPPING_AND_BRACES_SETTINGS) {
            consumer.showStandardOptions("RIGHT_MARGIN", "KEEP_LINE_BREAKS", "CALL_PARAMETERS_WRAP",
                    "ASSIGNMENT_WRAP", "ELSE_ON_NEW_LINE", "CATCH_ON_NEW_LINE", "WHILE_ON_NEW_LINE");
            consumer.renameStandardOption("CALL_PARAMETERS_WRAP",
                    SlangBundle.message("settings.code.style.argument.lists"));
            consumer.showCustomOption(SlangCodeStyleSettings.class, "BRACES_ON_NEW_LINE",
                    SlangBundle.message("settings.code.style.braces.new.line"),
                     SlangBundle.message("settings.code.style.braces"));
            String[] choices = {SlangBundle.message("settings.code.style.braces.inherit"),
                    SlangBundle.message("settings.code.style.braces.same.line"),
                    SlangBundle.message("settings.code.style.braces.next.line")};
            for (String option : new String[] {"FUNCTION_BRACES", "TYPE_BRACES", "CONTROL_BRACES"}) {
                consumer.showCustomOption(SlangCodeStyleSettings.class, option,
                        SlangBundle.message("settings.code.style." + option.toLowerCase(java.util.Locale.ROOT)),
                        SlangBundle.message("settings.code.style.braces"), choices, new int[] {0, 1, 2});
            }
        } else if (type == SettingsType.SPACING_SETTINGS) {
            consumer.showStandardOptions("SPACE_BEFORE_IF_PARENTHESES", "SPACE_BEFORE_FOR_PARENTHESES",
                    "SPACE_BEFORE_WHILE_PARENTHESES", "SPACE_BEFORE_SWITCH_PARENTHESES", "SPACE_BEFORE_CATCH_PARENTHESES",
                    "SPACE_BEFORE_METHOD_CALL_PARENTHESES", "SPACE_WITHIN_PARENTHESES", "SPACE_WITHIN_BRACKETS",
                    "SPACE_BEFORE_COMMA", "SPACE_AFTER_COMMA", "SPACE_AFTER_SEMICOLON",
                    "SPACE_AROUND_ASSIGNMENT_OPERATORS", "SPACE_AROUND_LOGICAL_OPERATORS",
                    "SPACE_AROUND_EQUALITY_OPERATORS", "SPACE_AROUND_ADDITIVE_OPERATORS",
                    "SPACE_AROUND_SHIFT_OPERATORS");
            consumer.renameStandardOption("SPACE_BEFORE_METHOD_CALL_PARENTHESES",
                    SlangBundle.message("settings.code.style.function.parentheses"));
        } else if (type == SettingsType.BLANK_LINES_SETTINGS) {
            consumer.showStandardOptions("KEEP_BLANK_LINES_IN_CODE", "KEEP_BLANK_LINES_IN_DECLARATIONS",
                    "KEEP_BLANK_LINES_BEFORE_RBRACE", "BLANK_LINES_AROUND_METHOD");
            consumer.renameStandardOption("BLANK_LINES_AROUND_METHOD",
                    SlangBundle.message("settings.code.style.blank.lines.functions"));
        }
    }

    @Override
    public String getCodeSample(@NotNull SettingsType type) {
        return """
                [shader("compute")]
                [numthreads(8, 8, 1)]
                void computeMain(uint3 id : SV_DispatchThreadID)
                {
                    float weights[3];
                    for (int i = 0; i < 3; i++)
                    {
                        weights[i] = evaluateIntensity(i, 0.5, 1.0);
                    }
                    switch (id.x)
                    {
                        case 0:
                            weights[0] = 1;
                            break;
                        default:
                            break;
                    }
                }

                struct Material
                {
                    float3 color;
                    float roughness;
                };

                float evaluateIntensity(float intensity, float roughness, float scale)
                {
                    float result = intensity + roughness;
                    if (result == 0 || intensity != 1)
                    {
                        return result * scale;
                    }
                    else
                    {
                        return intensity - roughness;
                    }
                }

                float previewIntensity(float firstSample, float secondSample, float thirdSample)
                {
                    return evaluateIntensity(firstSample, secondSample, thirdSample);
                }
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
                        addTab(new SlangFormatterPanel(settings));
                        addTab(new MyIndentOptionsWrapper(settings, new SmartIndentOptionsEditor()) {
                            @Override protected int getRightMargin() {
                                return getSettings().getRightMargin(SlangLanguage.INSTANCE);
                            }
                            @Override protected void installPreviewPanel(JPanel preview) {
                                super.installPreviewPanel(preview);
                                SlangLayoutPanels.padPreview(preview);
                            }
                        });
                        addTab(new SlangLayoutPanels.Braces(settings));
                        addTab(new SlangLayoutPanels.BlankLines(settings));
                        addTab(new SlangLayoutPanels.Wrapping(settings));
                        addTab(new SlangLayoutPanels.Spaces(settings));
                        addTab(new SlangLayoutPanels.Indentation(settings));
                    }

                    @Override
                    public JComponent getPanel() {
                        if (container == null) {
                            container = new JPanel(new BorderLayout());
                            container.add(super.getPanel(), BorderLayout.CENTER);
                        }
                        return container;
                    }
                };
                panel.reset(settings);
                panel.changeTab(SlangBundle.message("settings.general"));
                return panel;
            }
        };
    }
}
