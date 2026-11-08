package slanglsp.settings;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettingsSnapshotTest {
    @Test
    void settingsAreDetachedFromInputsAndReturnedSnapshots() {
        var config = new ProjectSettings();
        var input = new ProjectSettings.State();
        input.additionalIncludePaths.add("original");
        config.setState(input);
        input.additionalIncludePaths.clear();
        input.clangFormatStyle = "changed";
        var snapshot = config.getState();
        assertEquals(List.of("original"), snapshot.additionalIncludePaths);
        assertEquals("file", snapshot.clangFormatStyle);
        snapshot.additionalIncludePaths.clear();
        assertEquals(List.of("original"), config.getState().additionalIncludePaths);

        config.loadState(input);
        input.predefinedMacros.add("AFTER_LOAD");
        assertTrue(config.getState().predefinedMacros.isEmpty());
        assertEquals("changed", config.getState().clangFormatStyle);
    }
}
