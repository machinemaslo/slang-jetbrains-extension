package slanglsp.settings.codestyle.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.util.Disposer
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.treeStructure.treetable.TreeTable
import java.awt.Component
import java.awt.Container
import java.nio.file.Path
import java.util.*
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import slanglsp.FormattingFixtures
import slanglsp.SlangBundle.message
import slanglsp.formatting.clang.BundledClangFormat.executable
import slanglsp.formatting.clang.ClangFormatSettingsImporter.Options.parse
import slanglsp.settings.codestyle.CodeStyleProvider
import slanglsp.settings.codestyle.SlangCodeStyleSettings

internal class FormatterSettingsTest {
    @Test
    @Throws(Exception::class)
    fun completeCodeStylePanelBuildsWithGeneralSelected() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("CodeStyleTabs").getFixture()
        runInEdtAndWait { fixture.setUp() }
        try {
            runInEdtAndWait {
                val manager = CodeStyleSettingsManager.getInstance(fixture.project)
                val settings = manager.createSettings()
                val configurable = CodeStyleProvider().createConfigurable(settings, manager.cloneSettings(settings))
                try {
                    val component = configurable.createComponent()
                    Assertions.assertNotNull(component)
                    configurable.reset()
                    Assertions.assertFalse(configurable.isModified)
                    val tabs: JBTabs? = findTabs(component!!)
                    Assertions.assertNotNull(tabs)
                    Assertions.assertNotNull(tabs!!.selectedInfo)
                    Assertions.assertEquals("General", tabs.selectedInfo!!.text)
                    Assertions.assertEquals(8, tabs.tabCount)

                    val indents =
                        Assertions.assertInstanceOf<OnePixelSplitter?>(
                            OnePixelSplitter::class.java, unwrapTab(tabs.getTabAt(1).component))
                    val braces =
                        Assertions.assertInstanceOf<OnePixelSplitter?>(
                            OnePixelSplitter::class.java, unwrapTab(tabs.getTabAt(3).component))
                    val blankLines =
                        Assertions.assertInstanceOf<OnePixelSplitter?>(
                            OnePixelSplitter::class.java, unwrapTab(tabs.getTabAt(4).component))
                    for (tab in tabs.tabs) {
                        if (tab.text == message("naming.tab")) {
                            Assertions.assertFalse(unwrapTab(tab.component) is OnePixelSplitter)
                            continue
                        }
                        val splitter =
                            Assertions.assertInstanceOf<OnePixelSplitter?>(
                                OnePixelSplitter::class.java, unwrapTab(tab.component))
                        Assertions.assertTrue(splitter!!.isVertical, tab.text)
                    }
                    val hint =
                        Assertions.assertInstanceOf<JLabel?>(
                            JLabel::class.java, blankLines!!.secondComponent.getComponent(0))
                    Assertions.assertEquals("Click an option to see preview", hint!!.text)
                    Assertions.assertTrue(hint.isVisible)
                    selectOption(blankLines, message("settings.code.style.blank.lines.code"))
                    Assertions.assertFalse(hint.isVisible)
                    val spaces =
                        Assertions.assertInstanceOf<OnePixelSplitter?>(
                            OnePixelSplitter::class.java, unwrapTab(tabs.getTabAt(6).component))
                    selectOption(spaces!!, message("settings.code.style.spaces.before.comma"))
                    val preview = spaces.secondComponent.getComponent(1)
                    val explanation = findComponent(preview, JLabel::class.java)
                    Assertions.assertNotNull(explanation)
                    Assertions.assertTrue(explanation!!.text.contains("Before comma"))
                    val editor =
                        EditorFactory.getInstance()
                            .editorList
                            .stream()
                            .filter { item: Editor? -> SwingUtilities.isDescendingFrom(item!!.component, preview) }
                            .findFirst()
                            .orElseThrow()
                    val before = editor.document.text
                    Assertions.assertTrue(before.contains("evaluate"), before)
                    selectOption(spaces, message("settings.code.style.spaces.after.comma"))
                    Assertions.assertEquals(before, editor.document.text)
                    Assertions.assertTrue(explanation.text.contains("After comma"))
                    val braceTable: TreeTable = selectOption(braces!!, message("settings.code.style.function_braces"))
                    Assertions.assertEquals(2, braceTable.columnCount)
                    val row = braceTable.selectedRow
                    Assertions.assertTrue(braceTable.editCellAt(row, 1))
                    Assertions.assertEquals(row, braceTable.getEditingRow())
                    Assertions.assertEquals(1, braceTable.getEditingColumn())
                    val placements =
                        Assertions.assertInstanceOf<JComboBox<*>?>(JComboBox::class.java, braceTable.editorComponent)
                    Assertions.assertEquals("Allman", placements!!.selectedItem)
                    Assertions.assertEquals(3, placements.itemCount)
                    for (i in 0..<placements.itemCount) {
                        Assertions.assertNotEquals("Slang", placements.getItemAt(i))
                    }
                    Assertions.assertFalse(configurable.isModified, "Preview selections must not change the scheme")
                    placements.setSelectedItem("K&R")
                    Assertions.assertTrue(braceTable.getCellEditor().stopCellEditing())
                    Assertions.assertTrue(configurable.isModified)
                    val previousIndents = indents!!.proportion
                    try {
                        indents.setProportion(0.7f)
                        val previousBraces = braces.proportion
                        blankLines.setProportion(0.4f)
                        Assertions.assertEquals(0.7f, indents.proportion)
                        Assertions.assertEquals(previousBraces, braces.proportion)
                    } finally {
                        indents.setProportion(previousIndents)
                    }
                } finally {
                    configurable.disposeUIResources()
                }
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    @ParameterizedTest(name = "clang-format={0}, import={1}, external={2}, bundled available={3}")
    @CsvSource(
        "false, false, false, true",
        "false, true, false, false",
        "true, false, true, false",
        "true, false, true, true",
        "true, false, false, true",
        "true, false, false, false")
    @Throws(Exception::class)
    fun generalTabRoundTripsEngineAndExecutableSelection(
        clang: Boolean,
        readConfig: Boolean,
        external: Boolean,
        bundledAvailable: Boolean
    ) {
        val fixture =
            IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("FormatterSettings").getFixture()
        runInEdtAndWait { fixture.setUp() }
        try {
            runInEdtAndWait {
                val manager = CodeStyleSettingsManager.getInstance(fixture.project)
                val settings = manager.createSettings()
                val source = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
                Assertions.assertFalse(source.USE_CLANG_FORMAT)
                source.USE_CLANG_FORMAT = clang
                source.READ_CLANG_FORMAT = readConfig
                source.USE_CUSTOM_CLANG_FORMAT = external
                source.CLANG_FORMAT_PATH =
                    Path.of(checkNotNull(fixture.project.basePath), "tools with spaces", "clang-format")
                        .toAbsolutePath()
                        .toString()
                val panel = FormatterPanel(settings, if (bundledAvailable) "bundled fixture" else null)
                try {
                    panel.reset(settings)
                    Assertions.assertFalse(panel.isModified(settings))
                    val splitter =
                        Assertions.assertInstanceOf<OnePixelSplitter?>(OnePixelSplitter::class.java, panel.getPanel())
                    val scroll =
                        Assertions.assertInstanceOf<JScrollPane?>(JScrollPane::class.java, splitter!!.firstComponent)
                    val controls = (scroll!!.getViewport().view as JPanel).components
                    Assertions.assertEquals(
                        bundledAvailable,
                        Arrays.stream<Component?>(controls).anyMatch { component: Component? ->
                            component is JCheckBox &&
                                component.isVisible &&
                                component.text == message("settings.formatter.external")
                        })
                    val pathField =
                        Arrays.stream<Component?>(controls)
                            .filter { obj: Component? -> JPanel::class.java.isInstance(obj) }
                            .flatMap { row: Component? -> Arrays.stream<Component?>((row as JPanel).components) }
                            .filter { obj: Component? -> JTextField::class.java.isInstance(obj) }
                            .map { obj: Component? -> JTextField::class.java.cast(obj) }
                            .findFirst()
                            .orElseThrow()
                    Assertions.assertEquals(clang && (!bundledAvailable || external), pathField.isEnabled)
                    val bundled = executable()
                    Assertions.assertEquals(
                        if (!external && bundled != null) bundled else source.CLANG_FORMAT_PATH,
                        source.clangFormatExecutable())
                    val saved = manager.createSettings()
                    panel.apply(saved)
                    val result = saved.getCustomSettings(SlangCodeStyleSettings::class.java)
                    Assertions.assertEquals(clang, result.USE_CLANG_FORMAT)
                    Assertions.assertEquals(readConfig, result.READ_CLANG_FORMAT)
                    Assertions.assertEquals(external, result.USE_CUSTOM_CLANG_FORMAT)
                    Assertions.assertEquals(source.CLANG_FORMAT_PATH, result.CLANG_FORMAT_PATH)
                    if (pathField.isEnabled) {
                        pathField.text = "clang-format"
                        Assertions.assertThrows<ConfigurationException?>(ConfigurationException::class.java) {
                            panel.apply(saved)
                        }
                        pathField.text = ""
                        Assertions.assertThrows<ConfigurationException?>(ConfigurationException::class.java) {
                            panel.apply(saved)
                        }
                    }
                } finally {
                    Disposer.dispose(panel)
                }
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    @Test
    fun yamlLanguageSectionsOverrideDefaultsWithoutApplyingOtherLanguages() {
        val values = parse(FormattingFixtures.text("configuration/languages-parser/.clang-format"))
        Assertions.assertEquals(2, values["IndentWidth"])
        Assertions.assertEquals("Never", values["UseTab"])
    }

    @Test
    fun slangSectionTakesPrecedenceOverCppAndMergesBraceWrapping() {
        val values =
            parse(
                """
                ---
                BraceWrapping:
                  AfterNamespace: true
                ---
                Language: Slang
                BraceWrapping:
                  AfterFunction: false
                ---
                Language: Cpp
                BraceWrapping:
                  AfterStruct: true
                  AfterFunction: true
                
                """
                    .trimIndent())
        Assertions.assertEquals(
            mapOf("AfterNamespace" to true, "AfterStruct" to true, "AfterFunction" to false), values["BraceWrapping"])
    }

    companion object {
        private fun unwrapTab(tab: Container): Container {
            var tab = tab
            while (tab.componentCount == 1) {
                tab = tab.getComponent(0) as? Container ?: break
            }
            return tab
        }

        private fun selectOption(panel: OnePixelSplitter, label: String): TreeTable {
            val table: TreeTable? = findComponent(panel.firstComponent, TreeTable::class.java)
            Assertions.assertNotNull(table)
            val tree = table!!.tree
            val root = tree.model.root as DefaultMutableTreeNode
            val nodes = root.depthFirstEnumeration()
            while (nodes.hasMoreElements()) {
                val node = nodes.nextElement() as DefaultMutableTreeNode
                if (node.isLeaf && node.toString() == label) {
                    val path = TreePath(node.path)
                    tree.expandPath(path.parentPath)
                    val row = tree.getRowForPath(path)
                    table.setRowSelectionInterval(row, row)
                    return table
                }
            }
            return Assertions.fail("Missing option: $label")
        }

        private fun <T : Any> findComponent(component: Component?, type: Class<T>): T? {
            if (type.isInstance(component)) {
                return type.cast(component)
            }
            if (component is Container) {
                for (child in component.components) {
                    val match = findComponent(child, type)
                    if (match != null) {
                        return match
                    }
                }
            }
            return null
        }

        private fun findTabs(component: Component): JBTabs? {
            if (component is JBTabs) {
                return component
            }
            if (component is Container) {
                for (child in component.components) {
                    val tabs: JBTabs? = findTabs(child)
                    if (tabs != null) {
                        return tabs
                    }
                }
            }
            return null
        }
    }
}
