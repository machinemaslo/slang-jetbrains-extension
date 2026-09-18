package slanglsp;

import com.intellij.util.xmlb.XmlSerializer;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SlangSettingsGuiTest {
    @Test void formattingSurvivesUiAndPersistenceAndReachesServerSettings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var state = new SlangPersistentStateConfig.State();
            assertTrue(state.predefinedMacros.isEmpty());
            assertEquals("file", state.clangFormatStyle);
            assertTrue(state.enableFormatOnType);
            assertFalse(state.allowLineBreakChangesInOnTypeFormatting);
            assertFalse(state.allowLineBreakChangesInRangeFormatting);
            state.additionalIncludePaths = List.of("/shader includes");
            state.predefinedMacros = List.of("FEATURE=1");
            state.enableFormatOnType = false;
            state.clangFormatLocation = "/tools with spaces/clang-format";
            state.clangFormatStyle = "file:/project/.clang-format";
            state.clangFormatFallbackStyle = "LLVM";
            state.allowLineBreakChangesInOnTypeFormatting = true;
            state.allowLineBreakChangesInRangeFormatting = true;
            var gui = new SlangConfigurableGUI();
            gui.setGUIStateWithState(state);
            var saved = new SlangPersistentStateConfig.State();
            saved.copyValues(gui.deriveStateFromGUI());
            var restored = XmlSerializer.deserialize(XmlSerializer.serialize(saved), SlangPersistentStateConfig.State.class);
            assertTrue(state.equals(restored));
            Map<String, Object> settings = restored.createServerSettings();
            assertEquals(false, settings.get("slang.format.enableFormatOnType"));
            assertEquals(state.clangFormatLocation, settings.get("slang.format.clangFormatLocation"));
            assertEquals(state.clangFormatStyle, settings.get("slang.format.clangFormatStyle"));
            assertEquals("LLVM", settings.get("slang.format.clangFormatFallbackStyle"));
            assertEquals(true, settings.get("slang.format.allowLineBreakChangesInOnTypeFormatting"));
            assertEquals(true, settings.get("slang.format.allowLineBreakChangesInRangeFormatting"));
            gui.setGUIStateWithState(new SlangPersistentStateConfig.State());
            assertTrue(gui.deriveStateFromGUI().predefinedMacros.isEmpty());
            assertEquals("file", gui.deriveStateFromGUI().clangFormatStyle);
        });
    }
}
