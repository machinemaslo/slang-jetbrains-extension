package slanglsp.editor.inspections.unused

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService.Companion.isDumb
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import kotlin.math.min
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import slanglsp.SlangBundle
import slanglsp.analysis.AnalysisDeadline
import slanglsp.analysis.AnalysisTiming
import slanglsp.analysis.DocumentSymbolRequests
import slanglsp.editor.navigation.UsageSearch
import slanglsp.server.ServerSettings
import slanglsp.server.protocol.protocolOffset
import slanglsp.settings.project.ProjectSettings

/** Network and index work runs outside the read action; stale results are never applied. */
class UnusedSymbolsAnnotator : ExternalAnnotator<UnusedSymbolsAnnotator.Input, UnusedSymbolsAnnotator.Result>() {
    @JvmRecord private data class CachedResult(val serverStamp: Long, val result: Result?)

    @JvmRecord
    data class Input(
        val file: PsiFile,
        val document: Document,
        val stamp: Long,
        val projectStamp: Long,
        @JvmField val text: String,
        val batchOnly: Boolean
    ) {
        constructor(
            file: PsiFile,
            document: Document,
            stamp: Long,
            projectStamp: Long,
            text: String
        ) : this(file, document, stamp, projectStamp, text, false)
    }

    @JvmRecord
    data class Candidate(val name: String, val kind: String, @JvmField val range: TextRange) {
        fun message(): String {
            return when (kind) {
                "function" -> SlangBundle.message("unused.symbol.function", name)
                "method" -> SlangBundle.message("unused.symbol.method", name)
                "struct" -> SlangBundle.message("unused.symbol.struct", name)
                "class" -> SlangBundle.message("unused.symbol.class", name)
                "enum" -> SlangBundle.message("unused.symbol.enum", name)
                "field" -> SlangBundle.message("unused.symbol.field", name)
                "constant" -> SlangBundle.message("unused.symbol.constant", name)
                else -> SlangBundle.message("unused.symbol.variable", name)
            }
        }

        fun removalText(): String {
            return when (kind) {
                "function" -> SlangBundle.message("unused.remove.function")
                "method" -> SlangBundle.message("unused.remove.method")
                "struct" -> SlangBundle.message("unused.remove.struct")
                "class" -> SlangBundle.message("unused.remove.class")
                "enum" -> SlangBundle.message("unused.remove.enum")
                "field" -> SlangBundle.message("unused.remove.field")
                "constant" -> SlangBundle.message("unused.remove.constant")
                else -> SlangBundle.message("unused.remove.variable")
            }
        }
    }

    @JvmRecord
    data class Result(val input: Input, @JvmField val unused: List<Candidate>, @JvmField val failure: String?) {
        constructor(input: Input, unused: List<Candidate>) : this(input, unused, null)
    }

    override fun getPairedBatchInspectionShortName(): String {
        return UnusedSymbolsInspection.ID
    }

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? {
        return if (hasErrors) null else collectForEditor(file)
    }

    override fun collectInformation(file: PsiFile): Input? {
        val input = collectForEditor(file)
        return if (input == null) null
        else Input(input.file, input.document, input.stamp, input.projectStamp, input.text, true)
    }

    fun collectForEditor(file: PsiFile): Input? {
        val project = file.project

        if (Analysis.disabled(file) ||
            ProjectSettings.getInstance(project).getState().useSlangdUnusedDiagnosticsOnly ||
            isDumb(project) ||
            file.virtualFile == null ||
            !ProjectFileIndex.getInstance(project).isInContent(file.virtualFile)) {
            return null
        }
        val document = PsiDocumentManager.getInstance(project).getDocument(file)

        return if (document == null) null
        else
            Input(
                file,
                document,
                document.modificationStamp,
                PsiModificationTracker.getInstance(project).modificationCount,
                document.text)
    }

    override fun doAnnotate(input: Input): Result? {
        AnalysisTiming.start(if (input.batchOnly) "batch unused symbols" else "editor unused symbols", input.file.name)
            .use { timing ->
                return annotate(input, timing)
            }
    }

    private fun annotate(input: Input, timing: AnalysisTiming?): Result? {
        val cached: CachedResult? = input.file.getUserData(Analysis.REVISION_CACHE)
        val serverStamp: Long = ServerSettings.analysisGeneration(input.file.project)
        if (!input.batchOnly &&
            cached != null &&
            cached.serverStamp == serverStamp &&
            cached.result!!.input.stamp == input.stamp &&
            cached.result.input.projectStamp == input.projectStamp) {
            return cached.result
        }
        val result = AnalysisDeadline.open().use { analyze(input, timing) }
        if (result != null && !input.batchOnly) {
            runReadActionBlocking {
                Analysis.check(input)
                input.file.putUserData(Analysis.REVISION_CACHE, CachedResult(serverStamp, result))
            }
        }
        return result
    }

