package slanglsp.settings.codestyle.ui

import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.*
import com.intellij.ui.components.*
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import java.util.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import kotlin.math.min
import slanglsp.SlangBundle
import slanglsp.settings.codestyle.NamingRule

internal class NamingRuleDialog(project: Project?, rule: NamingRule) : DialogWrapper(project) {
    private val name = JBTextField()
    private val prefix = JBTextField()
    private val suffix = JBTextField()
    private val style = ComboBox<NamingRule.Style?>(NamingRule.Style.entries.toTypedArray())
    private val enabled = JBCheckBox(SlangBundle.message("naming.rule.inspect"))
    private val model = DefaultListModel<NamingRule.Variant>()
    private val styles = JBList(model)
    private val categories: CheckboxTree
    private var loading = false

    init {
        title = SlangBundle.message("naming.edit")
        name.setText(rule.name)
        enabled.setSelected(rule.enabled)
        model.addAll(rule.variants)
        styles.selectionMode = ListSelectionModel.SINGLE_SELECTION
        styles.setCellRenderer(
            textRenderer { variant: NamingRule.Variant? ->
                variant!!.preview()
            })
        style.setRenderer(
            textRenderer { style: NamingRule.Style? ->
                NamingRule.label(style!!)
            })
        categories = categoryTree(rule)
        styles.addListSelectionListener { loadStyle() }
        style.addActionListener { saveStyle() }
        val listener: DocumentAdapter =
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                    saveStyle()
                }
            }
        prefix.document.addDocumentListener(listener)
        suffix.document.addDocumentListener(listener)
        styles.setSelectedIndex(0)
        init()
    }

    private fun loadStyle() {
        val variant = styles.getSelectedValue()
        loading = true
        prefix.setEnabled(variant != null)
        suffix.setEnabled(variant != null)
        style.setEnabled(variant != null)
        if (variant != null) {
            prefix.setText(variant.prefix)
            suffix.setText(variant.suffix)
            style.setSelectedItem(variant.style)
        }
        loading = false
    }

    private fun saveStyle() {
        val index = styles.selectedIndex
        val selection = style.selectedItem as? NamingRule.Style
        if (!loading && index >= 0 && selection != null) {
            model.set(index, NamingRule.Variant(selection, prefix.getText(), suffix.getText()))
        }
    }

    private fun moveStyle(offset: Int) {
        val from = styles.selectedIndex
        val to = from + offset
        if (from >= 0 && to >= 0 && to < model.size()) {
            val moved = model.get(from)!!
            model.set(from, model.get(to))
            model.set(to, moved)
            styles.setSelectedIndex(to)
        }
    }

    public override fun createCenterPanel(): JComponent {
        val toolbar =
            ToolbarDecorator.createDecorator(styles)
                .setToolbarPosition(ActionToolbarPosition.TOP)
                .setAddAction {
                    model.addElement(NamingRule.Variant(NamingRule.Style.SNAKE_CASE, "", ""))
                    styles.setSelectedIndex(model.size() - 1)
                }
                .setRemoveAction {
                    val index = styles.selectedIndex
                    if (index >= 0 && model.size() > 1) {
                        model.remove(index)
                        styles.setSelectedIndex(min(index, model.size() - 1))
                    }
                }
                .setRemoveActionUpdater { model.size() > 1 && !styles.isSelectionEmpty }
                .setMoveUpAction { moveStyle(-1) }
                .setMoveDownAction { moveStyle(1) }
                .setPreferredSize(JBUI.size(330, 180))
        val left =
            FormBuilder.createFormBuilder()
                .addComponent(JBLabel(SlangBundle.message("naming.rule.styles")))
                .addComponent(toolbar.createPanel())
                .addLabeledComponent(SlangBundle.message("naming.prefix"), prefix)
                .addLabeledComponent(SlangBundle.message("naming.suffix"), suffix)
                .addLabeledComponent(SlangBundle.message("naming.style"), style)
                .addComponent(enabled)
                .panel
        val right = JPanel(BorderLayout(0, JBUI.scale(6)))
        right.add(JBLabel(SlangBundle.message("naming.rule.elements")), BorderLayout.NORTH)
        right.add(JBScrollPane(categories), BorderLayout.CENTER)
        val columns = JPanel(GridLayout(1, 2, JBUI.scale(16), 0))
        columns.add(left)
        columns.add(right)
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(SlangBundle.message("naming.rule.name"), name, true)
            .addComponentFillVertically(columns, JBUI.scale(10))
            .panel
    }

    private fun selectedCategories(): List<NamingRule.Category> {
        return categories.getCheckedNodes(NamingRule.Category::class.java, null).toList()
    }

    fun rule(): NamingRule {
        val selected = selectedCategories()
        val matching =
            if (selected.size == NamingRule.Category.entries.size - 1) EnumSet.of(NamingRule.Category.ALL)
            else EnumSet.copyOf(selected)
        return NamingRule(
            name.getText().trim { it <= ' ' }, matching, Collections.list(model.elements()), enabled.isSelected)
    }

    override fun doValidate(): ValidationInfo? {
        if (name.getText().isBlank()) {
            return ValidationInfo(SlangBundle.message("naming.name.required"), name)
        }
        if (selectedCategories().isEmpty()) {
            return ValidationInfo(SlangBundle.message("naming.elements.required"), categories)
        }
        for ((_, prefix, suffix) in Collections.list(model.elements())) {
            if (!prefix.matches("[A-Za-z_][A-Za-z_0-9]*|".toRegex()) || !suffix.matches("[A-Za-z_0-9]*".toRegex())) {
                return ValidationInfo(SlangBundle.message("naming.affix.invalid"), styles)
            }
        }
        return null
    }

    companion object {
        private fun <T> textRenderer(render: (T) -> String): SimpleListCellRenderer<T?> {
            return object : SimpleListCellRenderer<T?>() {
                override fun customize(
                    list: JList<out T?>,
                    value: T?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    text = if (value == null) "" else render(value)
                }
            }
        }

        private fun categoryTree(rule: NamingRule): CheckboxTree {
            val root = CheckedTreeNode()
            group(
                root,
                SlangBundle.message("naming.elements.types"),
                rule,
                NamingRule.Category.TYPES,
                NamingRule.Category.INTERFACES,
                NamingRule.Category.ENUMS,
                NamingRule.Category.ENUM_MEMBERS,
                NamingRule.Category.NAMESPACES)
            group(
                root,
                SlangBundle.message("naming.elements.callables"),
                rule,
                NamingRule.Category.FUNCTIONS,
                NamingRule.Category.METHODS,
                NamingRule.Category.GENERIC_PARAMETERS,
                NamingRule.Category.PARAMETERS)
            group(
                root,
                SlangBundle.message("naming.elements.values"),
                rule,
                NamingRule.Category.FIELDS,
                NamingRule.Category.GLOBALS,
                NamingRule.Category.CONSTANTS,
                NamingRule.Category.LOCALS)
            return createTree(root)
        }

        private fun createTree(root: CheckedTreeNode): CheckboxTree {
            val tree =
                CheckboxTree(
                    object : CheckboxTree.CheckboxTreeCellRenderer() {
                        override fun customizeRenderer(
                            tree: JTree,
                            value: Any,
                            selected: Boolean,
                            expanded: Boolean,
                            leaf: Boolean,
                            row: Int,
                            hasFocus: Boolean
                        ) {
                            val item = (value as CheckedTreeNode).getUserObject()
                            if (item != null) {
                                textRenderer.append(
                                    if (item is NamingRule.Category) NamingRule.label(item) else item.toString())
                            }
                        }
                    },
                    root,
                    CheckboxTreeBase.CheckPolicy(
                        checkChildrenWithCheckedParent = true,
                        uncheckChildrenWithUncheckedParent = true,
                        checkParentWithCheckedChild = false,
                        uncheckParentWithUncheckedChild = true,
                        checkByRowClick = false))
            tree.setRootVisible(false)
            for (row in 0..<tree.getRowCount()) {
                tree.expandRow(row)
            }
            return tree
        }

        private fun group(
            root: CheckedTreeNode,
            title: String,
            rule: NamingRule,
            vararg categories: NamingRule.Category?
        ) {
            val group = CheckedTreeNode(title)
            root.add(group)
            var any = false
            for (category in categories) {
                val node = CheckedTreeNode(category)
                node.setChecked(rule.matches(category))
                any = any or node.isChecked()
                group.add(node)
            }
            group.setChecked(any)
        }
    }
}
