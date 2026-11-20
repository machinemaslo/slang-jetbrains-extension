package slanglsp.settings.project

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlin.concurrent.Volatile
import slanglsp.formatting.clang.ClangFormatStyle

@Service(Service.Level.PROJECT)
@State(name = "SlangPersistentStateComponentConfig", storages = [Storage("SlangPluginSettings.xml")])
class ProjectSettings : PersistentStateComponent<ProjectSettings.State> {
    data class State(
        var additionalIncludePaths: MutableList<String?> = mutableListOf(),
        var predefinedMacros: MutableList<String?> = mutableListOf(),
        var explicitSlangdLocation: String = "",
        var useLocalVcpkgSlangd: Boolean = true,
        var enableCommitCharactersInAutoCompletion: String = "membersOnly",
        var enableInlayHintsForDeducedTypes: Boolean = true,
        var enableInlayHintsForParameterNames: Boolean = true,
        var enableSearchingSubDirectoriesOfWorkspace: Boolean = true,
        var useSlangdUnusedDiagnosticsOnly: Boolean = false,
        var enableFormatOnType: Boolean = true,
        var clangFormatLocation: String = "",
        var clangFormatStyle: String = "file",

        // Empty means the Slang code-style scheme; non-empty values remain explicit overrides.
        var clangFormatFallbackStyle: String = "",
        var allowLineBreakChangesInOnTypeFormatting: Boolean = false,
        var allowLineBreakChangesInRangeFormatting: Boolean = false
    ) {
        internal fun snapshot(): State =
            copy(
                additionalIncludePaths = additionalIncludePaths.toMutableList(),
                predefinedMacros = predefinedMacros.toMutableList())

        fun createServerSettings(): MutableMap<String?, Any?> {
            val settings: MutableMap<String?, Any?> = HashMap()
            settings["slang.additionalSearchPaths"] = additionalIncludePaths
            settings["slang.predefinedMacros"] = predefinedMacros
            settings["slang.enableCommitCharactersInAutoCompletion"] = enableCommitCharactersInAutoCompletion
            settings["slang.inlayHints.deducedTypes"] = enableInlayHintsForDeducedTypes
            settings["slang.inlayHints.parameterNames"] = enableInlayHintsForParameterNames
            settings["slang.searchInAllWorkspaceDirectories"] = enableSearchingSubDirectoriesOfWorkspace
            settings["slang.format.enableFormatOnType"] = enableFormatOnType
            settings["slang.format.clangFormatLocation"] = clangFormatLocation
            settings["slang.format.clangFormatStyle"] = clangFormatStyle
            settings["slang.format.clangFormatFallbackStyle"] = clangFormatFallbackStyle.ifBlank {
                ClangFormatStyle.DEFAULT_STYLE
            }
            settings["slang.format.allowLineBreakChangesInOnTypeFormatting"] = allowLineBreakChangesInOnTypeFormatting
            settings["slang.format.allowLineBreakChangesInRangeFormatting"] = allowLineBreakChangesInRangeFormatting

            return settings
        }
    }

    @Volatile private var state = State()

    fun setState(otherState: State) {
        state = otherState.snapshot()
    }

    // Published states are never mutated or exposed to callers.
    override fun getState(): State = state.snapshot()

    override fun loadState(config: State) {
        setState(config)
    }

    companion object {
        fun getInstance(project: Project): ProjectSettings {
            return project.service<ProjectSettings>()
        }
    }
}
