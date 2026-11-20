package slanglsp.mcp

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.math.min
import slanglsp.analysis.AnalysisDeadline
import slanglsp.analysis.ProjectTrust
import slanglsp.editor.inspections.diagnostics.BatchDiagnostics
import slanglsp.editor.inspections.diagnostics.BatchDiagnosticsAnnotator
import slanglsp.editor.navigation.UsageSearch
import slanglsp.editor.navigation.isSlangIdentifier
import slanglsp.language.SlangFileType
import slanglsp.server.protocol.DocumentUris

/** Read-only compiler and semantic operations for agent integrations. */
object AgentAnalysis {
    private const val MAX_FILES = 16
    private const val MAX_DIAGNOSTICS = 30
    private const val MAX_REFERENCES = 20

    @Throws(IOException::class)
    fun checkFiles(project: Project, files: List<String>): String {
        require(files.size in 1..MAX_FILES) { "Supply 1–16 project-relative Slang files." }
        val root = root(project)
        val paths = LinkedHashSet<Path>()
        for (file in files) {
            paths.add(resolve(root, file))
        }
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        val output = StringBuilder()
        val diagnostics = linkedSetOf<String>()
        for (path in paths) {
            ProgressManager.checkCanceled()
            val relative = root.relativize(path).toString()
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            val input = runReadActionBlocking {
                val file = if (virtualFile == null) null else PsiManager.getInstance(project).findFile(virtualFile)
                if (file == null || file.fileType !== SlangFileType) {
                    null
                } else {
                    BatchDiagnosticsAnnotator().collectInformation(file)
                }
            }
            if (input == null) {
                output.append(relative).append(": not checked (not a Slang file)\n")
                continue
            }
            val remaining = (deadline - System.nanoTime()) / 1000000
            if (remaining <= 0) {
                output.append("Time limit reached; remaining files were not checked.\n")
                break
            }
            val problems =
                try {
                    BatchDiagnostics.check(input, remaining.toInt())
                } catch (cancelled: ProcessCanceledException) {
                    throw cancelled
                } catch (failure: Exception) {
                    output.append(relative).append(": not checked (").append(clip(failure.message, 300)).append(")\n")
                    continue
                }
            if (input.document.modificationStamp != input.stamp) {
                output.append(relative).append(": changed during check; rerun\n")
                continue
            }
            if (problems.isEmpty()) {
                output.append(relative).append(": clean\n")
            }
            for ((file, line, column, _, _, severity, message) in problems) {
                val diagnosticPath = if ("<stdin>" == file) relative else file
                diagnostics.add("$diagnosticPath:$line:$column: $severity: ${clip(message, 350)}")
            }
        }
        for (problem in diagnostics.take(MAX_DIAGNOSTICS)) {
            output.appendLine(problem)
        }
        if (diagnostics.size > MAX_DIAGNOSTICS) {
            output.append("Additional diagnostics omitted; check individual files for more.\n")
        }
        return output.toString().trimEnd()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun symbol(project: Project, path: String, line: Int, column: Int, references: Boolean): String {
        val root = root(project)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolve(root, path))
        val source = runReadActionBlocking {
            val file = if (virtualFile == null) null else PsiManager.getInstance(project).findFile(virtualFile)
            val document = if (file == null) null else PsiDocumentManager.getInstance(project).getDocument(file)
            require(
                !(file == null ||
                    file.fileType !== SlangFileType ||
                    document == null ||
                    line < 1 ||
                    line > document.lineCount ||
                    column < 1)) {
                    "Expected a Slang file and a valid 1-based line and column."
                }
            val start = document.getLineStartOffset(line - 1)
            require(column - 1 < document.getLineEndOffset(line - 1) - start) { "Column is outside the source line." }
            Source(file, document, document.modificationStamp, start + column - 1)
        }

        val output = StringBuilder()
        AnalysisDeadline.open().use {
            val definitions = UsageSearch.definitions(source.file, source.document, source.offset, verify = true)
            if (definitions.isEmpty()) {
                output.append("No Slang definition resolved at this position.\n")
            }
            for (location in definitions.asSequence().distinct().take(6)) {
                val start = location.range.start
                output
                    .append("Definition: ")
                    .append(display(root, location.uri))
                    .append(':')
                    .append(start.line + 1)
                    .append(':')
                    .append(start.character + 1)
                    .append(excerpt(root, location.uri, start.line))
                    .append('\n')
            }
            if (definitions.size > 6) {
                output.append("Additional definitions omitted.\n")
            }
            if (references) {
                val element = runReadActionBlocking {
                    val leaf = source.file.findElementAt(source.offset)
                    if (isSlangIdentifier(leaf)) {
                        leaf
                    } else {
                        null
                    }
                }
                if (element == null) {
                    output.append("No identifier at the requested position for reference search.\n")
                } else {
                    val count = intArrayOf(0)
                    val complete =
                        UsageSearch.process(
                            element,
                            GlobalSearchScope.projectScope(project),
                            Processor { usage: UsageInfo? ->
                                val reference = runReadActionBlocking {
                                    val target = usage!!.element
                                    val hint = target?.containingFile
                                    val targetFile = hint?.virtualFile
                                    val document = hint?.let(PsiDocumentManager.getInstance(project)::getDocument)
                                    val offset = usage.navigationOffset
                                    if (targetFile == null || document == null || offset !in 0..document.textLength) {
                                        return@runReadActionBlocking null
                                    }
                                    val row = document.getLineNumber(offset)
                                    val path = display(root, DocumentUris.fileUri(targetFile).toString())
                                    val column = offset - document.getLineStartOffset(row) + 1
                                    "$path:${row + 1}:$column"
                                }
                                if (reference == null) {
                                    return@Processor true
                                }
                                if (count[0]++ >= MAX_REFERENCES) {
                                    return@Processor false
                                }
                                output.append("Reference: ").append(reference).append('\n')
                                true
                            })
                    when {
                        !complete -> output.appendLine("References incomplete or truncated.")
                        count[0] == 0 -> output.appendLine("No project references found.")
                    }
                }
            }
        }
        if (source.document.modificationStamp != source.stamp) {
            return "Source changed during resolution; retry at the current position."
        }
        return output.toString().trimEnd()
    }

