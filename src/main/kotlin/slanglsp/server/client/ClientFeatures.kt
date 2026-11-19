package slanglsp.server.client

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.client.features.LSPFormattingFeature
import com.redhat.devtools.lsp4ij.client.features.LSPUsageFeature
import java.net.URI
import java.nio.file.Path
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageServer
import slanglsp.language.SlangFileType
import slanglsp.server.BuiltinFiles
import slanglsp.server.BuiltinFiles.BuiltinFile
import slanglsp.server.protocol.ProtocolCompatibility
import slanglsp.settings.codestyle.SlangCodeStyleSettings

// LSP4IJ exposes custom client features through experimental extension points.
@Suppress("UnstableApiUsage")
internal class ClientFeatures : LSPClientFeatures() {
    override fun <S : LanguageServer?> createLauncherBuilder(): Launcher.Builder<S?> {
        return ProtocolCompatibility(this)
    }

    init {
        semanticTokensFeature = SemanticTokensFeature()
        hoverFeature = HoverFeature()
        // Local smart keys own whitespace while typing. Server on-type formatting can
        // otherwise rewrite an unfinished inline body after an unrelated keystroke.
        formattingFeature =
            object : LSPFormattingFeature() {
                override fun isEnabled(file: PsiFile): Boolean {
                    return CodeStyle.getSettings(file)
                        .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                        .USE_CLANG_FORMAT
                }

                override fun isOnTypeFormattingEnabled(file: PsiFile): Boolean {
                    return false
                }
            }
        // Our Find Usages handler owns references and the indexed fallback. Avoid duplicate searches.
        usageFeature =
            object : LSPUsageFeature() {
                override fun isSupported(file: PsiFile): Boolean {
                    return false
                }
            }
    }

    override fun isEnabled(file: VirtualFile): Boolean {
        return (file !is BuiltinFile) && file.fileType === SlangFileType && super.isEnabled(file)
    }

    override fun findFileByUri(uri: String): VirtualFile? {
        if (BuiltinFiles.moduleName(uri) != null) {
            return project.getService<BuiltinFiles?>(BuiltinFiles::class.java)!!.resolve(uri)
        }

        return super.findFileByUri(uri)
    }

    override fun getFileUri(file: VirtualFile): URI? {
        return if (file is BuiltinFile) file.uri() else super.getFileUri(file)
    }

    override fun initializeParams(params: InitializeParams) {
        configureWorkspace(params, project.basePath, project.name)
        Logger.getInstance(ClientFeatures::class.java).info("Slang workspace folders: " + params.workspaceFolders)
    }

    companion object {
        @JvmStatic
        fun configureWorkspace(params: InitializeParams, basePath: String?, projectName: String) {
            if (basePath == null) {
                return
            }
            val root = Path.of(basePath).toAbsolutePath().normalize()
            val uri: String = root.toUri().toString()
            val folders = ArrayList<WorkspaceFolder?>()

            if (params.workspaceFolders != null) {
                for (folder in params.workspaceFolders) {
                    if (!isSameRoot(folder.uri, root)) {
                        folders.add(folder)
                    }
                }
            }

            folders.add(WorkspaceFolder(uri, projectName))
            params.workspaceFolders = folders
        }

        private fun isSameRoot(uri: String, root: Path): Boolean {
            return try {
                root == Path.of(URI.create(uri)).toAbsolutePath().normalize()
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }
}
