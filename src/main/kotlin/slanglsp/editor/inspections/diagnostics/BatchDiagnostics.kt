package slanglsp.editor.inspections.diagnostics

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.SystemInfo
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import slanglsp.analysis.AnalysisTiming
import slanglsp.discovery.ExecutableLocator

/**
 * Explicit, bounded batch validation. Slangd's unversioned, change-only push diagnostics cannot establish completion
 * for a clean/unchanged file. Use its sibling compiler.
 */
internal object BatchDiagnostics {
    private const val TIMEOUT_MILLIS = 10000

    @JvmOverloads
    @Throws(Exception::class)
    fun check(
        input: BatchDiagnosticsAnnotator.Input,
        timeoutMillis: Int = TIMEOUT_MILLIS
    ): List<BatchDiagnosticsAnnotator.Problem> {
        ProgressManager.checkCanceled()
        val project = input.file.project
        val state = input.settings
        val compiler =
            AnalysisTiming.start("compiler discovery", input.path.toString()).use {
                val server =
                    ExecutableLocator.findSlangd(project, state) ?: throw IOException("Slang installation not found")
                val compiler =
                    Path.of(server).toRealPath().resolveSibling(if (SystemInfo.isWindows) "slangc.exe" else "slangc")
                if (!Files.isRegularFile(compiler) || !Files.isExecutable(compiler)) {
                    throw IOException("Slang compiler not found: $compiler")
                }
                compiler
            }
        val command =
            GeneralCommandLine(compiler.toString())
                .withCharset(StandardCharsets.UTF_8)
                .withWorkDirectory(input.path.parent.toFile())
                .withParameters("-lang", "slang", "-no-codegen", "-enable-machine-readable-diagnostics")
        val basePath = project.basePath
        input.searchPaths.forEach { path ->
            var include = Path.of(path)
            if (!include.isAbsolute && basePath != null) {
                include = Path.of(basePath).resolve(include)
            }
            command.addParameters("-I", include.toString())
        }
        for (macro in state.predefinedMacros) {
            if (!macro.isNullOrBlank()) {
                command.addParameter("-D$macro")
            }
        }
        command.addParameters("--", "-")
        AnalysisTiming.start("compiler execution", input.path.toString()).use {
            return run(command, input.text, timeoutMillis)
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun run(command: GeneralCommandLine, source: String, timeoutMillis: Int): List<BatchDiagnosticsAnnotator.Problem> {
        ProgressManager.checkCanceled()
        val handler =
            AnalysisTiming.start("compiler process startup", command.exePath).use {
                CapturingProcessHandler(command)
            }
        val writer =
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    handler.processInput.use { stream ->
                        stream.write(source.toByteArray(StandardCharsets.UTF_8))
                    }
                } catch (failure: IOException) {
                    throw UncheckedIOException(failure)
                }
            }
        try {
            val indicator = ProgressManager.getInstance().progressIndicator
            val output =
                handler.runProcessWithProgressIndicator(indicator ?: EmptyProgressIndicator(), timeoutMillis, true)
            ProgressManager.checkCanceled()
            if (output.isCancelled) {
                throw ProcessCanceledException()
            }
            if (output.isTimeout) {
                throw IOException("Slang analysis timed out")
            }
            if (output.exitCode == 0) {
                try {
                    writer.get()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ProcessCanceledException()
                }
            }
            val diagnostics = output.stderr + output.stdout
            val problems = parse(diagnostics)
            if (output.exitCode != 0 && problems.none { it.severity === HighlightSeverity.ERROR }) {
                throw IOException(
                    if (diagnostics.isBlank()) "Slang compiler exited with code ${output.exitCode}"
                    else diagnostics.trim())
            }
            return problems
        } finally {
            handler.destroyProcess()
            writer.cancel(true)
        }
    }

    @Throws(IOException::class)
    fun parse(diagnostics: String): List<BatchDiagnosticsAnnotator.Problem> {
        val problems = ArrayList<BatchDiagnosticsAnnotator.Problem>()
        for (line in diagnostics.lineSequence()) {
            if (line.isBlank()) {
                continue
            }
            val fields = line.split('\t', limit = 8)
            if (fields.size != 8) {
                throw IOException("Unrecognized Slang diagnostic: $line")
            }
            val severity =
                when (fields[1]) {
                    "error",
                    "fatal" -> HighlightSeverity.ERROR
                    "warning" -> HighlightSeverity.WARNING
                    "note",
                    "help" -> HighlightSeverity.WEAK_WARNING
                    else -> null
                }
            if (fields[1] == "span") {
                // The primary span carries the detailed message (e.g. the missing identifier).
                if (problems.isNotEmpty()) {
                    val previous = problems.last()
                    if (previous.file == fields[2] &&
                        previous.line == fields[3].toInt() &&
                        previous.column == fields[4].toInt()) {
                        problems[problems.size - 1] =
                            BatchDiagnosticsAnnotator.Problem(
                                previous.file,
                                previous.line,
                                previous.column,
                                previous.endLine,
                                previous.endColumn,
                                previous.severity,
                                fields[7])
                    }
                }
            } else if (severity != null) {
                problems.add(
                    BatchDiagnosticsAnnotator.Problem(
                        fields[2],
                        fields[3].toInt(),
                        fields[4].toInt(),
                        fields[5].toInt(),
                        fields[6].toInt(),
                        severity,
                        fields[7]))
            }
        }
        return problems.toList()
    }
}
