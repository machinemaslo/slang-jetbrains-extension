package slanglsp.settings;

import com.intellij.application.options.codeStyle.WrappingAndBracesPanel;
import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import slanglsp.SlangBundle;
import slanglsp.language.SlangLanguage;

import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

/** Standard JetBrains option tables and live previews, split into focused Slang tabs. */
final class LayoutPanels {
    private LayoutPanels() {
    }

    static void padPreview(javax.swing.JPanel panel) {
        panel.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                panel.getBorder(), com.intellij.util.ui.JBUI.Borders.empty(8, 16, 8, 8)));
    }

    static void showBraceOptions(com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable options) {
        options.showStandardOptions("ELSE_ON_NEW_LINE", "CATCH_ON_NEW_LINE", "WHILE_ON_NEW_LINE");
        String group = SlangBundle.message("settings.code.style.braces");
        options.showCustomOption(SlangCodeStyleSettings.class, "BRACES_ON_NEW_LINE",
                SlangBundle.message("settings.code.style.braces.new.line"), group);
        String[] choices = {SlangBundle.message("settings.code.style.braces.inherit"),
                SlangBundle.message("settings.code.style.braces.same.line"),
                SlangBundle.message("settings.code.style.braces.next.line"),
                SlangBundle.message("settings.code.style.braces.no.space")};
        String[] fields = {"NAMESPACE_BRACES", "TYPE_BRACES", "STRUCT_BRACES", "CLASS_BRACES", "ENUM_BRACES",
                "BUFFER_BRACES", "FUNCTION_BRACES",
                "SWITCH_BRACES", "CASE_BRACES", "INITIALIZER_BRACES", "CONTROL_BRACES"};
        for (String field : fields) {
            options.showCustomOption(SlangCodeStyleSettings.class, field,
                    SlangBundle.message("settings.code.style." + field.toLowerCase(java.util.Locale.ROOT)),
                    group, choices, new int[] {0, 1, 2, 3});
        }
        options.showCustomOption(SlangCodeStyleSettings.class, "KEEP_BLANK_LINES_AFTER_LBRACE",
                SlangBundle.message("settings.code.style.keep.blank.lines.after.brace"), group);
    }

    private abstract static class PreviewPanel extends WrappingAndBracesPanel {
        PreviewPanel(CodeStyleSettings settings) {
            super(settings);
        }

        @Override
        public @NotNull Language getDefaultLanguage() {
            return SlangLanguage.INSTANCE;
        }

        @Override
        protected int getRightMargin() {
            return getSettings().getRightMargin(SlangLanguage.INSTANCE);
        }

        @Override
        protected void installPreviewPanel(@NotNull javax.swing.JPanel panel) {
            super.installPreviewPanel(panel);
            padPreview(panel);
        }
    }

    static final class Braces extends PreviewPanel {
        Braces(CodeStyleSettings settings) {
            super(settings);
        }

        @Override
        protected @NotNull String getTabTitle() {
            return SlangBundle.message("settings.code.style.braces.tab");
        }

        @Override
        protected void customizeSettings() {
            showBraceOptions(this);
        }

        @Override
        protected void installPreviewPanel(@NotNull JPanel preview) {
            super.installPreviewPanel(preview);
            var parent = preview.getParent();
            if (parent.getLayout() instanceof GridBagLayout layout) {
                for (var component : parent.getComponents()) {
                    var constraints = layout.getConstraints(component);
                    constraints.gridx = 0;
                    constraints.gridy = component == preview ? 1 : 0;
                    constraints.weightx = 1;
                    constraints.weighty = 1;
                    constraints.fill = GridBagConstraints.BOTH;
                    layout.setConstraints(component, constraints);
                }
            }
        }

        @Override
        protected String getPreviewText() {
            return """
                    namespace Lighting
                    {
                        struct Material
                        {
                            float roughness;
                        };
                        cbuffer Scene
                        {
                            float exposure;
                        }
                        float intensity(int mode)
                        {
                            float values[2] = {1.0, 2.0};
                            switch (mode)
                            {
                                case 0:
                                {
                                    if (exposure > 1.0)
                                    {
                                        return values[0];
                                    }
                                    else
                                    {
                                        return values[1];
                                    }
                                }
                                default:
                                    return exposure;
                            }
                        }
                    }
                    """;
        }
    }

    static final class Wrapping extends PreviewPanel {
        Wrapping(CodeStyleSettings settings) {
            super(settings);
        }

        @Override
        protected @NotNull String getTabTitle() {
            return SlangBundle.message("settings.code.style.wrapping.tab");
        }

        @Override
        protected void customizeSettings() {
            showStandardOptions("RIGHT_MARGIN", "KEEP_LINE_BREAKS", "CALL_PARAMETERS_WRAP", "ASSIGNMENT_WRAP");
            renameStandardOption("CALL_PARAMETERS_WRAP", SlangBundle.message("settings.code.style.argument.lists"));
        }
    }

    static final class Indentation extends PreviewPanel {
        Indentation(CodeStyleSettings settings) {
            super(settings);
        }

        @Override
        protected @NotNull String getTabTitle() {
            return SlangBundle.message("settings.code.style.alignment.tab");
        }

        @Override
        protected void customizeSettings() {
            showStandardOptions("ALIGN_MULTILINE_PARAMETERS_IN_CALLS", "INDENT_CASE_FROM_SWITCH", "INDENT_BREAK_FROM_CASE");
            renameStandardOption("ALIGN_MULTILINE_PARAMETERS_IN_CALLS", SlangBundle.message("settings.code.style.align.arguments"));
            showCustomOption(SlangCodeStyleSettings.class, "NAMESPACE_INDENTATION",
                    SlangBundle.message("settings.code.style.namespace.indent"), getTabTitle(),
                    new String[]{SlangBundle.message("settings.code.style.namespace.indent.all"),
                            SlangBundle.message("settings.code.style.namespace.indent.none"),
                            SlangBundle.message("settings.code.style.namespace.indent.inner")}, new int[]{0, 1, 2});
        }
    }

    static final class Spaces extends com.intellij.application.options.codeStyle.CodeStyleSpacesPanel {
        Spaces(CodeStyleSettings settings) {
            super(settings);
        }

        @Override
        public @NotNull Language getDefaultLanguage() {
            return SlangLanguage.INSTANCE;
        }

        @Override
        protected int getRightMargin() {
            return getSettings().getRightMargin(SlangLanguage.INSTANCE);
        }

        @Override
        protected void installPreviewPanel(@NotNull javax.swing.JPanel panel) {
            super.installPreviewPanel(panel);
            padPreview(panel);
        }
    }

    static final class BlankLines extends com.intellij.application.options.codeStyle.OptionTableWithPreviewPanel {
        BlankLines(CodeStyleSettings settings) {
            super(settings);
            init();
        }

        @Override
        public com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider.SettingsType getSettingsType() {
            return com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider.SettingsType.BLANK_LINES_SETTINGS;
        }

        @Override
        protected @NotNull String getTabTitle() {
            return SlangBundle.message("settings.code.style.blank.lines.tab");
        }

        @Override
        protected void initTables() {
            String group = getTabTitle();
            addOption("KEEP_BLANK_LINES_IN_CODE",
                    SlangBundle.message("settings.code.style.blank.lines.code"), group, 0, 100, 0, null);
            addOption("KEEP_BLANK_LINES_IN_DECLARATIONS",
                    SlangBundle.message("settings.code.style.blank.lines.declarations"), group, 0, 100, 0, null);
            addOption("KEEP_BLANK_LINES_BEFORE_RBRACE",
                    SlangBundle.message("settings.code.style.blank.lines.before.brace"), group, 0, 100, 0, null);
            addOption("BLANK_LINES_AROUND_METHOD",
                    SlangBundle.message("settings.code.style.blank.lines.functions"), group, 0, 100, 0, null);
        }

        @Override
        public @NotNull Language getDefaultLanguage() {
            return SlangLanguage.INSTANCE;
        }

        @Override
        protected int getRightMargin() {
            return getSettings().getRightMargin(SlangLanguage.INSTANCE);
        }

        @Override
        protected void installPreviewPanel(@NotNull javax.swing.JPanel panel) {
            super.installPreviewPanel(panel);
            padPreview(panel);
        }
    }
}
