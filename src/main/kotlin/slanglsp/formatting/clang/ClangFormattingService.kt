package slanglsp.formatting.clang

import com.intellij.application.options.CodeStyle
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiFile
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.Path
import kotlin.io.path.isExecutable
import slanglsp.analysis.AnalysisDeadline
import slanglsp.analysis.ProjectTrust
import slanglsp.language.SlangLanguage
import slanglsp.server.ServerSettings
import slanglsp.server.nativeclient.NativeFormattingRequests
import slanglsp.settings.codestyle.SlangCodeStyleSettings

/** Routes explicit external formatting through slangd; the platform owns edit application. */
@OptIn(ExperimentalAtomicApi::class)
class ClangFormattingService : AsyncDocumentFormattingService() {
    override fun getFeatures(): MutableSet<FormattingService.Feature?> {
        return mutableSetOf(FormattingService.Feature.FORMAT_FRAGMENTS)
    }

    override fun canFormat(file: PsiFile): Boolean {
        val virtualFile = file.virtualFile ?: return false

        return file.language.isKindOf(SlangLanguage.INSTANCE) &&
            virtualFile.isInLocalFileSystem &&
            CodeStyle.getSettings(file)
                .getCustomSettings(SlangCodeStyleSettings::class.java)
                .USE_CLANG_FORMAT
    }

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask {
        val file = request.context.containingFile
        val settings = request.context.codeStyleSettings
        val serverSettings = ServerSettings.settings(file.project, settings)
        val executable =
            settings
                .getCustomSettings(SlangCodeStyleSettings::class.java)
                .clangFormatExecutable()
        val indent = settings.getIndentOptions(file.fileType)
        val indentSize = indent.INDENT_SIZE
        val insertSpaces = !indent.USE_TAB_CHARACTER
        val text = request.documentText
        val ranges = request.formattingRanges.map { checkNotNull(it) }
        val source = file.virtualFile.toNioPath()
        val cancelled = AtomicBoolean(false)
        val process = AtomicReference<CapturingProcessHandler?>(null)
        val active = AtomicReference<CompletableFuture<String?>?>(null)

        return object : FormattingTask {
            override fun run() {
                try {
                    AnalysisDeadline.open().use {
                        if (cancelled.load()) {
                            return
                        }

                        check(ProjectTrust.isTrusted(file.project)) { "Clang-format requires a trusted project" }

                        check(!executable.isNullOrBlank()) { "Selected clang-format executable is unavailable" }
                        val isPath = '/' in executable || '\\' in executable
                        check(!isPath || Path(executable).isExecutable()) {
                            "clang-format executable is unavailable: $executable"
                        }

                        FormattingConfiguration.validate(source, executable, serverSettings) { handler ->
                            process.store(handler)

                            if (cancelled.load()) {
                                handler.destroyProcess()
                            }
                        }

                        if (cancelled.load()) {
                            return
                        }

                        val future = file.project.service<NativeFormattingRequests>()
                            .format(file.virtualFile, text, ranges, indentSize, insertSpaces, serverSettings)
                        active.store(future)
                        val formatted = try {
                            if (cancelled.load()) {
                                return
                            }
                            AnalysisDeadline.await(future)
                        } finally {
                            if (!future.isDone) {
                                future.cancel(true)
                            }
                            active.store(null)
                        }

                        if (!cancelled.load()) {
                            request.onTextReady(formatted?.takeUnless { it == text })
                        }
                    }
                } catch (failure: ProcessCanceledException) {
                    throw failure
                } catch (failure: Exception) {
                    if (!cancelled.load()) {
                        val cause = failure.cause ?: failure
                        request.onError(name, cause.message ?: cause.toString())
                    }
                }
            }

            override fun cancel(): Boolean {
                cancelled.store(true)
                active.load()?.cancel(true)
                process.load()?.destroyProcess()

                return true
            }

            override fun isRunUnderProgress(): Boolean {
                return true
            }
        }
    }

    override fun getNotificationGroupId(): String {
        return "Slang LSP"
    }

    override fun getName(): String {
        return "Slang clang-format"
    }
}
