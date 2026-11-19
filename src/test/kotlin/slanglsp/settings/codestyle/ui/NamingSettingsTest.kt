package slanglsp.settings.codestyle.ui

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.profile.codeInspection.BaseInspectionProfileManager
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JTable
import javax.swing.JTextField
import org.jdom.Element
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import slanglsp.SlangBundle.message
import slanglsp.editor.inspections.naming.InconsistentNamingInspection
import slanglsp.settings.codestyle.CodeStylePresets.Slang
import slanglsp.settings.codestyle.NamingRule
import slanglsp.settings.codestyle.NamingRule.Companion.example
import slanglsp.settings.codestyle.NamingRule.Companion.slang
import slanglsp.settings.codestyle.SlangCodeStyleSettings

internal class NamingSettingsTest {
    @Test
    fun acceptsAlternativeStylesAndSuggestsTheFirstOne() {
        val rule =
            NamingRule(
                "Functions and methods",
                setOf(NamingRule.Category.FUNCTIONS, NamingRule.Category.METHODS),
                listOf(
                    NamingRule.Variant(NamingRule.Style.LOWER_CAMEL_CASE, "", ""),
                    NamingRule.Variant(NamingRule.Style.SNAKE_CASE, "", "")),
                true)
        Assertions.assertTrue(rule.matches(NamingRule.Category.METHODS))
        Assertions.assertFalse(rule.matches(NamingRule.Category.LOCALS))
        Assertions.assertEquals("already_valid", rule.suggest("already_valid", ""))
        Assertions.assertEquals("convertThis", rule.suggest("ConvertThis", ""))
        val disabled = NamingRule(rule.name, rule.categories, rule.variants, false)
        Assertions.assertEquals("ConvertThis", disabled.suggest("ConvertThis", ""))
    }

    @Test
    fun slangDefaultsFollowUpstreamDeclarations() {
        for (rule in slang()) {
            val example = example(rule.category())
            Assertions.assertEquals(example, rule.suggest(example, "GPU, CPU, API, UV, ID, BRDF, D3D"), rule.name)
        }
        val byCategory = slang().associateBy(NamingRule::category)
        Assertions.assertEquals("gCounter", byCategory[NamingRule.Category.GLOBALS]!!.suggest("counter", ""))
        Assertions.assertEquals("gGeometry", byCategory[NamingRule.Category.GLOBALS]!!.suggest("geometry", ""))
        Assertions.assertEquals("kSampleCount", byCategory[NamingRule.Category.CONSTANTS]!!.suggest("sample_count", ""))
        Assertions.assertEquals("ITransformer", byCategory[NamingRule.Category.INTERFACES]!!.suggest("transformer", ""))
        Assertions.assertEquals("IBRDF", byCategory[NamingRule.Category.INTERFACES]!!.suggest("IBRDF", "BRDF"))
        Assertions.assertEquals("Orange", byCategory[NamingRule.Category.ENUM_MEMBERS]!!.suggest("orange", ""))
        Assertions.assertEquals("d3dThing", byCategory[NamingRule.Category.LOCALS]!!.suggest("D3DThing", "D3D"))
        Assertions.assertEquals("nodeID", byCategory[NamingRule.Category.PARAMETERS]!!.suggest("node_id", "ID"))
        Assertions.assertEquals("Slang", Slang().name)
    }

