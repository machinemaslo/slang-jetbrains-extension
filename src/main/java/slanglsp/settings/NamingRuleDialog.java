package slanglsp.settings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.CheckboxTree;
import com.intellij.ui.CheckedTreeNode;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import slanglsp.settings.NamingRule.Category;
import slanglsp.settings.NamingRule.Style;
import slanglsp.settings.NamingRule.Variant;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static slanglsp.SlangBundle.message;

final class NamingRuleDialog extends DialogWrapper {
    private final JBTextField name = new JBTextField();
    private final JBTextField prefix = new JBTextField();
    private final JBTextField suffix = new JBTextField();
    private final ComboBox<Style> style = new ComboBox<>(Style.values());
    private final JBCheckBox enabled = new JBCheckBox(message("naming.rule.inspect"));
    private final DefaultListModel<Variant> model = new DefaultListModel<>();
    private final JBList<Variant> styles = new JBList<>(model);
    private final CheckboxTree categories;
    private boolean loading;

    NamingRuleDialog(Project project, NamingRule rule) {
        super(project);
        setTitle(message("naming.edit"));
        name.setText(rule.name());
        enabled.setSelected(rule.enabled());
        model.addAll(rule.variants());
        styles.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        styles.setCellRenderer(SimpleListCellRenderer.create("", (Variant variant) -> NamingRule.label(variant.style())));
        style.setRenderer(SimpleListCellRenderer.create("", NamingRule::label));
        categories = categoryTree(rule);
        styles.addListSelectionListener(event -> loadStyle());
        style.addActionListener(event -> saveStyle());
        var listener = new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                saveStyle();
            }
        };
        prefix.getDocument().addDocumentListener(listener);
        suffix.getDocument().addDocumentListener(listener);
        styles.setSelectedIndex(0);
        init();
    }

    private static CheckboxTree categoryTree(NamingRule rule) {
        var root = new CheckedTreeNode();
        group(root, message("naming.elements.types"), rule, Category.TYPES, Category.INTERFACES,
                Category.ENUMS, Category.ENUM_MEMBERS, Category.NAMESPACES);
        group(root, message("naming.elements.callables"), rule, Category.FUNCTIONS, Category.METHODS,
                Category.GENERIC_PARAMETERS, Category.PARAMETERS);
        group(root, message("naming.elements.values"), rule, Category.FIELDS, Category.GLOBALS,
                Category.CONSTANTS, Category.LOCALS);
        var tree = new CheckboxTree(new CheckboxTree.CheckboxTreeCellRenderer() {
            @Override
            public void customizeRenderer(JTree tree, Object value, boolean selected, boolean expanded,
                    boolean leaf, int row, boolean hasFocus) {
                Object item = ((CheckedTreeNode) value).getUserObject();
                if (item != null) {
                    getTextRenderer().append(item instanceof Category category ? NamingRule.label(category) : item.toString());
                }
            }
        }, root);
        tree.setRootVisible(false);
        for (int row = 0; row < tree.getRowCount(); row++) {
            tree.expandRow(row);
        }
        return tree;
    }

    private static void group(CheckedTreeNode root, String title, NamingRule rule, Category... categories) {
        var group = new CheckedTreeNode(title);
        root.add(group);
        boolean any = false;
        for (var category : categories) {
            var node = new CheckedTreeNode(category);
            node.setChecked(rule.matches(category));
            any |= node.isChecked();
            group.add(node);
        }
        group.setChecked(any);
    }

    private void loadStyle() {
        var variant = styles.getSelectedValue();
        loading = true;
        prefix.setEnabled(variant != null);
        suffix.setEnabled(variant != null);
        style.setEnabled(variant != null);
        if (variant != null) {
            prefix.setText(variant.prefix());
            suffix.setText(variant.suffix());
            style.setSelectedItem(variant.style());
        }
        loading = false;
    }

    private void saveStyle() {
        int index = styles.getSelectedIndex();
        if (!loading && index >= 0 && style.getSelectedItem() instanceof Style selection) {
            model.set(index, new Variant(selection, prefix.getText(), suffix.getText()));
        }
    }

    private void moveStyle(int offset) {
        int from = styles.getSelectedIndex();
        int to = from + offset;
        if (from >= 0 && to >= 0 && to < model.size()) {
            Variant moved = model.get(from);
            model.set(from, model.get(to));
            model.set(to, moved);
            styles.setSelectedIndex(to);
        }
    }

    @Override
    protected JComponent createCenterPanel() {
        var toolbar = ToolbarDecorator.createDecorator(styles)
                .setToolbarPosition(com.intellij.openapi.actionSystem.ActionToolbarPosition.TOP)
                .setAddAction(button -> {
                    model.addElement(new Variant(Style.SNAKE_CASE, "", ""));
                    styles.setSelectedIndex(model.size() - 1);
                })
                .setRemoveAction(button -> {
                    int index = styles.getSelectedIndex();
                    if (index >= 0 && model.size() > 1) {
                        model.remove(index);
                        styles.setSelectedIndex(Math.min(index, model.size() - 1));
                    }
                })
                .setRemoveActionUpdater(event -> model.size() > 1 && !styles.isSelectionEmpty())
                .setMoveUpAction(button -> moveStyle(-1))
                .setMoveDownAction(button -> moveStyle(1))
                .setPreferredSize(JBUI.size(330, 180));
        var left = FormBuilder.createFormBuilder().addComponent(new JBLabel(message("naming.rule.styles")))
                .addComponent(toolbar.createPanel()).addLabeledComponent(message("naming.prefix"), prefix)
                .addLabeledComponent(message("naming.suffix"), suffix)
                .addLabeledComponent(message("naming.style"), style).addComponent(enabled).getPanel();
        var right = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        right.add(new JBLabel(message("naming.rule.elements")), BorderLayout.NORTH);
        right.add(new JBScrollPane(categories), BorderLayout.CENTER);
        var columns = new JPanel(new GridLayout(1, 2, JBUI.scale(16), 0));
        columns.add(left);
        columns.add(right);
        return FormBuilder.createFormBuilder().addLabeledComponent(message("naming.rule.name"), name, true)
                .addComponentFillVertically(columns, JBUI.scale(10)).getPanel();
    }

    private List<Category> selectedCategories() {
        return List.of(categories.getCheckedNodes(Category.class, null));
    }

    NamingRule rule() {
        var selected = selectedCategories();
        var matching = selected.size() == Category.values().length - 1
                ? EnumSet.of(Category.ALL) : EnumSet.copyOf(selected);
        return new NamingRule(name.getText().trim(), matching, Collections.list(model.elements()), enabled.isSelected());
    }

    @Override
    protected ValidationInfo doValidate() {
        if (name.getText().isBlank()) {
            return new ValidationInfo(message("naming.name.required"), name);
        }
        if (selectedCategories().isEmpty()) {
            return new ValidationInfo(message("naming.elements.required"), categories);
        }
        for (var variant : Collections.list(model.elements())) {
            if (!variant.prefix().matches("[A-Za-z_][A-Za-z_0-9]*|") || !variant.suffix().matches("[A-Za-z_0-9]*")) {
                return new ValidationInfo(message("naming.affix.invalid"), styles);
            }
        }
        return null;
    }
}