    @Throws(IOException::class)
    private fun root(project: Project): Path {
        val basePath = project.basePath
        require(!project.isDisposed && basePath != null) { "Open a local Slang project first." }
        require(ProjectTrust.isTrusted(project)) { "Slang analysis requires a trusted project." }
        return Path.of(basePath).toRealPath()
    }

    @Throws(IOException::class)
    fun resolve(root: Path, relative: String): Path {
        val path = Path.of(relative)
        require(!path.isAbsolute) { "Expected a project-relative path: $relative" }
        val candidate = root.resolve(path).normalize()
        require(candidate.startsWith(root)) { "Path is outside the project: $relative" }
        val real = candidate.toRealPath()
        require(real.startsWith(root) && Files.isRegularFile(real)) { "Expected a file inside the project: $relative" }
        return real
    }

    private fun display(root: Path, uri: String): String? {
        try {
            val path = Path.of(URI.create(uri)).normalize()
            return if (path.startsWith(root)) root.relativize(path).toString() else uri
        } catch (_: IllegalArgumentException) {
            return uri
        } catch (_: FileSystemNotFoundException) {
            return uri
        }
    }

    private fun excerpt(root: Path, uri: String, line: Int): String {
        try {
            val path = Path.of(URI.create(uri)).toRealPath()
            if (!path.startsWith(root)) {
                return ""
            }
            val file = LocalFileSystem.getInstance().findFileByNioFile(path)
            return runReadActionBlocking {
                val document =
                    if (file == null || file.fileType !== SlangFileType) null
                    else FileDocumentManager.getInstance().getDocument(file)
                if (document == null || line < 0 || line >= document.lineCount) {
                    return@runReadActionBlocking ""
                }
                val start = document.getLineStartOffset(line)
                val end = min(document.getLineEndOffset(line), start + 180)
                " — " + document.charsSequence.subSequence(start, end).toString().trim()
            }
        } catch (_: IOException) {
            return ""
        } catch (_: IllegalArgumentException) {
            return ""
        } catch (_: FileSystemNotFoundException) {
            return ""
        }
    }

    private fun clip(text: String?, limit: Int): String {
        val detail = text ?: return "no detail available"
        return if (detail.length <= limit) detail else "${detail.take(limit)}…"
    }

    @JvmRecord private data class Source(val file: PsiFile, val document: Document, val stamp: Long, val offset: Int)
}
