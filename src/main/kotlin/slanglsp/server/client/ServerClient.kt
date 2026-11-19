package slanglsp.server.client

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WorkspaceFolder
import slanglsp.analysis.BatchAnalysisService
import slanglsp.formatting.clang.ClangFormatStyle
import slanglsp.server.BatchConnection
import slanglsp.server.BuiltinFiles
import slanglsp.settings.codestyle.SlangCodeStyleSettings
import slanglsp.settings.project.ProjectSettings

open class ServerClient internal constructor(private val clientProject: Project) : LanguageClientImpl(clientProject) {
    @Synchronized
    override fun handleServerStatusChanged(serverStatus: ServerStatus) {
        if (isDisposed || clientProject.isDisposed) {
            return
        }

        invalidateAnalysis(clientProject)
        if (serverStatus == ServerStatus.started) {
            activeClients.add(this)
            triggerChangeConfiguration()
        }
    }

    @Synchronized
    override fun dispose() {
        activeClients.remove(this)

        if (!isDisposed) {
            invalidateAnalysis(clientProject)
            clientProject.getService(BuiltinFiles::class.java).clear()
            super.dispose()
        }
    }

    public override fun createSettings(): MutableMap<String?, Any?> {
        val settings = settings(clientProject, CodeStyle.getSettings(clientProject))
        clientProject.getService(FormattingSettings::class.java).apply(settings)
        return settings
    }

    public override fun findSettings(section: String?): Any? {
        val settings = createSettings()
        return if (section.isNullOrEmpty()) settings else settings[section]
    }

    override fun configuration(params: ConfigurationParams): CompletableFuture<MutableList<Any?>?>? {
        // Upstream's supplyAsync uses the same common pool that commit passes occupy.
        // Keep its section-selection hook, but schedule replies independently.
        return clientProject.getService<BatchAnalysisService?>(BatchAnalysisService::class.java)!!.callback {
            params.items.mapTo(ArrayList(params.items.size)) { findSettings(it.section) }
        }
    }

    override fun registerCapability(params: RegistrationParams?): CompletableFuture<Void?>? {
        return clientProject.getService<BatchAnalysisService?>(BatchAnalysisService::class.java)!!.callback {
            BatchConnection.wrapper(clientProject).registerCapability(params)
            null
        }
    }

    override fun unregisterCapability(params: UnregistrationParams?): CompletableFuture<Void?>? {
        return clientProject.getService<BatchAnalysisService?>(BatchAnalysisService::class.java)!!.callback {
            BatchConnection.wrapper(clientProject).unregisterCapability(params)
            null
        }
    }

    // LSP4IJ supplies workspace folders through its client feature API.
    // Use the same workspace-folders policy as LSP4IJ's default client, on our callback executor.
    @Suppress("UnstableApiUsage")
    override fun workspaceFolders(): CompletableFuture<MutableList<WorkspaceFolder?>?>? {
        return clientProject.getService<BatchAnalysisService?>(BatchAnalysisService::class.java)!!.callback {
            clientFeatures.workspaceFolderFeature.getInitialWorkspaceFolders(clientProject)
        }
    }

    @Synchronized
    override fun triggerChangeConfiguration() {
        if (!isDisposed && !clientProject.isDisposed && activeClients.contains(this)) {
            super.triggerChangeConfiguration()
        }
    }

    companion object {
        private val ANALYSIS_GENERATION = Key.create<AtomicLong?>("slang.server.analysisGeneration")

        fun notifySettingsChanged(project: Project) {
            invalidateAnalysis(project)
            for (client in activeClients) {
                if (client.clientProject === project) {
                    client.triggerChangeConfiguration()
                }
            }
        }

        fun analysisGeneration(project: Project): Long {
            val generation = project.getUserData(ANALYSIS_GENERATION)
            return generation?.get() ?: 0
        }

        private fun invalidateAnalysis(project: Project) {
            synchronized(ANALYSIS_GENERATION) {
                val generation = project.getUserData(ANALYSIS_GENERATION)
                generation?.incrementAndGet() ?: project.putUserData(ANALYSIS_GENERATION, AtomicLong(1))
            }
        }

        @JvmField val activeClients: MutableSet<ServerClient> = ConcurrentHashMap.newKeySet()

        fun settings(project: Project, codeStyle: CodeStyleSettings): MutableMap<String?, Any?> {
            val state = ProjectSettings.getInstance(project).getState()

            val settings = state.createServerSettings()
            val formatting = codeStyle.getCustomSettings(SlangCodeStyleSettings::class.java)
            if (formatting.USE_CLANG_FORMAT) {
                val executable = formatting.clangFormatExecutable()
                settings["slang.format.clangFormatLocation"] = executable ?: ""
            }
            if (state.clangFormatFallbackStyle.isBlank()) {
                settings["slang.format.clangFormatFallbackStyle"] = ClangFormatStyle.clangFormatStyle(codeStyle)
            }
            return settings
        }
    }
}
