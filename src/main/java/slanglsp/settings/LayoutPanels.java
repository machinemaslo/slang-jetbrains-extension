package slanglsp.settings;

import com.intellij.application.options.codeStyle.WrappingAndBracesPanel;
import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import slanglsp.SlangBundle;
import slanglsp.language.SlangLanguage;

/** Standard JetBrains option tables and live previews, split into focused Slang tabs. */
final class LayoutPanels {
    private LayoutPanels() {
    }

    static void padPreview(javax.swing.JPanel panel) {
        panel.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                panel.getBorder(), com.intellij.util.ui.JBUI.Borders.empty(8, 16, 8, 8)));
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
            showStandardOptions("ELSE_ON_NEW_LINE", "CATCH_ON_NEW_LINE", "WHILE_ON_NEW_LINE");
            showCustomOption(SlangCodeStyleSettings.class, "BRACES_ON_NEW_LINE",
                    SlangBundle.message("settings.code.style.braces.new.line"),
                    SlangBundle.message("settings.code.style.braces"));
            String[] choices = {SlangBundle.message("settings.code.style.braces.inherit"),
                    SlangBundle.message("settings.code.style.braces.same.line"),
                    SlangBundle.message("settings.code.style.braces.next.line")};
            for (String option : new String[] {"FUNCTION_BRACES", "TYPE_BRACES", "CONTROL_BRACES"}) {
                showCustomOption(SlangCodeStyleSettings.class, option,
                        SlangBundle.message("settings.code.style." + option.toLowerCase(java.util.Locale.ROOT)),
                        SlangBundle.message("settings.code.style.braces"), choices, new int[] {0, 1, 2});
            }
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

    static final class BlankLines extends com.intellij.application.options.codeStyle.CodeStyleBlankLinesPanel {
        BlankLines(CodeStyleSettings settings) {
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
}
