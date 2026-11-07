package slanglsp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;

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
    private final JCheckBox useVcpkg = new JCheckBox(SlangBundle.message("settings.use.local.vcpkg.slangd.package.if.found"));
    private final ComboBox<String> commitCharacters = new ComboBox<>(new String[]{"off", "membersOnly", "on"});
    private final JCheckBox deducedTypes = new JCheckBox(SlangBundle.message("settings.show.inlay.hints.for.deduced.types"));
    private final JCheckBox parameterNames = new JCheckBox(SlangBundle.message("settings.show.inlay.hints.for.parameter.names"));
    private final JCheckBox searchWorkspace = new JCheckBox(SlangBundle.message("settings.search.workspace.subdirectories.for.imports.and.includes"));
    private final JCheckBox formatOnType = new JCheckBox(SlangBundle.message("settings.enable.formatting.while.typing"));
    private final JTextField clangFormat = new JTextField(30);
    private final JTextField formatStyle = new JTextField(30);
    private final JTextField fallbackStyle = new JTextField(30);
    private final JCheckBox onTypeLineBreaks = new JCheckBox(SlangBundle.message("settings.allow.line.break.changes.while.formatting.on.type"));
    private final JCheckBox rangeLineBreaks = new JCheckBox(SlangBundle.message("settings.allow.line.break.changes.while.formatting.a.selection"));

    public SlangConfigurableGUI() {
        commitCharacters.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof String mode) {
                    setText(switch (mode) {
                        case "off" -> SlangBundle.message("settings.commit.off");
                        case "membersOnly" -> SlangBundle.message("settings.commit.members.only");
                        case "on" -> SlangBundle.message("settings.commit.on");
                        default -> mode;
                    });
                }
                return this;
            }
        });
        JPanel general = formPanel();
        addField(general, SlangBundle.message("settings.additional.include.import.paths"), listEditor(includePaths, SlangBundle.message("settings.path.to.an.include.directory")));
        addField(general, SlangBundle.message("settings.predefined.macros"), listEditor(macros, SlangBundle.message("settings.examples.my.macro.or.my.value.1")));
        slangdDirectory.setToolTipText(SlangBundle.message("settings.optional.directory.containing.slangd.takes.priority.over.vcpkg.and.path"));
        addField(general, SlangBundle.message("settings.slangd.directory"), slangdDirectory);
        addField(general, null, useVcpkg);
        addField(general, SlangBundle.message("settings.completion.commit.characters"), commitCharacters);
        addField(general, null, deducedTypes);
        addField(general, null, parameterNames);
        addField(general, null, searchWorkspace);

        JPanel formatting = formPanel();
        addField(formatting, null, formatOnType);
        clangFormat.setToolTipText(SlangBundle.message("settings.full.path.including.the.executable.name.leave.empty.to.search.path"));
        addField(formatting, SlangBundle.message("settings.clang.format.executable"), clangFormat);
        formatStyle.setToolTipText(SlangBundle.message("settings.for.example.file.llvm.microsoft.or.file.path.to.clang.format"));
        addField(formatting, SlangBundle.message("settings.style"), formatStyle);
        fallbackStyle.setToolTipText(SlangBundle.message("settings.style.used.when.no.clang.format.file.is.found"));
        addField(formatting, SlangBundle.message("settings.fallback.style"), fallbackStyle);
        addField(formatting, null, onTypeLineBreaks);
        addField(formatting, null, rangeLineBreaks);
        addField(formatting, null, new JLabel(SlangBundle.message("settings.formatting.requires.clang.format.to.be.installed")));

        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab(SlangBundle.message("settings.general"), scrollable(general));
        tabs.addTab(SlangBundle.message("settings.formatting"), scrollable(formatting));
        root.add(tabs, BorderLayout.CENTER);
    }

    private static JPanel formPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(12));
        return panel;
    }

    private static JComponent scrollable(JPanel form) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(form, BorderLayout.NORTH);
        JBScrollPane scroll = new JBScrollPane(wrapper);
        scroll.setBorder(JBUI.Borders.empty());
        return scroll;
    }

    private static void addField(JPanel panel, String label, JComponent component) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = panel.getComponentCount();
        constraints.gridx = 0;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = JBUI.insets(4, 0, 4, 10);
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
        JPanel editor = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JButton add = new JButton(SlangBundle.message("settings.add"));
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
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        JTextField field = new JTextField(value, 25);
        field.setToolTipText(help);
        JButton remove = new JButton(SlangBundle.message("settings.remove"));
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
        for (var client : SlangLanguageClient.activeClients)
            if (client.project == project) client.triggerChangeConfiguration();
    }

    public void reset() { setGUIStateWithState(config.getState()); }

    public boolean isModified() { return !config.getState().equals(deriveStateFromGUI()); }
}
