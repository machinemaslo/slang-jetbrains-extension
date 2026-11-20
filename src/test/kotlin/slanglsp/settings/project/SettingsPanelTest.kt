package slanglsp.settings.project

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class SettingsPanelTest {
    @Test
    fun readsLegacyProjectSettingsXml() {
        val xml =
            JDOMUtil.load(
                """
            <component name="SlangPersistentStateComponentConfig">
                <option name="additionalIncludePaths">
                    <list>
                        <option value="shaders/include" />
                    </list>
                </option>
                <option name="explicitSlangdLocation" value="tools" />
                <option name="enableInlayHintsForParameterNames" value="false" />
            </component>
            """
                    .trimIndent())

        val restored = XmlSerializer.deserialize(xml, ProjectSettings.State::class.java)
        Assertions.assertEquals(listOf("shaders/include"), restored.additionalIncludePaths)
        Assertions.assertEquals("tools", restored.explicitSlangdLocation)
        Assertions.assertFalse(restored.enableInlayHintsForParameterNames)
        Assertions.assertTrue(restored.useLocalVcpkgSlangd)
    }

    @Test
    @Throws(Exception::class)
    fun formattingSurvivesUiAndPersistenceAndReachesServerSettings() {
        SwingUtilities.invokeAndWait {
            val state = ProjectSettings.State()
            Assertions.assertTrue(state.predefinedMacros.isEmpty())
            Assertions.assertEquals("file", state.clangFormatStyle)
            Assertions.assertTrue(state.enableFormatOnType)
            Assertions.assertFalse(state.allowLineBreakChangesInOnTypeFormatting)
            Assertions.assertFalse(state.allowLineBreakChangesInRangeFormatting)
            state.additionalIncludePaths = mutableListOf("/shader includes")
            state.predefinedMacros = mutableListOf("FEATURE=1")
            state.enableFormatOnType = false
            state.useSlangdUnusedDiagnosticsOnly = true
            state.clangFormatLocation = "/tools with spaces/clang-format"
            state.clangFormatStyle = "file:/project/.clang-format"
            state.clangFormatFallbackStyle = "LLVM"
            state.allowLineBreakChangesInOnTypeFormatting = true
            state.allowLineBreakChangesInRangeFormatting = true
            val gui = SettingsPanel()
            gui.setGUIStateWithState(state)
            val saved = gui.deriveStateFromGUI()
            val restored = XmlSerializer.deserialize(XmlSerializer.serialize(saved), ProjectSettings.State::class.java)
            Assertions.assertEquals(state, restored)
            Assertions.assertTrue(restored.useSlangdUnusedDiagnosticsOnly)
            Assertions.assertFalse(ProjectSettings.State().useSlangdUnusedDiagnosticsOnly)
            val settings = restored.createServerSettings()
            Assertions.assertEquals(false, settings["slang.format.enableFormatOnType"])
            Assertions.assertEquals(state.clangFormatLocation, settings["slang.format.clangFormatLocation"])
            Assertions.assertEquals(state.clangFormatStyle, settings["slang.format.clangFormatStyle"])
            Assertions.assertEquals("LLVM", settings["slang.format.clangFormatFallbackStyle"])
            Assertions.assertEquals(true, settings["slang.format.allowLineBreakChangesInOnTypeFormatting"])
            Assertions.assertEquals(true, settings["slang.format.allowLineBreakChangesInRangeFormatting"])
            gui.setGUIStateWithState(ProjectSettings.State())
            Assertions.assertTrue(gui.deriveStateFromGUI().predefinedMacros.isEmpty())
            Assertions.assertEquals("file", gui.deriveStateFromGUI().clangFormatStyle)
        }
    }
}
