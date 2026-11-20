package slanglsp.server

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.codeStyle.CodeStyleSettings
import java.util.concurrent.atomic.AtomicLong
import slanglsp.formatting.clang.ClangFormatStyle
import slanglsp.server.nativeclient.notifyNativeSettingsChanged
import slanglsp.settings.codestyle.SlangCodeStyleSettings
import slanglsp.settings.project.ProjectSettings

internal object ServerSettings {
    private val generation = Key.create<AtomicLong>("slang.server.analysisGeneration")

    fun notifySettingsChanged(project: Project) {
        invalidateAnalysis(project)
        notifyNativeSettingsChanged(project)
    }

    fun analysisGeneration(project: Project): Long {
        return project.getUserData(generation)?.get() ?: 0
    }

    fun invalidateAnalysis(project: Project) {
        synchronized(generation) {
            project.getUserData(generation)?.incrementAndGet() ?: project.putUserData(generation, AtomicLong(1))
        }
    }

    fun settings(project: Project, codeStyle: CodeStyleSettings): MutableMap<String?, Any?> {
        val state = ProjectSettings.getInstance(project).getState()
        val settings = state.createServerSettings()
        val formatting = codeStyle.getCustomSettings(SlangCodeStyleSettings::class.java)
        if (formatting.USE_CLANG_FORMAT) {
            settings["slang.format.clangFormatLocation"] = formatting.clangFormatExecutable() ?: ""
        }
        if (state.clangFormatFallbackStyle.isBlank()) {
            settings["slang.format.clangFormatFallbackStyle"] = ClangFormatStyle.clangFormatStyle(codeStyle)
        }
        return settings
    }
}
