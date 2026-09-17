package slanglsp;

import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;

class SlangSettingsGuiTest {
    @Test void vcpkgCheckboxRoundTripsThroughInstrumentedForm() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var gui = new SlangConfigurableGUI();
            var state = new SlangPersistentStateConfig.State();
            assertNotNull(gui.getRootPanel());
            gui.setGUIStateWithState(state);
            assertTrue(gui.deriveStateFromGUI().useLocalVcpkgSlangd);
            state.useLocalVcpkgSlangd = false;
            gui.setGUIStateWithState(state);
            assertFalse(gui.deriveStateFromGUI().useLocalVcpkgSlangd);
        });
    }
}
