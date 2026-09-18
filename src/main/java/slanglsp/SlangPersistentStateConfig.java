package slanglsp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;

@State(
    name="SlangPersistentStateComponentConfig",
    storages = {
        @Storage("SlangPluginSettings.xml")
    }
)
class SlangPersistentStateConfig implements PersistentStateComponent<SlangPersistentStateConfig.State>
{
    static class State
    {
        public List<String> additionalIncludePaths = new ArrayList<>();
        public List<String> predefinedMacros = new ArrayList<>();

        public String explicitSlangdLocation = "";
        public boolean useLocalVcpkgSlangd = true;
        public String enableCommitCharactersInAutoCompletion = "membersOnly";

        public Boolean enableInlayHintsForDeducedTypes = true;
        public Boolean enableInlayHintsForParameterNames = true;
        public Boolean enableSearchingSubDirectoriesOfWorkspace = true;

        public boolean enableFormatOnType = true;
        public String clangFormatLocation = "";
        public String clangFormatStyle = "file";
        public String clangFormatFallbackStyle = "{BasedOnStyle: Microsoft, BreakBeforeBraces: Allman, ColumnLimit: 0}";
        public boolean allowLineBreakChangesInOnTypeFormatting = false;
        public boolean allowLineBreakChangesInRangeFormatting = false;

        public void copyValues(State otherState)
        {
            additionalIncludePaths = new ArrayList<>(otherState.additionalIncludePaths);
            predefinedMacros = new ArrayList<>(otherState.predefinedMacros);
            explicitSlangdLocation = otherState.explicitSlangdLocation;
            useLocalVcpkgSlangd = otherState.useLocalVcpkgSlangd;
            enableCommitCharactersInAutoCompletion = otherState.enableCommitCharactersInAutoCompletion;
            enableInlayHintsForDeducedTypes = otherState.enableInlayHintsForDeducedTypes;
            enableInlayHintsForParameterNames = otherState.enableInlayHintsForParameterNames;
            enableSearchingSubDirectoriesOfWorkspace = otherState.enableSearchingSubDirectoriesOfWorkspace;
            enableFormatOnType = otherState.enableFormatOnType;
            clangFormatLocation = otherState.clangFormatLocation;
            clangFormatStyle = otherState.clangFormatStyle;
            clangFormatFallbackStyle = otherState.clangFormatFallbackStyle;
            allowLineBreakChangesInOnTypeFormatting = otherState.allowLineBreakChangesInOnTypeFormatting;
            allowLineBreakChangesInRangeFormatting = otherState.allowLineBreakChangesInRangeFormatting;
        }
        public boolean equals(State other)
        {
            return true
                && additionalIncludePaths.equals(other.additionalIncludePaths)
                && predefinedMacros.equals(other.predefinedMacros)
                && explicitSlangdLocation.equals(other.explicitSlangdLocation)
                && useLocalVcpkgSlangd == other.useLocalVcpkgSlangd
                && enableCommitCharactersInAutoCompletion.equals(other.enableCommitCharactersInAutoCompletion)
                && enableInlayHintsForDeducedTypes.equals(other.enableInlayHintsForDeducedTypes)
                && enableInlayHintsForParameterNames.equals(other.enableInlayHintsForParameterNames)
                && enableSearchingSubDirectoriesOfWorkspace.equals(other.enableSearchingSubDirectoriesOfWorkspace)
                && enableFormatOnType == other.enableFormatOnType
                && clangFormatLocation.equals(other.clangFormatLocation)
                && clangFormatStyle.equals(other.clangFormatStyle)
                && clangFormatFallbackStyle.equals(other.clangFormatFallbackStyle)
                && allowLineBreakChangesInOnTypeFormatting == other.allowLineBreakChangesInOnTypeFormatting
                && allowLineBreakChangesInRangeFormatting == other.allowLineBreakChangesInRangeFormatting
                ;
        }

        Map<String, Object> createServerSettings()
        {
            Map<String, Object> settings = new HashMap<>();
            settings.put("slang.additionalSearchPaths", additionalIncludePaths);
            settings.put("slang.predefinedMacros", predefinedMacros);
            settings.put("slang.enableCommitCharactersInAutoCompletion", enableCommitCharactersInAutoCompletion);
            settings.put("slang.inlayHints.deducedTypes", enableInlayHintsForDeducedTypes);
            settings.put("slang.inlayHints.parameterNames", enableInlayHintsForParameterNames);
            settings.put("slang.searchInAllWorkspaceDirectories", enableSearchingSubDirectoriesOfWorkspace);
            settings.put("slang.format.enableFormatOnType", enableFormatOnType);
            settings.put("slang.format.clangFormatLocation", clangFormatLocation);
            settings.put("slang.format.clangFormatStyle", clangFormatStyle);
            settings.put("slang.format.clangFormatFallbackStyle", clangFormatFallbackStyle);
            settings.put("slang.format.allowLineBreakChangesInOnTypeFormatting", allowLineBreakChangesInOnTypeFormatting);
            settings.put("slang.format.allowLineBreakChangesInRangeFormatting", allowLineBreakChangesInRangeFormatting);
            return settings;
        }

    }

    @NotNull
    private State state = new State();

    void setState(State otherState)
    {
        state.copyValues(otherState);
    }

    @NotNull
    @Override
    public State getState()
    {
        return state;
    }

    @Override
    public void loadState(@NotNull State config)
    {
        state = config;
    }


    @NotNull
    public static SlangPersistentStateConfig getInstance(Project project)
    {
        return project.getService(SlangPersistentStateConfig.class);
    }
}
