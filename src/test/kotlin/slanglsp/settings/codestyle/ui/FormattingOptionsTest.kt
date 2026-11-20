package slanglsp.settings.codestyle.ui

import com.intellij.openapi.util.Disposer
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.treeStructure.treetable.TreeTable
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.tree.DefaultMutableTreeNode
import org.jetbrains.annotations.PropertyKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import slanglsp.SlangBundle.message
import slanglsp.language.SlangLanguage
import slanglsp.settings.codestyle.SlangCodeStyleSettings

class FormattingOptionsTest {
    @Test
    fun inheritedBraceStyleRefreshesWithoutAddingAChoice() = withSettings { manager ->
        val settings = manager.createSettings()
        val style = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
        style.TYPE_BRACES = 2
        style.STRUCT_BRACES = 0

        val panel = FormattingOptions.braces(settings)
        try {
            panel.reset(settings)
            assertEquals(message("settings.code.style.braces"), panel.optionTable().accessibleContext.accessibleName)
            val type = assertInstanceOf(JComboBox::class.java, panel.control("settings.code.style.type_braces"))
            val struct = assertInstanceOf(JComboBox::class.java, panel.control("settings.code.style.struct_braces"))
            assertEquals(3, struct.itemCount)
            assertEquals("Allman", struct.selectedItem)
            assertFalse(panel.isModified(settings))

            type.selectedItem = "K&R"
            assertEquals("K&R", struct.selectedItem)
            val draft = manager.cloneSettings(settings)
            val draftStyle = draft.getCustomSettings(SlangCodeStyleSettings::class.java)
            panel.apply(draft)
            assertEquals(1, draftStyle.TYPE_BRACES)
            assertEquals(0, draftStyle.STRUCT_BRACES)

            struct.selectedItem = "Allman"
            panel.apply(draft)
            assertEquals(2, draftStyle.STRUCT_BRACES)
        } finally {
            Disposer.dispose(panel)
        }
    }

    @Test
    fun checkboxAndSpinnerApplyToTheirOwnIndentSettings() = withSettings { manager ->
        val settings = manager.createSettings()
        val common = settings.getCommonSettings(SlangLanguage.INSTANCE)
        val indent = common.indentOptions ?: common.initIndentOptions()
        indent.USE_TAB_CHARACTER = false
        indent.INDENT_SIZE = 6
        indent.TAB_SIZE = 8

        val panel = FormattingOptions.indents(settings)
        try {
            panel.reset(settings)
            assertEquals(
                message("settings.code.style.group.options"), panel.optionTable().accessibleContext.accessibleName)
            assertFalse(panel.isModified(settings))
            val useTabs = assertInstanceOf(JCheckBox::class.java, panel.control("settings.code.style.use.tabs"))
            val indentSize = assertInstanceOf(JSpinner::class.java, panel.control("settings.code.style.indent.size"))
            assertFalse(useTabs.isSelected)
            assertEquals(6, indentSize.value)
            useTabs.doClick()
            indentSize.value = 2
            val draft = manager.cloneSettings(settings)
            panel.apply(draft)

            val saved = requireNotNull(draft.getCommonSettings(SlangLanguage.INSTANCE).indentOptions)
            assertTrue(saved.USE_TAB_CHARACTER)
            assertEquals(2, saved.INDENT_SIZE)
            assertEquals(8, saved.TAB_SIZE)
        } finally {
            Disposer.dispose(panel)
        }
    }

    private fun OptionPreviewPanel.optionTable(): TreeTable {
        val splitter = assertInstanceOf(OnePixelSplitter::class.java, panel)
        val scroll = assertInstanceOf(JScrollPane::class.java, splitter.firstComponent)
        return assertInstanceOf(TreeTable::class.java, scroll.viewport.view)
    }

    private fun OptionPreviewPanel.control(
        @PropertyKey(resourceBundle = "messages.SlangBundle") labelKey: String
    ): JComponent {
        val root = assertInstanceOf(DefaultMutableTreeNode::class.java, optionTable().tree.model.root)
        val label = message(labelKey)
        val binding =
            root
                .depthFirstEnumeration()
                .asSequence()
                .mapNotNull { (it as? DefaultMutableTreeNode)?.userObject as? FormattingOption.Binding }
                .single { it.definition.label == label }
        return binding.control
    }

    private fun withSettings(action: (CodeStyleSettingsManager) -> Unit) {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("FormattingOptions").fixture
        runInEdtAndWait { fixture.setUp() }
        try {
            runInEdtAndWait { action(CodeStyleSettingsManager.getInstance(fixture.project)) }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }
}
