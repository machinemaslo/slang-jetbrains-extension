package slanglsp.settings

import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.text.ParseException
import javax.swing.AbstractCellEditor
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeSelectionModel
import slanglsp.SlangBundle.message

/** Each tab owns its controls, preview selection, and persisted splitter proportion. */
internal class OptionPreviewPanel(
    settings: CodeStyleSettings,
    id: String,
    private val title: String,
    definitions: List<FormattingOption>,
) : SettingsPreviewPanel(settings, id, applyPreviewOptions = true) {
    private var resetting = false
    private val options = definitions.map { it.bind() }
    private val root = DefaultMutableTreeNode()
    private val table: TreeTable

    init {
        val groups = linkedMapOf<String, DefaultMutableTreeNode>()
        for (option in options) {
            val group = groups.getOrPut(option.definition.group) {
                DefaultMutableTreeNode(option.definition.group).also(root::add)
            }
            group.add(DefaultMutableTreeNode(option, false))
            option.control.accessibleContext.accessibleName = option.definition.label
        }
        table = createTable()
        for (option in options) {
            option.onChanged({ !resetting }) {
                refreshInheritedOptions()
                showOption(option)
                onSomethingChanged()
                table.repaint()
            }
        }
        val tree = table.tree
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.expandRow(0)
        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val option = binding(node)
            if (option != null) {
                showOption(option)
            } else if (node != null) {
                val firstOption = (node.firstChild as DefaultMutableTreeNode).userObject as FormattingOption.Binding
                showExample(firstOption.definition.sample)
            } else {
                clearExample()
            }
        }
        installOptions(JBScrollPane(table).apply {
            preferredSize = Dimension(0, JBUI.scale(120))
        }, scroll = false)
    }

    private fun createTable(): TreeTable {
        val label = object : ColumnInfo<DefaultMutableTreeNode, String>(message("settings.code.style.option.column")) {
            override fun valueOf(node: DefaultMutableTreeNode) = node.toString()
            override fun getColumnClass() = TreeTableModel::class.java
        }
        val value = object : ColumnInfo<DefaultMutableTreeNode, FormattingOption.Binding?>(message("settings.code.style.value.column")) {
            override fun valueOf(node: DefaultMutableTreeNode) = binding(node)
            override fun isCellEditable(node: DefaultMutableTreeNode) = binding(node) != null
        }
        val model = ListTreeTableModelOnColumns(root, arrayOf(label, value))
        return TreeTable(model).apply {
            setRootVisible(false)
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            setShowGrid(false)
            tableHeader = null
            rowHeight = maxOf(rowHeight, JBUI.scale(28))
            putClientProperty("terminateEditOnFocusLost", true)
            columnModel.getColumn(0).preferredWidth = JBUI.scale(430)
            columnModel.getColumn(1).apply {
                preferredWidth = JBUI.scale(160)
                maxWidth = JBUI.scale(220)
                cellRenderer = TableCellRenderer { table, cell, selected, _, row, column ->
                    val option = cell as? FormattingOption.Binding
                    option?.renderValue()?.apply {
                        background = if (selected) table.selectionBackground else table.background
                        foreground = if (selected) table.selectionForeground else table.foreground
                    } ?: table.getDefaultRenderer(Any::class.java)
                        .getTableCellRendererComponent(table, "", selected, false, row, column)
                }
                cellEditor = InlineOptionEditor()
            }
        }
    }

    private fun binding(node: DefaultMutableTreeNode?) = node?.userObject as? FormattingOption.Binding

    private class InlineOptionEditor : AbstractCellEditor(), TableCellEditor {
        private var option: FormattingOption.Binding? = null

        override fun getTableCellEditorComponent(
            table: JTable, value: Any?, selected: Boolean, row: Int, column: Int,
        ): JComponent {
            val binding = value as FormattingOption.Binding
            option = binding
            return binding.control
        }

        override fun getCellEditorValue() = option

        override fun stopCellEditing(): Boolean {
            try {
                option?.commitEditor()
            } catch (_: ParseException) {
                return false
            }
            return super.stopCellEditing()
        }
    }

    private fun showOption(option: FormattingOption.Binding) {
        showExample(option.definition.sample, option.definition.description)
    }

    private fun refreshInheritedOptions() {
        if (options.none { it.followsParent }) {
            return
        }
        val draft = CodeStyleSettingsManager.getInstance().cloneSettings(settings)
        apply(draft)
        resetting = true
        try {
            options.forEach { it.refreshInherited(draft) }
        } finally {
            resetting = false
        }
    }

    override fun getTabTitle() = title

    override fun apply(settings: CodeStyleSettings) {
        options.filter { it.isModified(settings) }.forEach { it.apply(settings) }
    }

    override fun resetImpl(settings: CodeStyleSettings) {
        resetting = true
        try {
            options.forEach { it.reset(settings) }
            table.cellEditor?.cancelCellEditing()
            table.clearSelection()
            table.tree.clearSelection()
            table.repaint()
            clearExample()
        } finally {
            resetting = false
        }
    }

    override fun isModified(settings: CodeStyleSettings) = options.any { it.isModified(settings) }
}
