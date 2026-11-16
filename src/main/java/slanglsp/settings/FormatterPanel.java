package slanglsp.settings;

import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.annotations.NotNull;
import slanglsp.SlangBundle;
import slanglsp.language.SlangFileType;

import javax.swing.*;
import java.awt.GridBagLayout;
import java.awt.GridBagConstraints;

final class FormatterPanel extends CodeStyleAbstractPanel {
    private final JPanel panel = new JPanel();
    private final JRadioButton nativeFormatter = new JRadioButton(SlangBundle.message("settings.formatter.native"));
    private final JRadioButton clang = new JRadioButton(SlangBundle.message("settings.formatter.clang"));
    private final JCheckBox readConfig = new JCheckBox(SlangBundle.message("settings.formatter.read.config"));
    private final JCheckBox custom = new JCheckBox(SlangBundle.message("settings.formatter.custom"));
    private final JCheckBox bundled = new JCheckBox(SlangBundle.message("settings.formatter.bundled"));
    private final TextFieldWithBrowseButton executable = new TextFieldWithBrowseButton();

    FormatterPanel(CodeStyleSettings settings) {
        super(settings);
        panel.setLayout(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(12, 16));
        var group = new ButtonGroup();
        group.add(nativeFormatter);
        group.add(clang);
        row(new TitledSeparator(SlangBundle.message("settings.formatter.engine")), 0, 0);
        row(nativeFormatter, 0, 12);
        row(description("settings.formatter.native.help"), 26, 4);
        row(readConfig, 26, 10);
        row(description("settings.formatter.read.help"), 52, 4);
        row(clang, 0, 20);
        row(description("settings.formatter.clang.help"), 26, 4);
        bundled.setVisible(BundledClangFormat.executable() != null);
        bundled.setToolTipText(BundledClangFormat.executable());
        if (bundled.isVisible()) row(bundled, 26, 10);
        row(custom, 26, 8);
        executable.addBrowseFolderListener(new com.intellij.openapi.ui.TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFileDescriptor()
                        .withTitle(SlangBundle.message("settings.clang.format.executable"))));
        com.intellij.openapi.util.Disposer.register(this, executable);
        row(executable, 52, 6);
        var filler = new GridBagConstraints();
        filler.gridy = panel.getComponentCount();
        filler.weighty = 1;
        filler.fill = GridBagConstraints.VERTICAL;
        panel.add(Box.createVerticalGlue(), filler);
        nativeFormatter.addActionListener(event -> updateEnabled());
        clang.addActionListener(event -> updateEnabled());
        custom.addActionListener(event -> updateEnabled());
        addPanelToWatch(panel);
    }

    private static JBLabel description(String key) {
        return new JBLabel(SlangBundle.message(key), UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER)
                .setAllowAutoWrapping(true);
    }

    private void row(JComponent component, int indent, int top) {
        var constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = panel.getComponentCount();
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = JBUI.insets(top, indent, 0, 0);
        panel.add(component, constraints);
    }

    private void updateEnabled() {
        readConfig.setEnabled(nativeFormatter.isSelected());
        custom.setEnabled(clang.isSelected());
        bundled.setEnabled(clang.isSelected() && !custom.isSelected());
        executable.setEnabled(clang.isSelected() && custom.isSelected());
    }

    @Override public void apply(@NotNull CodeStyleSettings settings) throws ConfigurationException {
        if (clang.isSelected() && custom.isSelected() && executable.getText().isBlank()) {
            throw new ConfigurationException(SlangBundle.message("settings.formatter.path.required"));
        }
        var state = settings.getCustomSettings(SlangCodeStyleSettings.class);
        state.USE_CLANG_FORMAT = clang.isSelected();
        state.READ_CLANG_FORMAT = readConfig.isSelected();
        state.USE_CUSTOM_CLANG_FORMAT = custom.isSelected();
        state.USE_BUNDLED_CLANG_FORMAT = bundled.isSelected();
        state.CLANG_FORMAT_PATH = executable.getText().trim();
    }

    @Override protected void resetImpl(@NotNull CodeStyleSettings settings) {
        var state = settings.getCustomSettings(SlangCodeStyleSettings.class);
        clang.setSelected(state.USE_CLANG_FORMAT);
        nativeFormatter.setSelected(!state.USE_CLANG_FORMAT);
        readConfig.setSelected(state.READ_CLANG_FORMAT);
        custom.setSelected(state.USE_CUSTOM_CLANG_FORMAT);
        bundled.setSelected(state.USE_BUNDLED_CLANG_FORMAT);
        executable.setText(state.CLANG_FORMAT_PATH);
        updateEnabled();
    }

    @Override public boolean isModified(CodeStyleSettings settings) {
        var state = settings.getCustomSettings(SlangCodeStyleSettings.class);
        return state.USE_CLANG_FORMAT != clang.isSelected() || state.READ_CLANG_FORMAT != readConfig.isSelected()
                || state.USE_CUSTOM_CLANG_FORMAT != custom.isSelected()
                || state.USE_BUNDLED_CLANG_FORMAT != bundled.isSelected()
                || !state.CLANG_FORMAT_PATH.equals(executable.getText().trim());
    }

    @Override
    public JComponent getPanel() {
        return panel;
    }

    @Override
    protected @NotNull String getTabTitle() {
        return SlangBundle.message("settings.general");
    }

    @Override
    protected int getRightMargin() {
        return 0;
    }

    @Override
    protected EditorHighlighter createHighlighter(@NotNull EditorColorsScheme scheme) {
        return null;
    }

    @Override
    protected @NotNull FileType getFileType() {
        return SlangFileType.INSTANCE;
    }

    @Override
    protected String getPreviewText() {
        return "";
    }
}
