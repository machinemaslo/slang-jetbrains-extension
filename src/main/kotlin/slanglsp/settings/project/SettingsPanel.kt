package slanglsp.settings.project

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.setToolTipText
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import slanglsp.SlangBundle
import slanglsp.discovery.VcpkgDiscoveryService
import slanglsp.server.ServerSettings
import slanglsp.server.nativeclient.SlangLspProvider

internal class SettingsPanel {
    private var config: ProjectSettings? = null
    private var project: Project? = null
    val rootPanel: JPanel = JPanel(BorderLayout())
    private val includePaths: JPanel = listPanel()
    private val macros: JPanel = listPanel()
    private val includePathsHelp = tooltip(SlangBundle.message("settings.path.to.an.include.directory"))
    private val macrosHelp = tooltip(SlangBundle.message("settings.examples.my.macro.or.my.value.1"))
    private val slangdDirectory = JTextField(30)
    private val useVcpkg = JCheckBox(SlangBundle.message("settings.use.local.vcpkg.slangd.package.if.found"))
    private val commitCharacters = ComboBox<String?>(arrayOf("off", "membersOnly", "on"))
    private val deducedTypes = JCheckBox(SlangBundle.message("settings.show.inlay.hints.for.deduced.types"))
    private val parameterNames = JCheckBox(SlangBundle.message("settings.show.inlay.hints.for.parameter.names"))
    private val searchWorkspace =
        JCheckBox(SlangBundle.message("settings.search.workspace.subdirectories.for.imports.and.includes"))
    private val checkUnusedSymbols = JCheckBox(SlangBundle.message("settings.check.unused.symbols"))
    private val formatOnType = JCheckBox(SlangBundle.message("settings.enable.formatting.while.typing"))
    private var initialState = ProjectSettings.State()
    private val onTypeLineBreaks =
        JCheckBox(SlangBundle.message("settings.allow.line.break.changes.while.formatting.on.type"))
    private val rangeLineBreaks =
        JCheckBox(SlangBundle.message("settings.allow.line.break.changes.while.formatting.a.selection"))

    init {
        commitCharacters.renderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    selected: Boolean,
                    focus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, selected, focus)

                    if (value is String) {
                        text =
                            when (value) {
                                "off" -> SlangBundle.message("settings.commit.off")
                                "membersOnly" -> SlangBundle.message("settings.commit.members.only")
                                "on" -> SlangBundle.message("settings.commit.on")
                                else -> value
                            }
                    }

