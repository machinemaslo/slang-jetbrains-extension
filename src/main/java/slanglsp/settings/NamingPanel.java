package slanglsp.settings;

import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import slanglsp.SlangBundle;
import slanglsp.editor.InconsistentNamingInspection;
import slanglsp.language.SlangFileType;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

final class NamingPanel extends CodeStyleAbstractPanel {
    private final Project contextProject;
    private final JPanel panel = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(8)));
    private final JCheckBox enabled = new JBCheckBox(message("naming.enable"));
    private final JTextField abbreviations = new JBTextField();
    private final List<NamingRule> rules = new ArrayList<>();
    private final RulesModel model = new RulesModel();
    private final JBTable table = new JBTable(model);
    private boolean initialEnabled;

    NamingPanel(CodeStyleSettings settings) {
        this(settings, null);
    }

    NamingPanel(CodeStyleSettings settings, @org.jetbrains.annotations.Nullable Project project) {
        super(settings);
        contextProject = project;
        panel.setBorder(JBUI.Borders.empty(12, 16));
        var top = new JPanel(new BorderLayout());
        top.add(enabled, BorderLayout.NORTH);
        panel.add(top, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        var toolbar = ToolbarDecorator.createDecorator(table)
                .setToolbarPosition(com.intellij.openapi.actionSystem.ActionToolbarPosition.RIGHT)
                .setAddAction(button -> edit(-1))
                .setEditAction(button -> edit(table.getSelectedRow()))
                .setRemoveAction(button -> {
                    int row = table.getSelectedRow();
                    if (row >= 0) {
                        rules.remove(row);
                        model.fireTableDataChanged();
                    }
                })
                .setMoveUpAction(button -> move(-1))
                .setMoveDownAction(button -> move(1));
        panel.add(toolbar.createPanel(), BorderLayout.CENTER);
        var bottom = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        bottom.add(new JBLabel(message("naming.abbreviations")), BorderLayout.NORTH);
        bottom.add(abbreviations, BorderLayout.CENTER);
        bottom.add(new JBLabel(message("naming.order"),
                com.intellij.util.ui.UIUtil.ComponentStyle.SMALL,
                com.intellij.util.ui.UIUtil.FontColor.BRIGHTER), BorderLayout.SOUTH);
        panel.add(bottom, BorderLayout.SOUTH);
        abbreviations.getDocument().addDocumentListener(new com.intellij.ui.DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull javax.swing.event.DocumentEvent event) {
                model.fireTableDataChanged();
            }
        });
        addPanelToWatch(panel);
    }

    private static String message(@org.jetbrains.annotations.PropertyKey(resourceBundle = "messages.SlangBundle") String key) {
        return SlangBundle.message(key);
    }

    private Project project() {
        if (contextProject != null) {
            return contextProject;
        }
        var project = com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.getData(
                com.intellij.ide.DataManager.getInstance().getDataContext(panel));
        return project != null ? project : com.intellij.openapi.project.ProjectManager.getInstance().getDefaultProject();
    }

    private com.intellij.codeInspection.ex.InspectionProfileImpl profile() {
        var project = project();
        return (project.isDefault() ? InspectionProfileManager.getInstance()
                : InspectionProfileManager.getInstance(project)).getCurrentProfile();
    }

    private boolean inspectionEnabled() {
        var profile = profile();
        // Initialize tools before looking up the display key.
        profile.getInspectionTool(InconsistentNamingInspection.ID, project());
        var key = HighlightDisplayKey.find(InconsistentNamingInspection.ID);
        return key != null && profile.isToolEnabled(key);
    }

    private void move(int delta) {
        int row = table.getSelectedRow();
        int target = row + delta;
        if (row >= 0 && target >= 0 && target < rules.size()) {
            java.util.Collections.swap(rules, row, target);
            model.fireTableDataChanged();
            table.setRowSelectionInterval(target, target);
        }
    }

    private void edit(int row) {
        if (row < -1 || row >= rules.size()) {
            return;
        }
        var dialog = new RuleDialog(row < 0
                ? new NamingRule(message("naming.new.rule"), NamingRule.Category.ALL, NamingRule.Style.SNAKE_CASE, "", "")
                : rules.get(row));
        if (dialog.showAndGet()) {
            if (row < 0) {
                rules.add(dialog.rule());
                row = rules.size() - 1;
            } else {
                rules.set(row, dialog.rule());
            }
            model.fireTableDataChanged();
            table.setRowSelectionInterval(row, row);
        }
    }

    @Override
    public void apply(@NotNull CodeStyleSettings settings) {
        var custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
        custom.setNamingRules(rules);
        custom.NAMING_ABBREVIATIONS = abbreviations.getText().trim();
        if (enabled.isSelected() != initialEnabled) {
            var model = profile().getModifiableModel();
            model.initInspectionTools(project());
            model.setToolEnabled(InconsistentNamingInspection.ID, enabled.isSelected(), project());
            model.commit();
            initialEnabled = enabled.isSelected();
        }
    }

    @Override
    protected void resetImpl(@NotNull CodeStyleSettings settings) {
        var custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
        rules.clear();
        rules.addAll(custom.getNamingRules());
        abbreviations.setText(custom.NAMING_ABBREVIATIONS);
        initialEnabled = inspectionEnabled();
        enabled.setSelected(initialEnabled);
        model.fireTableDataChanged();
    }

    @Override
    public boolean isModified(CodeStyleSettings settings) {
        var custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
        return !rules.equals(custom.getNamingRules())
                || !abbreviations.getText().trim().equals(custom.NAMING_ABBREVIATIONS)
                || enabled.isSelected() != initialEnabled;
    }

    private final class RulesModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rules.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return message(column == 0 ? "naming.rule.name" : "naming.preview");
        }

        @Override
        public Object getValueAt(int row, int column) {
            var rule = rules.get(row);
            return column == 0 ? rule.name() : rule.suggest(NamingRule.example(rule.category()), abbreviations.getText());
        }
    }

    private final class RuleDialog extends DialogWrapper {
        private final JTextField name = new JBTextField();
        private final com.intellij.openapi.ui.ComboBox<NamingRule.Category> category =
                new com.intellij.openapi.ui.ComboBox<>(NamingRule.Category.values());
        private final com.intellij.openapi.ui.ComboBox<NamingRule.Style> style =
                new com.intellij.openapi.ui.ComboBox<>(NamingRule.Style.values());
        private final JTextField prefix = new JBTextField();
        private final JTextField suffix = new JBTextField();
        private final JPanel content = new JPanel(new GridLayout(0, 2, JBUI.scale(12), JBUI.scale(8)));

        RuleDialog(NamingRule rule) {
            super(NamingPanel.this.project());
            setTitle(message("naming.edit"));
            name.setText(rule.name());
            prefix.setText(rule.prefix());
            suffix.setText(rule.suffix());
            category.setSelectedItem(rule.category());
            style.setSelectedItem(rule.style());
            category.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                              boolean selected, boolean focus) {
                    return super.getListCellRendererComponent(list, NamingRule.label((NamingRule.Category) value),
                            index, selected, focus);
                }
            });
            style.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                              boolean selected, boolean focus) {
                    return super.getListCellRendererComponent(list,
                            NamingRule.label((NamingRule.Style) value),
                            index, selected, focus);
                }
            });
            row("naming.rule.name", name);
            row("naming.category", category);
            row("naming.style", style);
            row("naming.prefix", prefix);
            row("naming.suffix", suffix);
            init();
        }

        private void row(@org.jetbrains.annotations.PropertyKey(resourceBundle = "messages.SlangBundle") String key,
                         JComponent field) {
            content.add(new JBLabel(message(key)));
            content.add(field);
        }

        NamingRule rule() {
            return new NamingRule(name.getText().trim(), (NamingRule.Category) category.getSelectedItem(),
                    (NamingRule.Style) style.getSelectedItem(), prefix.getText(), suffix.getText());
        }

        @Override
        protected ValidationInfo doValidate() {
            if (name.getText().isBlank()) {
                return new ValidationInfo(message("naming.name.required"), name);
            }
            if (!prefix.getText().matches("[A-Za-z_][A-Za-z_0-9]*|")
                    || !suffix.getText().matches("[A-Za-z_0-9]*")) {
                return new ValidationInfo(message("naming.affix.invalid"), prefix);
            }
            return null;
        }

        @Override
        protected JComponent createCenterPanel() {
            return content;
        }
    }

    @Override
    public JComponent getPanel() {
        return panel;
    }

    @Override
    protected @NotNull String getTabTitle() {
        return message("naming.tab");
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
