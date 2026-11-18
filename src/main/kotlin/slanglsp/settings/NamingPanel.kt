package slanglsp.settings

import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import slanglsp.SlangBundle.message
import slanglsp.editor.InconsistentNamingInspection
import slanglsp.language.SlangFileType
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.Collections
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

internal class NamingPanel @JvmOverloads constructor(
    settings: CodeStyleSettings,
    private val contextProject: Project? = null,
) : CodeStyleAbstractPanel(settings) {
    private val controls = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8)))
    private val enabled = JBCheckBox(message("naming.enable"))
    private val abbreviations = JBTextField()
    private val rules = mutableListOf<NamingRule>()
    private val model = RulesModel()
    private val table = JBTable(model)
    private var initialEnabled = false

    init {
        controls.border = JBUI.Borders.empty(12, 16)
        controls.add(JPanel(BorderLayout()).apply { add(enabled, BorderLayout.WEST) }, BorderLayout.NORTH)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.setShowGrid(false)
        val toolbar = ToolbarDecorator.createDecorator(table)
            .setToolbarPosition(ActionToolbarPosition.RIGHT)
            .setAddAction { edit(-1) }
            .setEditAction { edit(table.selectedRow) }
            .setRemoveAction {
                val row = table.selectedRow
                if (row in rules.indices) {
                    rules.removeAt(row)
                    model.fireTableDataChanged()
                }
            }
            .setMoveUpAction { move(-1) }
            .setMoveDownAction { move(1) }
            .setPreferredSize(Dimension(0, JBUI.scale(160)))
        controls.add(toolbar.createPanel(), BorderLayout.CENTER)
        controls.add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(JBLabel(message("naming.abbreviations")).apply { labelFor = abbreviations }, BorderLayout.NORTH)
            add(abbreviations, BorderLayout.CENTER)
            add(JBLabel(message("naming.order"), UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER), BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        addPanelToWatch(controls)
    }

    private fun project(): Project = contextProject
        ?: CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(controls))
        ?: ProjectManager.getInstance().defaultProject

    private fun profile() = project().let {
        val manager = if (it.isDefault) InspectionProfileManager.getInstance() else InspectionProfileManager.getInstance(it)
        manager.currentProfile
    }

    private fun inspectionEnabled(): Boolean {
        val profile = profile()
        profile.getInspectionTool(InconsistentNamingInspection.ID, project())
        return HighlightDisplayKey.find(InconsistentNamingInspection.ID)?.let(profile::isToolEnabled) == true
    }

    private fun move(delta: Int) {
        val row = table.selectedRow
        val target = row + delta
        if (row in rules.indices && target in rules.indices) {
            Collections.swap(rules, row, target)
            model.fireTableDataChanged()
            table.setRowSelectionInterval(target, target)
        }
    }

    private fun edit(row: Int) {
        if (row < -1 || row >= rules.size) {
            return
        }
        val dialog = NamingRuleDialog(project(), rules.getOrNull(row) ?: NamingRule(
            message("naming.new.rule"), NamingRule.Category.ALL, NamingRule.Style.SNAKE_CASE, "", "",
        ))
        if (dialog.showAndGet()) {
            if (row < 0) {
                rules.add(dialog.rule())
            } else {
                rules[row] = dialog.rule()
            }
            model.fireTableDataChanged()
            val selected = if (row < 0) rules.lastIndex else row
            table.setRowSelectionInterval(selected, selected)
        }
    }

    override fun apply(settings: CodeStyleSettings) {
        val custom = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
        custom.setNamingRules(rules)
        custom.NAMING_ABBREVIATIONS = abbreviations.text.trim()
        if (enabled.isSelected != initialEnabled) {
            val draft = profile().modifiableModel
            val project = project()
            draft.initInspectionTools(project)
            draft.setToolEnabled(InconsistentNamingInspection.ID, enabled.isSelected, project)
            draft.commit()
            initialEnabled = enabled.isSelected
        }
    }

    override fun resetImpl(settings: CodeStyleSettings) {
        val custom = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
        rules.clear()
        rules.addAll(custom.namingRules)
        abbreviations.text = custom.NAMING_ABBREVIATIONS
        initialEnabled = inspectionEnabled()
        enabled.isSelected = initialEnabled
        model.fireTableDataChanged()
    }

    override fun isModified(settings: CodeStyleSettings): Boolean {
        val custom = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
        return rules != custom.namingRules || abbreviations.text.trim() != custom.NAMING_ABBREVIATIONS
            || enabled.isSelected != initialEnabled
    }

    override fun getTabTitle() = message("naming.tab")
    override fun getPanel() = controls
    override fun getFileType() = SlangFileType.INSTANCE
    override fun getRightMargin() = 0
    override fun getPreviewText() = ""
    override fun createHighlighter(scheme: EditorColorsScheme): EditorHighlighter? = null

    private inner class RulesModel : AbstractTableModel() {
        override fun getRowCount() = rules.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = message(if (column == 0) "naming.rule.name" else "naming.preview")
        override fun getValueAt(row: Int, column: Int): Any {
            val rule = rules[row]
            return if (column == 0) rule.name() else rule.variants().map { NamingRule.label(it.style()) }
                .distinct().joinToString(", ")
        }
    }
}
