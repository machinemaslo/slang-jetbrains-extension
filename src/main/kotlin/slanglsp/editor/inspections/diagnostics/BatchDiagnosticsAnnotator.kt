package slanglsp.editor.inspections.diagnostics

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import java.nio.file.Path
import kotlin.Exception
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.math.max
import slanglsp.SlangBundle
import slanglsp.analysis.AnalysisTiming
import slanglsp.settings.project.ProjectSettings

/** Batch-only: editor diagnostics continue to be supplied by the active LSP client. */
// Timing scopes report elapsed time when closed.
class BatchDiagnosticsAnnotator :
    ExternalAnnotator<BatchDiagnosticsAnnotator.Input, BatchDiagnosticsAnnotator.Result>() {
    @JvmRecord
    data class Input(
        val file: PsiFile,
        val document: Document,
        val stamp: Long,
        val text: String,
        val path: Path,
        val settings: ProjectSettings.State,
        val searchPaths: List<String>
    )

    @JvmRecord
    data class Problem(
        val file: String,
        val line: Int,
        val column: Int,
        val endLine: Int,
        val endColumn: Int,
        val severity: HighlightSeverity,
        val message: String
    )

    @JvmRecord data class Result(val input: Input, val problems: List<Problem>, val failure: String?)

    override fun getPairedBatchInspectionShortName(): String {
        return BatchDiagnosticsInspection.ID
    }

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? {
        return null // Never spawn a compiler from editor highlighting.
    }

    override fun collectInformation(file: PsiFile): Input? {
        AnalysisTiming.start("batch snapshot and search paths", file.name).use {
            return collect(file)
        }
    }

    private fun collect(file: PsiFile): Input? {
        val virtualFile = file.virtualFile
        if (virtualFile == null || !virtualFile.isInLocalFileSystem) {
            return null
        }
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        val settings: ProjectSettings.State = ProjectSettings.getInstance(file.project).getState()
        val paths = LinkedHashSet<String>()
        val source = virtualFile.toNioPath()
        paths.add(source.parent.toString())
        val base = file.project.basePath
        for (path in settings.additionalIncludePaths) {
            if (!path.isNullOrBlank()) {
                paths.add(path)
            }
        }
        if (base != null) {
            paths.add(base)
        }
        if (settings.enableSearchingSubDirectoriesOfWorkspace) {
            paths.addAll(DiagnosticLocations.workspacePaths(file.project))
        }
        return Input(file, document, document.modificationStamp, document.text, source, settings, paths.toList())
    }

    override fun doAnnotate(input: Input): Result {
        try {
            AnalysisTiming.start("batch diagnostics total", input.path.toString()).use {
                return Result(input, BatchDiagnostics.check(input), null)
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            return Result(
                input,
                emptyList(),
                SlangBundle.message(
                    "inspection.batch.failed",
                    if (failure.message == null) failure.javaClass.getSimpleName() else failure.message))
        }
    }

    override fun apply(file: PsiFile, result: Result?, holder: AnnotationHolder) {
        if (result == null) {
            return
        }
        if (result.input.document.modificationStamp != result.input.stamp) {
            holder
                .newAnnotation(HighlightSeverity.ERROR, SlangBundle.message("inspection.batch.changed"))
                .fileLevel()
                .create()
            return
        }
        if (result.failure != null) {
            holder.newAnnotation(HighlightSeverity.ERROR, result.failure).fileLevel().create()
            return
        }
        for ((file1, line, column, endLine, endColumn, severity, message) in result.problems) {
            if (file1 != "<stdin>") {
                // Included/imported-file failures must not be mistaken for a clean source file.
                holder.newAnnotation(severity, "$file1:$line: $message").fileLevel().create()
                continue
            }
            val start: Int = DiagnosticLocations.offset(result.input.text, line, column)
            var end: Int = DiagnosticLocations.offset(result.input.text, endLine, endColumn)
            if (end <= start && start < file.textLength) {
                end = start + 1
            }
            var annotation = holder.newAnnotation(severity, message).range(TextRange(start, max(start, end)))
            if (start == end) {
                annotation = annotation.afterEndOfLine()
            }
            annotation.create()
        }
    }

    private object DiagnosticLocations {
        private val WORKSPACE_PATHS = Key.create<CachedValue<List<String>>>("slang.batch.workspace.paths")

        fun workspacePaths(project: Project): List<String> {
            return CachedValuesManager.getManager(project)
                .getCachedValue(
                    project,
                    WORKSPACE_PATHS,
                    {
                        val directories = LinkedHashSet<String>()
                        for (extension in listOf("slang", "slangh")) {
                            for (candidate in
                                FilenameIndex.getAllFilesByExt(
                                    project, extension, GlobalSearchScope.projectScope(project))) {
                                if (candidate.isInLocalFileSystem) {
                                    directories.add(candidate.parent.path)
                                }
                            }
                        }
                        CachedValueProvider.Result.create(
                            directories.toList(),
                            VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                            ProjectRootModificationTracker.getInstance(project))
                    },
                    false)
        }

        /** Compiler positions are one-based Unicode code points; IDE offsets are UTF-16. */
        fun offset(text: String, line: Int, column: Int): Int {
            var start = 0
            repeat(line.coerceAtLeast(1) - 1) {
                val newline = text.indexOf('\n', start)
                if (newline < 0) {
                    return text.length
                }
                start = newline + 1
            }
            var end = text.indexOf('\n', start)
            if (end < 0) {
                end = text.length
            }
            return text.offsetByCodePoints(start, Math.clamp((column - 1).toLong(), 0, text.codePointCount(start, end)))
        }
    }
}
