package slanglsp.settings;

import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.annotations.NotNull;
import slanglsp.SlangBundle;
import slanglsp.language.SlangFileType;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.awt.GridBagConstraints;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class FormatterPanel extends CodeStyleAbstractPanel {
    private final JPanel panel = new JPanel();
    private final JRadioButton nativeFormatter = new JRadioButton(SlangBundle.message("settings.formatter.native"));
    private final JRadioButton clang = new JRadioButton(SlangBundle.message("settings.formatter.clang"));
    private final JCheckBox readConfig = new JCheckBox(SlangBundle.message("settings.formatter.read.config"));
    private final JCheckBox external = new JCheckBox(SlangBundle.message("settings.formatter.external"));
    private final JBTextField executable = new JBTextField();
    private final JButton browse = new JButton(SlangBundle.message("settings.formatter.browse"));
    private final boolean bundledAvailable;

    FormatterPanel(CodeStyleSettings settings) {
        this(settings, BundledClangFormat.executable());
    }

    FormatterPanel(CodeStyleSettings settings, String bundledExecutable) {
        super(settings);
        bundledAvailable = bundledExecutable != null;
        panel.setLayout(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(12, 16));
        var group = new ButtonGroup();
        group.add(nativeFormatter);
        group.add(clang);
        nativeFormatter.setSelected(true);

        row(new TitledSeparator(SlangBundle.message("settings.formatter.engine")), 0, 0);
        row(nativeFormatter, 0, 12);
        row(description("settings.formatter.native.help"), 26, 4);
        row(readConfig, 26, 10);
        row(clang, 0, 8);
        row(description(bundledAvailable ? "settings.formatter.clang.bundled.help" : "settings.formatter.clang.external.help"), 26, 4);
        if (bundledAvailable) {
            row(external, 26, 10);
        }

        executable.getAccessibleContext().setAccessibleName(SlangBundle.message("settings.clang.format.executable"));
        browse.addActionListener(event -> chooseExecutable());
        var executableRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        executableRow.add(executable, BorderLayout.CENTER);
        executableRow.add(browse, BorderLayout.EAST);
        row(executableRow, 26, 10);

        row(new TitledSeparator(SlangBundle.message("settings.formatter.export")), 0, 16);
        var export = new JButton(SlangBundle.message("settings.formatter.export.button"));
        export.addActionListener(event -> exportStyle());
        var exportRow = new JPanel(new BorderLayout());
        exportRow.add(export, BorderLayout.WEST);
        row(exportRow, 0, 10);

        var filler = new GridBagConstraints();
        filler.gridy = panel.getComponentCount();
        filler.weighty = 1;
        filler.fill = GridBagConstraints.VERTICAL;
        panel.add(Box.createVerticalGlue(), filler);
        nativeFormatter.addActionListener(event -> updateEnabled());
        clang.addActionListener(event -> updateEnabled());
        external.addActionListener(event -> updateEnabled());
        updateEnabled();
        addPanelToWatch(panel);
    }

    private void chooseExecutable() {
        var descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle(SlangBundle.message("settings.clang.format.executable"));
        String path = executable.getText().trim();
        var initial = SlangCodeStyleSettings.isAbsoluteExecutablePath(path)
                ? LocalFileSystem.getInstance().findFileByNioFile(Path.of(path)) : null;
        FileChooser.chooseFile(descriptor, null, panel, initial, file -> executable.setText(file.getPresentableUrl()));
    }

    private void exportStyle() {
        String title = SlangBundle.message("settings.formatter.export");
        var descriptor = new FileSaverDescriptor(title, "");
        var target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, panel).save(".clang-format");
        if (target == null) {
            return;
        }

        try {
            var current = getModelSettings();
            Files.writeString(target.getFile().toPath(), ClangFormatStyle.export(current == null ? getSettings() : current));
            LocalFileSystem.getInstance().refreshIoFiles(java.util.List.of(target.getFile()), true, false, null);
        } catch (IOException failure) {
            Messages.showErrorDialog(panel, SlangBundle.message("settings.formatter.export.failed", failure.getMessage()), title);
        }
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
        external.setEnabled(clang.isSelected());
        boolean useExternal = clang.isSelected() && (!bundledAvailable || external.isSelected());
        executable.setEnabled(useExternal);
        browse.setEnabled(useExternal);
    }

    @Override
    public void apply(@NotNull CodeStyleSettings settings) throws ConfigurationException {
        String path = executable.getText().trim();
        if (clang.isSelected() && (!bundledAvailable || external.isSelected())
                && !SlangCodeStyleSettings.isAbsoluteExecutablePath(path)) {
            throw new ConfigurationException(SlangBundle.message("settings.formatter.path.required"));
        }
        var state = settings.getCustomSettings(SlangCodeStyleSettings.class);
        state.USE_CLANG_FORMAT = clang.isSelected();
        state.READ_CLANG_FORMAT = readConfig.isSelected();
        state.USE_CUSTOM_CLANG_FORMAT = external.isSelected();
        state.CLANG_FORMAT_PATH = path;
    }

    @Override
    protected void resetImpl(@NotNull CodeStyleSettings settings) {
        var state = settings.getCustomSettings(SlangCodeStyleSettings.class);
        clang.setSelected(state.USE_CLANG_FORMAT);
        nativeFormatter.setSelected(!state.USE_CLANG_FORMAT);
        readConfig.setSelected(state.READ_CLANG_FORMAT);
        external.setSelected(state.USE_CUSTOM_CLANG_FORMAT);
        executable.setText(state.CLANG_FORMAT_PATH);
        updateEnabled();
    }

    @Override
    public boolean isModified(CodeStyleSettings settings) {
        var state = settings.getCustomSettings(SlangCodeStyleSettings.class);
        return state.USE_CLANG_FORMAT != clang.isSelected() || state.READ_CLANG_FORMAT != readConfig.isSelected()
                || state.USE_CUSTOM_CLANG_FORMAT != external.isSelected()
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
