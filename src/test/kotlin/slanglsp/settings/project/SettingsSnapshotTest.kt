package slanglsp.settings.project

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class SettingsSnapshotTest {
    @Test
    fun settingsAreDetachedFromInputsAndReturnedSnapshots() {
        val config = ProjectSettings()
        val input = ProjectSettings.State()
        input.additionalIncludePaths.add("original")
        config.setState(input)
        input.additionalIncludePaths.clear()
        input.clangFormatStyle = "changed"
        val snapshot = config.getState()
        Assertions.assertEquals(mutableListOf<String?>("original"), snapshot.additionalIncludePaths)
        Assertions.assertEquals("file", snapshot.clangFormatStyle)
        snapshot.additionalIncludePaths.clear()
        Assertions.assertEquals(mutableListOf<String?>("original"), config.getState().additionalIncludePaths)

        config.loadState(input)
        input.predefinedMacros.add("AFTER_LOAD")
        Assertions.assertTrue(config.getState().predefinedMacros.isEmpty())
        Assertions.assertEquals("changed", config.getState().clangFormatStyle)
    }
}
