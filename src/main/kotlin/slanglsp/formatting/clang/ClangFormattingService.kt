package slanglsp.formatting.clang

import com.intellij.application.options.CodeStyle
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.features.formatting.LSPFormattingParams
import com.redhat.devtools.lsp4ij.features.formatting.LSPFormattingSupport
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.Path
import kotlin.io.path.isExecutable
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.TextEdit
import slanglsp.analysis.AnalysisDeadline
import slanglsp.language.SlangLanguage
import slanglsp.server.client.FormattingSettings
import slanglsp.server.client.ServerClient
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
        val serverSettings: MutableMap<String?, Any?> = ServerClient.settings(file.project, settings)
        val executable =
            settings
                .getCustomSettings(SlangCodeStyleSettings::class.java)
                .clangFormatExecutable()
        val indent = settings.getIndentOptions(file.fileType)
        val options = FormattingOptions(indent.INDENT_SIZE, !indent.USE_TAB_CHARACTER)
        val document = EditorFactory.getInstance().createDocument(request.documentText)
        val ranges = request.formattingRanges.map { checkNotNull(it) }
        val source = file.virtualFile.toNioPath()
        val support = FormattingSupport(file)
        val cancelled = AtomicBoolean(false)
        val process = AtomicReference<CapturingProcessHandler?>(null)

        return object : FormattingTask {
            // Closing the scopes restores the deadline and server configuration.
            override fun run() {
                try {
                    AnalysisDeadline.open().use {
                        if (cancelled.load()) {
                            return
                        }

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

                        val formatted = file.project.service<FormattingSettings>()
                            .open(serverSettings, cancelled::load)
                            .use {
                                if (cancelled.load()) {
                                    return
                                }

                                val servers =
                                    AnalysisDeadline.await(runReadActionBlocking { support.connect() }).orEmpty()
                                val server =
                                    checkNotNull(
                                        servers.firstOrNull {
                                            it?.serverDefinition?.id == "slanglsp.SlangLanguageServer"
                                        }) {
                                            "Slang language server is unavailable"
                                        }
                                server.workspaceService.didChangeConfiguration(
                                    DidChangeConfigurationParams(serverSettings),
                                )

                                val edits = linkedSetOf<TextEdit>()
                                for (range in ranges) {
                                    if (cancelled.load()) {
                                        return
                                    }

                                    val selection = range.takeUnless {
                                        it.startOffset == 0 && it.endOffset == document.textLength
                                    }
                                    val params = LSPFormattingParams(selection, document, server, options)
                                    val result: MutableList<out TextEdit?>? =
                                        AnalysisDeadline.await<MutableList<out TextEdit?>?>(
                                            runReadActionBlocking { support.getFeatureData(params) },
                                        )
                                    result.orEmpty().asSequence().filterNotNull().filterTo(edits) { edit ->
                                        selection == null || inside(edit, selection, document)
                                    }
                                }

                                LSPIJUtils.applyEdits(document, edits.toMutableList())
                            }

                        if (!cancelled.load()) {
                            request.onTextReady(formatted.takeUnless { it == document.text })
                        }
                    }
                } catch (failure: ProcessCanceledException) {
                    support.cancel()
                    throw failure
                } catch (failure: Exception) {
                    support.cancel()

                    if (!cancelled.load()) {
                        val cause = failure.cause ?: failure
                        request.onError(name, cause.message ?: cause.toString())
                    }
                }
            }

            override fun cancel(): Boolean {
                cancelled.store(true)
                support.cancel()
                process.load()?.destroyProcess()

                return true
            }

            override fun isRunUnderProgress(): Boolean {
                return true
            }
        }
    }

    private class FormattingSupport(file: PsiFile) : LSPFormattingSupport(file) {
        // Reuse LSP4IJ's document connection without overriding its feature predicates.
        @Suppress("UnstableApiUsage")
        fun connect(): CompletableFuture<MutableList<LanguageServerItem?>?>? {
            return getLanguageServers(file, null, null)
        }
    }

    override fun getNotificationGroupId(): String {
        return "Slang LSP"
    }

    override fun getName(): String {
        return "Slang clang-format"
    }
}

private fun inside(edit: TextEdit, range: TextRange, document: Document): Boolean {
    val start = LSPIJUtils.toOffset(edit.range.start, document)
    val end = LSPIJUtils.toOffset(edit.range.end, document)
    return start in range.startOffset..<range.endOffset && end <= range.endOffset
}
