package slanglsp.server.nativeclient

import com.intellij.application.options.CodeStyle
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspHoverDisabled
import com.intellij.platform.lsp.api.customization.LspOnTypeFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspRenameDisabled
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.platform.lsp.api.stopAndRestartClientsIfNeeded
import com.intellij.psi.PsiFile
import kotlin.concurrent.Volatile
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.InitializeResult
import slanglsp.SlangBundle
import slanglsp.highlighting.semanticColor
import slanglsp.language.SlangFileType
import slanglsp.language.SlangLanguage
import slanglsp.server.BuiltinFiles
import slanglsp.server.BuiltinFiles.BuiltinFile
import slanglsp.server.ServerConnection
import slanglsp.server.ServerSettings

internal class SlangLspProvider : LspIntegrationProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        clientStarter: LspIntegrationProvider.LspClientStarter,
    ) {
        if (supports(file)) {
            clientStarter.ensureClientStarted(SlangLspDescriptor(project))
        }
    }
}

internal fun restartSlangClient(project: Project) {
    if (!project.isDefault && !project.isDisposed) {
        project.service<NativeVerification>().reset()
        LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded<SlangLspProvider>()
    }
}

private class SlangLspDescriptor(project: Project) : ProjectWideLspClientDescriptor(project, "Slang") {
    @Volatile
    private var executablePath: String? = null

    override fun isSupportedFile(file: VirtualFile): Boolean {
        return supports(file)
    }

    override fun getLanguageId(file: VirtualFile): String {
        return "slang"
    }

    override fun createCommandLine(): GeneralCommandLine {
        val executable = ServerConnection.findEditorExecutable(project)
            ?: throw ExecutionException(SlangBundle.message("server.not.found"))
        executablePath = executable

        return GeneralCommandLine(executable).withWorkDirectory(project.basePath)
    }

    override fun getFileUri(file: VirtualFile): String {
        return if (file is BuiltinFile) file.uri().toString() else super.getFileUri(file)
    }

    override fun findFileByUri(fileUri: String): VirtualFile? {
        return if (BuiltinFiles.moduleName(fileUri) != null) {
            project.service<BuiltinFiles>().resolve(fileUri)
        } else {
            super.findFileByUri(fileUri)
        }
    }

    override fun getWorkspaceConfiguration(item: ConfigurationItem): Any? {
        val settings = nativeSettings(project)
        val section = item.section
        return if (section.isNullOrEmpty()) settings else settings[section]
    }

    override val lspServerListener = object : LspServerListener {
        override fun serverInitialized(params: InitializeResult) {
            if (project.isDisposed) {
                return
            }

            project.service<BuiltinFiles>().serverStarted(executablePath)
            ServerSettings.invalidateAnalysis(project)
            notifyNativeSettingsChanged(project)
        }

        override fun serverStopped(shutdownNormally: Boolean) {
            if (!project.isDisposed) {
                ServerSettings.invalidateAnalysis(project)
                project.service<BuiltinFiles>().clear()
            }
        }
    }

    override val lspCustomization = object : LspCustomization() {
        override val semanticTokensCustomizer = object : LspSemanticTokensSupport() {
            override fun shouldAskServerForSemanticTokens(psiFile: PsiFile): Boolean {
                return psiFile.language === SlangLanguage.INSTANCE
            }

            override fun getTextAttributesKey(tokenType: String, modifiers: List<String>): TextAttributesKey? {
                return semanticColor(tokenType, modifiers)
            }
        }

        override val formattingCustomizer = LspFormattingDisabled
        override val hoverCustomizer = LspHoverDisabled
        override val onTypeFormattingCustomizer = LspOnTypeFormattingDisabled
        override val renameCustomizer = LspRenameDisabled
    }
}

internal fun notifyNativeSettingsChanged(project: Project) {
    val client = runningSlangClient(project) ?: return
    val params = DidChangeConfigurationParams(nativeSettings(project))
    client.sendNotification { it.workspaceService.didChangeConfiguration(params) }
}

private fun nativeSettings(project: Project): Map<String?, Any?> {
    return ServerSettings.settings(project, CodeStyle.getSettings(project))
}

private fun supports(file: VirtualFile): Boolean {
    return file.isInLocalFileSystem && file.fileType === SlangFileType
}