                    return this
                }
            }
        slangdDirectory.setToolTipText(
            tooltip(
                SlangBundle.message(
                    "settings.optional.directory.containing.slangd.takes.priority.over.vcpkg.and.path")))
        checkUnusedSymbols.setToolTipText(tooltip(SlangBundle.message("settings.check.unused.symbols.help")))
        val general = panel {
            row(SlangBundle.message("settings.additional.include.import.paths")) {
                cell(listEditor(includePaths, includePathsHelp)).align(AlignX.FILL)
            }
            row(SlangBundle.message("settings.predefined.macros")) {
                cell(listEditor(macros, macrosHelp)).align(AlignX.FILL)
            }
            row(SlangBundle.message("settings.slangd.directory")) {
                cell(slangdDirectory).align(AlignX.FILL)
            }
            row { cell(useVcpkg) }
            row(SlangBundle.message("settings.completion.commit.characters")) {
                cell(commitCharacters)
            }
            row { cell(deducedTypes) }
            row { cell(parameterNames) }
            row { cell(searchWorkspace) }
            row { cell(checkUnusedSymbols) }
        }
        val formatting = panel {
            row { cell(formatOnType) }
            row { cell(onTypeLineBreaks) }
            row { cell(rangeLineBreaks) }
            row { label(SlangBundle.message("settings.formatter.settings.location")) }
        }

        val tabs = JBTabbedPane()
        tabs.addTab(SlangBundle.message("settings.general"), scrollable(general))
        tabs.addTab(SlangBundle.message("settings.formatting"), scrollable(formatting))
        rootPanel.add(tabs, BorderLayout.CENTER)
    }

    fun createUI(project: Project) {
        this.project = project
        config = ProjectSettings.getInstance(project)
        reset()
    }

    fun deriveStateFromGUI(): ProjectSettings.State =
        initialState.copy(
            additionalIncludePaths = readRows(includePaths),
            predefinedMacros = readRows(macros),
            explicitSlangdLocation = slangdDirectory.text.trim { it <= ' ' },
            useLocalVcpkgSlangd = useVcpkg.isSelected,
            enableCommitCharactersInAutoCompletion =
                commitCharacters.selectedItem as? String ?: initialState.enableCommitCharactersInAutoCompletion,
            enableInlayHintsForDeducedTypes = deducedTypes.isSelected,
            enableInlayHintsForParameterNames = parameterNames.isSelected,
            enableSearchingSubDirectoriesOfWorkspace = searchWorkspace.isSelected,
            useSlangdUnusedDiagnosticsOnly = !checkUnusedSymbols.isSelected,
            enableFormatOnType = formatOnType.isSelected,
            allowLineBreakChangesInOnTypeFormatting = onTypeLineBreaks.isSelected,
            allowLineBreakChangesInRangeFormatting = rangeLineBreaks.isSelected)

    fun setGUIStateWithState(state: ProjectSettings.State) {
        initialState = state.snapshot()
        setRows(includePaths, state.additionalIncludePaths, includePathsHelp)
        setRows(macros, state.predefinedMacros, macrosHelp)
        slangdDirectory.text = state.explicitSlangdLocation
        useVcpkg.isSelected = state.useLocalVcpkgSlangd
        commitCharacters.selectedItem = state.enableCommitCharactersInAutoCompletion
        deducedTypes.isSelected = state.enableInlayHintsForDeducedTypes
        parameterNames.isSelected = state.enableInlayHintsForParameterNames
        searchWorkspace.isSelected = state.enableSearchingSubDirectoriesOfWorkspace
        checkUnusedSymbols.isSelected = !state.useSlangdUnusedDiagnosticsOnly
        formatOnType.isSelected = state.enableFormatOnType
        onTypeLineBreaks.isSelected = state.allowLineBreakChangesInOnTypeFormatting
        rangeLineBreaks.isSelected = state.allowLineBreakChangesInRangeFormatting
    }

    fun apply() {
        val project = checkNotNull(project)
        val next = deriveStateFromGUI()
        val previous = config!!.getState()
        val executableChanged =
            next.useLocalVcpkgSlangd != previous.useLocalVcpkgSlangd ||
                next.explicitSlangdLocation != previous.explicitSlangdLocation
        config!!.setState(next)

        if (executableChanged) {
            project.service<VcpkgDiscoveryService>().invalidate()
            SlangLspProvider.restart(project)
        }

        ServerSettings.notifySettingsChanged(project)
        DaemonCodeAnalyzer.getInstance(project).settingsChanged()
    }

    fun reset() {
        setGUIStateWithState(config!!.getState())
    }

    val isModified: Boolean
        get() = config!!.getState() != deriveStateFromGUI()

    companion object {
        private fun scrollable(form: JPanel): JComponent {
            val wrapper = JPanel(BorderLayout())
            wrapper.add(form, BorderLayout.NORTH)
            val scroll = JBScrollPane(wrapper)
            scroll.setBorder(JBUI.Borders.empty())

            return scroll
        }

        private fun listPanel(): JPanel {
            val panel = JPanel()
            panel.setLayout(BoxLayout(panel, BoxLayout.Y_AXIS))

            return panel
        }

        private fun tooltip(text: String): HtmlChunk = HtmlChunk.text(text).wrapWith("html")

        private fun listEditor(rows: JPanel, help: HtmlChunk): JPanel {
            val editor = JPanel(BorderLayout(0, JBUI.scale(4)))
            val add = JButton(SlangBundle.message("settings.add"))
            add.setToolTipText(help)
            add.addActionListener { addRow(rows, "", help) }
            val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            toolbar.add(add)
            editor.add(rows, BorderLayout.CENTER)
            editor.add(toolbar, BorderLayout.SOUTH)
            rows.setToolTipText(help)

            return editor
        }

        private fun addRow(rows: JPanel, value: String, help: HtmlChunk) {
            val row = JPanel(BorderLayout(JBUI.scale(4), 0))
            val field = JTextField(value, 25)
            field.setToolTipText(help)
            val remove = JButton(SlangBundle.message("settings.remove"))
            remove.addActionListener {
                rows.remove(row)
                rows.revalidate()
                rows.repaint()
            }
            row.add(field, BorderLayout.CENTER)
            row.add(remove, BorderLayout.EAST)
            rows.add(row)
            rows.revalidate()
            rows.repaint()
        }

        private fun readRows(rows: JPanel): MutableList<String?> {
            val values: MutableList<String?> = ArrayList()

            for (component in rows.components) {
                val row = component as JPanel
                val field = (row.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER) as JTextField
                val value = field.text.trim { it <= ' ' }

                if (value.isNotEmpty()) {
                    values.add(value)
                }
            }

            return values
        }

        private fun setRows(rows: JPanel, values: MutableList<String?>, help: HtmlChunk) {
            rows.removeAll()

            for (value in values) {
                addRow(rows, value!!, help)
            }
            rows.revalidate()
            rows.repaint()
        }
    }
}