    @Test
    fun convertsAcronymsAffixesAndPreservesConformingNames() {
        val snake = NamingRule("Functions", NamingRule.Category.FUNCTIONS, NamingRule.Style.SNAKE_CASE, "", "")
        Assertions.assertEquals("test_function", snake.suggest("testFunction", ""))
        Assertions.assertEquals("gpu_texture_id", snake.suggest("GPUTextureID", "GPU, ID"))
        val camel = NamingRule("Fields", NamingRule.Category.FIELDS, NamingRule.Style.LOWER_CAMEL_CASE, "m_", "_")
        Assertions.assertEquals("m_textureID_", camel.suggest("texture_id", "ID"))
        Assertions.assertEquals("m_textureID_", camel.suggest("m_textureID_", "ID"))
        Assertions.assertEquals("m_value_", camel.suggest("m_value_", ""))
        val any = NamingRule("Exemption", NamingRule.Category.ALL, NamingRule.Style.ANY, "", "")
        Assertions.assertEquals("any_STRANGE_Name", any.suggest("any_STRANGE_Name", ""))
        for (style in NamingRule.Style.entries) {
            val rule = NamingRule("Rule", NamingRule.Category.ALL, style, "", "")
            val suggested = rule.suggest("sampleGPUTexture2", "GPU")
            Assertions.assertEquals(suggested, rule.suggest(suggested, "GPU"), style.toString())
        }
    }

    @Test
    fun previewsIncludeEachVariantsAffixesAndUpdateWhileEditing() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("NamingPreview").fixture
        runInEdtAndWait { fixture.setUp() }

