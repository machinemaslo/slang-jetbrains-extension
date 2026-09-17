package slanglsp;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SlangSettingsTest {
    @Test
    void localVcpkgSettingIsCopiedComparedAndNotSentToServer() {
        var original = new SlangPersistentStateConfig.State();
        var changed = new SlangPersistentStateConfig.State();
        assertTrue(original.useLocalVcpkgSlangd);
        changed.useLocalVcpkgSlangd = false;
        assertFalse(original.equals(changed));
        original.copyValues(changed);
        assertTrue(original.equals(changed));
        assertFalse(original.useLocalVcpkgSlangd);
        assertEquals(6, original.createJSONFromObject().size());
        assertFalse(original.createJSONFromObject().has("useLocalVcpkgSlangd"));
    }
    @Test
    void settingsPreserveJsonTypesAndValues() {
        var state = new SlangPersistentStateConfig.State();
        state.additionalIncludePaths = List.of("/shader includes", "C:\\shaders\\\"quoted\"");
        state.predefinedMacros = List.of("FEATURE", "VALUE=42");
        state.enableInlayHintsForParameterNames = false;
        JsonObject settings = state.createJSONFromObject();

        assertEquals(6, settings.size());
        var paths = settings.getAsJsonArray("slang.additionalSearchPaths");
        assertEquals(2, paths.size());
        assertEquals(state.additionalIncludePaths.get(0), paths.get(0).getAsString());
        assertEquals(state.additionalIncludePaths.get(1), paths.get(1).getAsString());
        var macros = settings.getAsJsonArray("slang.predefinedMacros");
        assertEquals(2, macros.size());
        assertEquals("FEATURE", macros.get(0).getAsString());
        assertEquals("VALUE=42", macros.get(1).getAsString());
        assertEquals("membersOnly", settings.get("slang.enableCommitCharactersInAutoCompletion").getAsString());
        for (String key : List.of("slang.inlayHints.deducedTypes", "slang.inlayHints.parameterNames", "slang.searchInAllWorkspaceDirectories")) {
            assertTrue(settings.getAsJsonPrimitive(key).isBoolean());
        }
        assertTrue(settings.get("slang.inlayHints.deducedTypes").getAsBoolean());
        assertFalse(settings.get("slang.inlayHints.parameterNames").getAsBoolean());
        assertTrue(settings.get("slang.searchInAllWorkspaceDirectories").getAsBoolean());
        assertFalse(settings.has("explicitSlangdLocation"));
    }

    @Test
    void emptyListsRemainJsonArrays() {
        var state = new SlangPersistentStateConfig.State();
        state.predefinedMacros = List.of();
        var settings = state.createJSONFromObject();
        assertEquals(0, settings.getAsJsonArray("slang.additionalSearchPaths").size());
        assertEquals(0, settings.getAsJsonArray("slang.predefinedMacros").size());
    }
}
