package slanglsp;

import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class SlangConfigurableGUI {
    private SlangPersistentStateConfig config;
    private Project project;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel includePaths = listPanel();
    private final JPanel macros = listPanel();
    private final JTextField slangdDirectory = new JTextField(30);
    private final JCheckBox useVcpkg = new JCheckBox("Use local vcpkg slangd package if found");
    private final JComboBox<String> commitCharacters = new JComboBox<>(new String[]{"off", "membersOnly", "on"});
    private final JCheckBox deducedTypes = new JCheckBox("Show inlay hints for deduced types");
    private final JCheckBox parameterNames = new JCheckBox("Show inlay hints for parameter names");
    private final JCheckBox searchWorkspace = new JCheckBox("Search workspace subdirectories for imports and includes");
    private final JCheckBox formatOnType = new JCheckBox("Enable formatting while typing");
    private final JTextField clangFormat = new JTextField(30);
    private final JTextField formatStyle = new JTextField(30);
    private final JTextField fallbackStyle = new JTextField(30);
    private final JCheckBox onTypeLineBreaks = new JCheckBox("Allow line-break changes while formatting on type");
    private final JCheckBox rangeLineBreaks = new JCheckBox("Allow line-break changes while formatting a selection");

    public SlangConfigurableGUI() {
        JPanel general = formPanel();
        addField(general, "Additional include/import paths", listEditor(includePaths, "Path to an include directory"));
        addField(general, "Predefined macros", listEditor(macros, "Examples: MY_MACRO or MY_VALUE=1"));
        slangdDirectory.setToolTipText("Optional directory containing slangd. Takes priority over vcpkg and PATH.");
        addField(general, "slangd directory", slangdDirectory);
        addField(general, null, useVcpkg);
        addField(general, "Completion commit characters", commitCharacters);
        addField(general, null, deducedTypes);
        addField(general, null, parameterNames);
        addField(general, null, searchWorkspace);

        JPanel formatting = formPanel();
        addField(formatting, null, formatOnType);
        clangFormat.setToolTipText("Full path including the executable name. Leave empty to search PATH.");
        addField(formatting, "clang-format executable", clangFormat);
        formatStyle.setToolTipText("For example: file, LLVM, Microsoft, or file:/path/to/.clang-format");
        addField(formatting, "Style", formatStyle);
        fallbackStyle.setToolTipText("Style used when no .clang-format file is found.");
        addField(formatting, "Fallback style", fallbackStyle);
        addField(formatting, null, onTypeLineBreaks);
        addField(formatting, null, rangeLineBreaks);
        addField(formatting, null, new JLabel("Formatting requires clang-format to be installed."));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("General", scrollable(general));
        tabs.addTab("Formatting", scrollable(formatting));
        root.add(tabs, BorderLayout.CENTER);
    }

    private static JPanel formPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        return panel;
    }

    private static JComponent scrollable(JPanel form) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(form, BorderLayout.NORTH);
        JBScrollPane scroll = new JBScrollPane(wrapper);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }

    private static void addField(JPanel panel, String label, JComponent component) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = panel.getComponentCount();
        constraints.gridx = 0;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(4, 0, 4, 10);
        if (label != null) {
            JLabel fieldLabel = new JLabel(label);
            fieldLabel.setLabelFor(component);
            panel.add(fieldLabel, constraints);
            constraints.gridx = 1;
        } else {
            constraints.gridwidth = 2;
        }
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(component, constraints);
    }

    private static JPanel listPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static JPanel listEditor(JPanel rows, String help) {
        JPanel editor = new JPanel(new BorderLayout(0, 4));
        JButton add = new JButton("Add");
        add.setToolTipText(help);
        add.addActionListener(event -> addRow(rows, "", help));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        toolbar.add(add);
        editor.add(rows, BorderLayout.CENTER);
        editor.add(toolbar, BorderLayout.SOUTH);
        rows.setToolTipText(help);
        return editor;
    }

    private static void addRow(JPanel rows, String value, String help) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        JTextField field = new JTextField(value, 25);
        field.setToolTipText(help);
        JButton remove = new JButton("Remove");
        remove.addActionListener(event -> {
            rows.remove(row);
            rows.revalidate();
            rows.repaint();
        });
        row.add(field, BorderLayout.CENTER);
        row.add(remove, BorderLayout.EAST);
        rows.add(row);
        rows.revalidate();
        rows.repaint();
    }

    private static List<String> readRows(JPanel rows) {
        List<String> values = new ArrayList<>();
        for (Component component : rows.getComponents()) {
            JPanel row = (JPanel) component;
            JTextField field = (JTextField) ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            String value = field.getText().trim();
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }

    private static void setRows(JPanel rows, List<String> values) {
        rows.removeAll();
        for (String value : values) addRow(rows, value, rows.getToolTipText());
        rows.revalidate();
        rows.repaint();
    }

    JPanel getRootPanel() { return root; }

    public void createUI(Project project) {
        this.project = project;
        config = SlangPersistentStateConfig.getInstance(project);
        reset();
    }

    SlangPersistentStateConfig.State deriveStateFromGUI() {
        var state = new SlangPersistentStateConfig.State();
        state.additionalIncludePaths = readRows(includePaths);
        state.predefinedMacros = readRows(macros);
        state.explicitSlangdLocation = slangdDirectory.getText().trim();
        state.useLocalVcpkgSlangd = useVcpkg.isSelected();
        state.enableCommitCharactersInAutoCompletion = (String) commitCharacters.getSelectedItem();
        state.enableInlayHintsForDeducedTypes = deducedTypes.isSelected();
        state.enableInlayHintsForParameterNames = parameterNames.isSelected();
        state.enableSearchingSubDirectoriesOfWorkspace = searchWorkspace.isSelected();
        state.enableFormatOnType = formatOnType.isSelected();
        state.clangFormatLocation = clangFormat.getText().trim();
        state.clangFormatStyle = formatStyle.getText().trim();
        state.clangFormatFallbackStyle = fallbackStyle.getText().trim();
        state.allowLineBreakChangesInOnTypeFormatting = onTypeLineBreaks.isSelected();
        state.allowLineBreakChangesInRangeFormatting = rangeLineBreaks.isSelected();
        return state;
    }

    void setGUIStateWithState(SlangPersistentStateConfig.State state) {
        setRows(includePaths, state.additionalIncludePaths);
        setRows(macros, state.predefinedMacros);
        slangdDirectory.setText(state.explicitSlangdLocation);
        useVcpkg.setSelected(state.useLocalVcpkgSlangd);
        commitCharacters.setSelectedItem(state.enableCommitCharactersInAutoCompletion);
        deducedTypes.setSelected(state.enableInlayHintsForDeducedTypes);
        parameterNames.setSelected(state.enableInlayHintsForParameterNames);
        searchWorkspace.setSelected(state.enableSearchingSubDirectoriesOfWorkspace);
        formatOnType.setSelected(state.enableFormatOnType);
        clangFormat.setText(state.clangFormatLocation);
        formatStyle.setText(state.clangFormatStyle);
        fallbackStyle.setText(state.clangFormatFallbackStyle);
        onTypeLineBreaks.setSelected(state.allowLineBreakChangesInOnTypeFormatting);
        rangeLineBreaks.setSelected(state.allowLineBreakChangesInRangeFormatting);
    }

    public void apply() {
        var next = deriveStateFromGUI();
        var previous = config.getState();
        boolean executableChanged = next.useLocalVcpkgSlangd != previous.useLocalVcpkgSlangd
                || !next.explicitSlangdLocation.equals(previous.explicitSlangdLocation);
        config.setState(next);
        if (executableChanged) {
            project.getService(VcpkgSlangdService.class).invalidate();
            var manager = com.redhat.devtools.lsp4ij.LanguageServerManager.getInstance(project);
            if (manager.getServerStatus("slanglsp.SlangLanguageServer") == com.redhat.devtools.lsp4ij.ServerStatus.started) {
                manager.start("slanglsp.SlangLanguageServer",
                        new com.redhat.devtools.lsp4ij.LanguageServerManager.StartOptions().setForceStart(true));
            }
        }
        for (var client : SlangLanguageClient.maybeAliveClients)
            if (client.project == project) client.triggerChangeConfiguration();
    }

    public void reset() { setGUIStateWithState(config.getState()); }

    public boolean isModified() { return !config.getState().equals(deriveStateFromGUI()); }
}
