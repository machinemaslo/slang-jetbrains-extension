package slanglsp.formatting.clang

import com.intellij.application.options.CodeStyle
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.features.formatting.LSPFormattingParams
import com.redhat.devtools.lsp4ij.features.formatting.LSPFormattingSupport
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.TextEdit
import slanglsp.analysis.AnalysisDeadline
import slanglsp.analysis.ReadActions
import slanglsp.language.SlangLanguage
import slanglsp.server.client.FormattingSettings
import slanglsp.server.client.ServerClient
import slanglsp.settings.codestyle.SlangCodeStyleSettings

/** Routes explicit external formatting through slangd; the platform owns edit application. */
class ClangFormattingService : AsyncDocumentFormattingService() {
    override fun getFeatures(): MutableSet<FormattingService.Feature?> {
        return mutableSetOf(FormattingService.Feature.FORMAT_FRAGMENTS)
    }

    override fun canFormat(file: PsiFile): Boolean {
        return file.language.isKindOf(SlangLanguage.INSTANCE) &&
            file.virtualFile != null &&
            file.virtualFile.isInLocalFileSystem &&
            CodeStyle.getSettings(file)
                .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                .USE_CLANG_FORMAT
    }

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask {
        val file = request.context.containingFile
        val settings = request.context.codeStyleSettings
        val serverSettings: MutableMap<String?, Any?> = ServerClient.settings(file.project, settings)
        val executable =
            settings
                .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                .clangFormatExecutable()
        val indent = settings.getIndentOptions(file.fileType)
        val options = FormattingOptions(indent.INDENT_SIZE, !indent.USE_TAB_CHARACTER)
        val document = EditorFactory.getInstance().createDocument(request.documentText)
        val ranges = request.formattingRanges.map { checkNotNull(it) }
        val source = file.virtualFile.toNioPath()
        val support = FormattingSupport(file)
        val cancelled = AtomicBoolean()
        val process = AtomicReference<CapturingProcessHandler?>()
        return object : FormattingTask {
            // Closing the scopes restores the deadline and server configuration.
            override fun run() {
                try {
                    AnalysisDeadline.open().use {
                        if (cancelled.get()) {
                            return
                        }
                        check(!executable.isNullOrBlank()) { "Selected clang-format executable is unavailable" }
                        check(
                            !((executable.contains("/") || executable.contains("\\")) &&
                                !Files.isExecutable(Path.of(executable)))) {
                                "clang-format executable is unavailable: $executable"
                            }
                        FormattingConfiguration.validate(source, executable, serverSettings) { handler ->
                            process.set(handler)
                            if (cancelled.get()) {
                                handler.destroyProcess()
                            }
                        }
                        val formatted: String
                        file.project
                            .getService<FormattingSettings?>(FormattingSettings::class.java)!!
                            .open(serverSettings, cancelled::get)
                            .use {
                                if (cancelled.get()) {
                                    return
                                }
                                val servers =
                                    AnalysisDeadline.await(ReadActions.compute { support.connect() }).orEmpty()
                                val server =
                                    checkNotNull(
                                        servers.firstOrNull {
                                            it?.serverDefinition?.id == "slanglsp.SlangLanguageServer"
                                        }) {
                                            "Slang language server is unavailable"
                                        }
                                server.workspaceService.didChangeConfiguration(
                                    DidChangeConfigurationParams(serverSettings))
                                val edits = ArrayList<TextEdit?>()
                                for (range in ranges) {
                                    if (cancelled.get()) {
                                        return
                                    }
                                    val selection =
                                        if (range.startOffset == 0 && range.endOffset == document.textLength) null
                                        else range
                                    val params = LSPFormattingParams(selection, document, server, options)
                                    val result: MutableList<out TextEdit?>? =
                                        AnalysisDeadline.await<MutableList<out TextEdit?>?>(
                                            ReadActions.compute { support.getFeatureData(params) })
                                    for (edit in result.orEmpty()) {
                                        if (edit != null &&
                                            (selection == null || inside(edit, selection, document)) &&
                                            edit !in edits) {
                                            edits.add(edit)
                                        }
                                    }
                                }
                                formatted = LSPIJUtils.applyEdits(document, edits)
                            }
                        if (!cancelled.get()) {
                            request.onTextReady(if (formatted == document.text) null else formatted)
                        }
                    }
                } catch (failure: ProcessCanceledException) {
                    support.cancel()
                    throw failure
                } catch (failure: Exception) {
                    support.cancel()
                    if (!cancelled.get()) {
                        val cause: Throwable = (if (failure.cause == null) failure else failure.cause)!!
                        request.onError(name, (if (cause.message == null) cause.toString() else cause.message)!!)
                    }
                }
            }

            override fun cancel(): Boolean {
                cancelled.set(true)
                support.cancel()
                val handler = process.get()
                handler?.destroyProcess()
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