    private fun analyze(input: Input, timing: AnalysisTiming?): Result? {
        try {
            val future = DocumentSymbolRequests.get(input.file, input.document, input.stamp, input.batchOnly)
            val symbols = AnalysisDeadline.await(future)
            if (symbols == null) {
                check(!input.batchOnly) { SlangBundle.message("analysis.server.unavailable") }
                return null
            }

            val candidates: MutableList<Candidate> = ArrayList()
            runReadActionBlocking {
                Analysis.check(input)
                for (symbol in symbols) {
                    Analysis.collect(input, symbol, candidates, externallyUsed = false, member = false)
                }
            }

            val unused: MutableList<Candidate> = ArrayList()

            for (candidate in candidates) {
                ProgressManager.checkCanceled()
                val element = runReadActionBlocking {
                    Analysis.check(input)
                    input.file.findElementAt(candidate.range.startOffset)
                }

                if (element != null && UsageSearch.isUnused(element, timing, batch = input.batchOnly)) {
                    unused.add(candidate)
                }
            }

            return Result(input, unused)
        } catch (canceled: ProcessCanceledException) {
            throw canceled
        } catch (failure: RuntimeException) {
            // An unavailable server or failed request cannot prove that a declaration is unused.
            Logger.getInstance(javaClass).debug("Unused symbol analysis unavailable", failure)

            return if (input.batchOnly)
                Result(
                    input,
                    mutableListOf(),
                    SlangBundle.message("analysis.batch.failed", "Unused symbols", failure.message))
            else null
        }
    }

    override fun apply(file: PsiFile, result: Result?, holder: AnnotationHolder) {
        if (result == null ||
            Analysis.disabled(file) ||
            ProjectSettings.getInstance(file.project).getState().useSlangdUnusedDiagnosticsOnly) {
            return
        }

        Analysis.check(result.input)
        if (result.failure != null) {
            holder.newAnnotation(HighlightSeverity.WARNING, result.failure).fileLevel().create()
            return
        }

        for (candidate in result.unused) {
            // This warning owns the hover; naming only contributes informational styling and a menu action.
            var annotation =
                holder
                    .newAnnotation(HighlightSeverity.WEAK_WARNING, candidate.message())
                    .range(candidate.range)
                    .highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL)
            val fix = UnusedRemoval.quickFix(result.input, candidate)
            if (fix != null) {
                annotation = annotation.newFix(fix).range(candidate.range).registerFix()
            }
            annotation.create()
        }
    }

    private object Analysis {
        val REVISION_CACHE: Key<CachedResult?> = Key.create("slang.unused.analysisRevision")

        fun check(input: Input) {
            ProgressManager.checkCanceled()

            if (!input.file.isValid ||
                input.document.modificationStamp != input.stamp ||
                PsiModificationTracker.getInstance(input.file.project).modificationCount != input.projectStamp) {
                throw ProcessCanceledException()
            }
        }

        fun collect(
            input: Input,
            symbol: DocumentSymbol?,
            result: MutableList<Candidate>,
            externallyUsed: Boolean,
            member: Boolean
        ) {
            if (symbol?.selectionRange == null || symbol.range == null) {
                return
            }

            val position = symbol.selectionRange.start
            val start = input.document.protocolOffset(position)
            // Some slangd versions give selection ranges extending beyond the identifier.
            val end = start + symbol.name.length

            if (start < 0 || end > input.text.length || (input.text.substring(start, end) != symbol.name)) {
                return
            }

            var boundary = start

            while (boundary > 0 && ";{}".indexOf(input.text[boundary - 1]) < 0) {
                boundary--
            }

            val prefix = input.text.substring(boundary, start)
            val external =
                externallyUsed ||
                    symbol.kind == SymbolKind.Interface ||
                    UnusedDeclarations.isExternal(symbol.name, prefix)
            val kind =
                when (symbol.kind) {
                    SymbolKind.Struct -> "struct"
                    SymbolKind.Class -> "class"
                    SymbolKind.Enum -> "enum"
                    SymbolKind.Function -> if (member) "method" else "function"
                    SymbolKind.Method -> "method"
                    SymbolKind.Constant -> "constant"
                    SymbolKind.Field -> "field"
                    SymbolKind.Variable -> if (member) "field" else "variable"
                    else -> null
                }

            if (!external && kind != null) {
                result.add(Candidate(symbol.name, kind, TextRange(start, end)))
            }

            if (symbol.kind == SymbolKind.Function || symbol.kind == SymbolKind.Method) {
                val endPosition = symbol.range.end
                val bodyEnd = input.document.protocolOffset(endPosition)
                result.addAll(UnusedDeclarations.locals(input.text, end, min(bodyEnd, input.text.length)))
            }

            if (symbol.children != null) {
                val type = symbol.kind == SymbolKind.Struct || symbol.kind == SymbolKind.Class
                val body = if (type) input.text.indexOf('{', end) else -1
                val implementsInterface = body >= end && input.text.substring(end, body).contains(":")
                for (child in symbol.children) {
                    collect(input, child, result, external || implementsInterface, type)
                }
            }
        }

        fun disabled(file: PsiFile): Boolean {
            val key = HighlightDisplayKey.find(UnusedSymbolsInspection.ID)
            return key != null &&
                !InspectionProfileManager.getInstance(file.project).currentProfile.isToolEnabled(key, file)
        }
    }
}
