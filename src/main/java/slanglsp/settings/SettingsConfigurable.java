package slanglsp.settings;

import slanglsp.SlangBundle;

import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;

public final class SettingsConfigurable implements SearchableConfigurable {
    private final Project project;
    private SettingsPanel gui;

    public SettingsConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String getDisplayName() {
        return SlangBundle.message("settings.slang.display.name");
    }

    @Override
    public @NotNull String getId() {
        return "slanglsp.SlangConfigurable";
    }

    @Override
    public JComponent createComponent() {
        if (gui == null) {
            gui = new SettingsPanel();
            gui.createUI(project);
        }

        return gui.getRootPanel();
    }

    @Override
    public boolean isModified() {
        return gui != null && gui.isModified();
    }

    @Override
    public void apply() {
        if (gui != null)
            gui.apply();
    }

    @Override
    public void reset() {
        if (gui != null)
            gui.reset();
    }

    @Override
    public void disposeUIResources() {
        gui = null;
    }
}