        try {
            runInEdtAndWait {
                val project = fixture.project
                val settings = CodeStyleSettingsManager.getInstance(project).createSettings()
                val rule =
                    NamingRule(
                        "Values",
                        setOf(NamingRule.Category.ALL),
                        listOf(
                            NamingRule.Variant(NamingRule.Style.UPPER_CAMEL_CASE, "k", ""),
                            NamingRule.Variant(NamingRule.Style.UPPER_CAMEL_CASE, "g", "_"),
                            NamingRule.Variant(NamingRule.Style.ANY, "", "_id"),
                        ),
                        enabled = false,
                    )
                settings.getCustomSettings(SlangCodeStyleSettings::class.java).setNamingRules(listOf(rule))
                val panel = NamingPanel(settings, project)
                try {
                    panel.reset(settings)
                    val table = checkNotNull(UIUtil.findComponentOfType(panel.panel, JTable::class.java))
                    Assertions.assertEquals("kUpperCamelCase, gUpperCamelCase_, sampleName_id", table.getValueAt(0, 1))
                } finally {
                    Disposer.dispose(panel)
                }

                val dialog = NamingRuleDialog(project, rule)
                try {
                    val content = dialog.createCenterPanel()
                    val styles = checkNotNull(UIUtil.findComponentOfType(content, JList::class.java))
                    val labels = UIUtil.findComponentsOfType(content, JLabel::class.java)
                    val prefix = labels.single { it.text == message("naming.prefix") }.labelFor as JTextField
                    val suffix = labels.single { it.text == message("naming.suffix") }.labelFor as JTextField

                    Assertions.assertEquals("kUpperCamelCase", renderedStyle(styles, 0))
                    Assertions.assertEquals("gUpperCamelCase_", renderedStyle(styles, 1))
                    prefix.text = "m_"
                    suffix.text = "Value"
                    Assertions.assertEquals("m_UpperCamelCaseValue", renderedStyle(styles, 0))
                    Assertions.assertEquals("gUpperCamelCase_", renderedStyle(styles, 1))
                } finally {
                    dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                }
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    @Test
    @Throws(Exception::class)
    fun rulesRoundTripCloneAndProfileCheckbox() {
        val fixture =
            IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("NamingSettings").getFixture()
        runInEdtAndWait { fixture.setUp() }
        try {
            runInEdtAndWait {
                val manager = CodeStyleSettingsManager.getInstance(fixture.project)
                val settings = manager.createSettings()
                val custom = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
                val rule =
                    NamingRule(
                        "My <fields>",
                        setOf(NamingRule.Category.FIELDS, NamingRule.Category.LOCALS),
                        listOf(
                            NamingRule.Variant(NamingRule.Style.LOWER_CAMEL_CASE, "m_", "_"),
                            NamingRule.Variant(NamingRule.Style.SNAKE_CASE, "", "")),
                        false)
                custom.setNamingRules(listOf(rule))
                custom.NAMING_ABBREVIATIONS = "GPU, API"
                val xml = Element("code_scheme")
                try {
                    settings.writeExternal(xml)
                    val loaded = manager.createSettings()
                    loaded.readExternal(xml)
                    Assertions.assertEquals(
                        custom.namingRules,
                        loaded
                            .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                            .namingRules)
                    Assertions.assertEquals(
                        custom.NAMING_ABBREVIATIONS,
                        loaded
                            .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                            .NAMING_ABBREVIATIONS)
                    val clone = manager.cloneSettings(settings)
                    val clonedCustom = clone.getCustomSettings(SlangCodeStyleSettings::class.java)
                    Assertions.assertEquals(custom, clonedCustom)
                    Assertions.assertEquals(custom.hashCode(), clonedCustom.hashCode())
                    clonedCustom.setNamingRules(emptyList())
                    Assertions.assertEquals(listOf(rule), custom.namingRules)
                    Assertions.assertNotEquals(
                        custom, clone.getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java))
                    val emptyXml = Element("code_scheme")
                    clone.writeExternal(emptyXml)
                    loaded.readExternal(emptyXml)
                    Assertions.assertTrue(
                        loaded
                            .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                            .namingRules
                            .isEmpty())
                } catch (failure: Exception) {
                    throw AssertionError(failure)
                }
                val project = fixture.project
                val editor = NamingRuleDialog(project, rule)
                try {
                    Assertions.assertEquals(rule, editor.rule())
                } finally {
                    editor.close(DialogWrapper.CANCEL_EXIT_CODE)
                }
                val panel = NamingPanel(settings, project)
                val profiles = InspectionProfileManager.getInstance(project) as BaseInspectionProfileManager
                val previous = profiles.currentProfile
                val initialize = InspectionProfileImpl.INIT_INSPECTIONS
                InspectionProfileImpl.INIT_INSPECTIONS = true
                val supplier: InspectionToolsSupplier =
                    object : InspectionToolsSupplier() {
                        override fun createTools(): MutableList<InspectionToolWrapper<*, *>?> {
                            return mutableListOf(LocalInspectionToolWrapper(InconsistentNamingInspection()))
                        }
                    }
                val profile = InspectionProfileImpl("Naming checkbox test", supplier, profiles)
                profiles.addProfile(profile)
                profiles.setRootProfile(profile.name)
                try {
                    panel.reset(settings)
                    Assertions.assertFalse(panel.isModified(settings))
                    val checkbox: JCheckBox? = findCheckbox(panel.getPanel())
                    Assertions.assertNotNull(checkbox)
                    val original = checkbox!!.isSelected
                    checkbox.setSelected(!original)
                    Assertions.assertTrue(panel.isModified(settings))
                    panel.apply(settings)
                    panel.reset(settings)
                    Assertions.assertEquals(!original, checkbox.isSelected)
                    checkbox.setSelected(original)
                    panel.apply(settings)
                } finally {
                    profiles.setRootProfile(previous.name)
                    profiles.deleteProfile(profile)
                    Disposer.dispose(supplier)
                    Disposer.dispose(panel)
                    InspectionProfileImpl.INIT_INSPECTIONS = initialize
                }
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    companion object {
        private fun <T> renderedStyle(list: JList<T>, index: Int): String {
            val cell =
                list.cellRenderer.getListCellRendererComponent(list, list.model.getElementAt(index), index, false, false)
            return (cell as JLabel).text
        }

        private fun findCheckbox(component: Component): JCheckBox? {
            if (component is JCheckBox) {
                return component
            }
            if (component is Container) {
                for (child in component.components) {
                    val checkbox: JCheckBox? = findCheckbox(child)
                    if (checkbox != null) {
                        return checkbox
                    }
                }
            }
            return null
        }
    }
}
