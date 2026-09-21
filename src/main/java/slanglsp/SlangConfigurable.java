package slanglsp;

import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;

public final class SlangConfigurable implements SearchableConfigurable {
    private final Project project;
    private SlangConfigurableGUI gui;

    public SlangConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override public @NotNull String getDisplayName() { return "Slang"; }

    @Override public @NotNull String getId() { return "slanglsp.SlangConfigurable"; }

    @Override public JComponent createComponent() {
        if (gui == null) {
            gui = new SlangConfigurableGUI();
            gui.createUI(project);
        }
        return gui.getRootPanel();
    }

    @Override public boolean isModified() { return gui != null && gui.isModified(); }

    @Override public void apply() { if (gui != null) gui.apply(); }

    @Override public void reset() { if (gui != null) gui.reset(); }

    @Override public void disposeUIResources() { gui = null; }
}
