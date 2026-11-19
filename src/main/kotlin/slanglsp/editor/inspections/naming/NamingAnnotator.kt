package slanglsp.editor.inspections.naming

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService.Companion.isDumb
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiModificationTracker
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import kotlin.math.min
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import slanglsp.SlangBundle
import slanglsp.analysis.AnalysisDeadline
import slanglsp.analysis.AnalysisTiming
import slanglsp.analysis.BatchAnalysisService
import slanglsp.analysis.DocumentSymbolRequests
import slanglsp.analysis.ReadActions
import slanglsp.editor.inspections.unused.UnusedDeclarations
import slanglsp.editor.inspections.unused.UnusedSymbolsAnnotator
import slanglsp.editor.rename.RenamePlan
import slanglsp.editor.rename.VerifiedRenameHandler
import slanglsp.server.BuiltinFiles.BuiltinFile
import slanglsp.settings.codestyle.NamingRule
import slanglsp.settings.codestyle.SlangCodeStyleSettings

// Timing and analysis scopes do their work on entry and close.
class NamingAnnotator : ExternalAnnotator<NamingAnnotator.Input, NamingAnnotator.Result>() {
    @JvmRecord
    data class Input(
        val source: UnusedSymbolsAnnotator.Input,
        val rules: List<NamingRule>,
        val abbreviations: String,
        val batchOnly: Boolean
    )

    @JvmRecord data class Candidate(val name: String, @JvmField val category: NamingRule.Category, val range: TextRange)

    @JvmRecord
    data class Result(
        val input: Input,
        @JvmField val candidates: List<Candidate>,
        val unusedMessages: Map<Int, String>,
        @JvmField val failure: String?
    )

    override fun getPairedBatchInspectionShortName(): String {
        return InconsistentNamingInspection.ID
    }

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? {
        return if (hasErrors) null else collect(file, false)
    }

    override fun collectInformation(file: PsiFile): Input? {
        return collect(file, true)
    }

    private fun collect(file: PsiFile, batchOnly: Boolean): Input? {
        if (Analysis.disabled(file) ||
            file.virtualFile == null ||
            file.virtualFile is BuiltinFile ||
            isDumb(file.project)) {
            return null
        }
        val document = LSPIJUtils.getDocument(file) ?: return null
        val settings = CodeStyle.getSettings(file).getCustomSettings(SlangCodeStyleSettings::class.java)
        return Input(
            UnusedSymbolsAnnotator.Input(
                file,
                document,
                document.modificationStamp,
                PsiModificationTracker.getInstance(file.project).modificationCount,
                document.text),
            settings.namingRules,
            settings.NAMING_ABBREVIATIONS,
            batchOnly)
    }

    override fun doAnnotate(input: Input): Result? {
        AnalysisTiming.start(if (input.batchOnly) "batch naming" else "editor naming", input.source.file.name).use {
            return annotate(input)
        }
    }

    private fun annotate(input: Input): Result? {
        val source = input.source
        try {
            AnalysisDeadline.open().use {
                BatchAnalysisService.enter(input.batchOnly).use {
                    val future = DocumentSymbolRequests.get(source.file, source.document, source.stamp, input.batchOnly)
                    val symbols = future?.let { AnalysisDeadline.await(it) }
                    if (input.batchOnly) {
                        Analysis.requireServer(source.file)
                    }
                    if (symbols == null) {
                        check(!input.batchOnly) { SlangBundle.message("analysis.server.unavailable") }
                        return null
                    }
                    val candidates = ReadActions.compute {
                        if (source.document.modificationStamp != source.stamp) {
                            return@compute null
                        }
                        val collected: MutableMap<Int?, Candidate?> = LinkedHashMap()
                        for (symbol in symbols) {
                            Analysis.collect(source, symbol?.documentSymbol, null, collected)
                        }
                        collected.values.filterNotNull()
                    }
                    if (candidates == null) {
                        return null
                    }
                    val unusedAnnotator = UnusedSymbolsAnnotator()
                    val unusedInput =
                        if (input.batchOnly) null
                        else
                            ReadActions.compute {
                                unusedAnnotator.collectForEditor(source.file)
                            }
                    val unusedMessages = mutableMapOf<Int, String>()
                    if (unusedInput != null &&
                        unusedInput.stamp == source.stamp &&
                        unusedInput.projectStamp == source.projectStamp) {
                        val unused = unusedAnnotator.doAnnotate(unusedInput)
                        unused?.unused?.forEach { candidate ->
                            unusedMessages[candidate.range.startOffset] = candidate.message()
                        }
                    }
                    return Result(input, candidates, unusedMessages, null)
                }
            }
        } catch (canceled: ProcessCanceledException) {
            throw canceled
        } catch (failure: RuntimeException) {
            Logger.getInstance(javaClass).debug("Naming analysis unavailable", failure)
            return if (input.batchOnly)
                Result(
                    input,
                    emptyList(),
                    emptyMap(),
                    SlangBundle.message("analysis.batch.failed", "Naming", failure.message))
            else null
        }
    }

    override fun apply(file: PsiFile, result: Result?, holder: AnnotationHolder) {
        if (result == null ||
            Analysis.disabled(file) ||
            result.input.source.document.modificationStamp != result.input.source.stamp ||
            (PsiModificationTracker.getInstance(file.project).modificationCount != result.input.source.projectStamp)) {
            return
        }
        val current = CodeStyle.getSettings(file).getCustomSettings(SlangCodeStyleSettings::class.java)
        if (current.namingRules != result.input.rules || current.NAMING_ABBREVIATIONS != result.input.abbreviations) {
            return
        }
        if (result.failure != null) {
            holder.newAnnotation(HighlightSeverity.WARNING, result.failure).fileLevel().create()
            return
        }
        val key = HighlightDisplayKey.find(InconsistentNamingInspection.ID) ?: return
        val severity =
            InspectionProfileManager.getInstance(file.project).currentProfile.getErrorLevel(key, file).severity
        for (candidate in result.candidates) {
            val rule: NamingRule = Analysis.rule(result.input, candidate) ?: continue
            val suggestion = rule.suggest(candidate.name, result.input.abbreviations)
            if (suggestion == candidate.name || !RenamePlan.validName(suggestion)) {
                continue
            }
            val element = file.findElementAt(candidate.range.startOffset)
            if (element == null || element.text != candidate.name) {
                continue
            }
            // Informational styling must not compete with the unused warning for hover selection.
            // Its fix remains available through Alt+Enter over the entire identifier.
            val unusedMessage = result.unusedMessages[candidate.range.startOffset]
            val annotation =
                if (unusedMessage != null)
                    holder
                        .newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .textAttributes(CodeInsightColors.WARNINGS_ATTRIBUTES)
                else
                    holder.newAnnotation(
                        severity, SlangBundle.message("naming.warning", candidate.name, rule.name, suggestion))
            annotation
                .range(TextRange.from(candidate.range.startOffset, min(2, candidate.name.length)))
                .newFix(NamingRenameFix(element, suggestion))
                .range(candidate.range)
                .registerFix()
                .create()
        }
    }

    internal object Analysis {
        fun disabled(file: PsiFile): Boolean {
            val key = HighlightDisplayKey.find(InconsistentNamingInspection.ID)
            return key == null ||
                !InspectionProfileManager.getInstance(file.project).currentProfile.isToolEnabled(key, file)
        }

        fun requireServer(file: PsiFile) {
            check(
                LanguageServerManager.getInstance(file.project).getServerStatus("slanglsp.SlangLanguageServer") ==
                    ServerStatus.started) {
                    SlangBundle.message("analysis.server.unavailable")
                }
        }

        fun collect(
            input: UnusedSymbolsAnnotator.Input,
            symbol: DocumentSymbol?,
            parent: SymbolKind?,
            result: MutableMap<Int?, Candidate?>
        ) {
            if (symbol?.selectionRange == null || symbol.range == null) {
                return
            }
            val start = LSPIJUtils.toOffset(symbol.selectionRange.start, input.document)
            val end = start + symbol.name.length
            if (start < 0 || end > input.text.length || (input.text.substring(start, end) != symbol.name)) {
                return
            }
            var boundary = start
            while (boundary > 0 && ";{}".indexOf(input.text[boundary - 1]) < 0) {
                boundary--
            }
            val prefix = input.text.substring(boundary, start)
            val external = UnusedDeclarations.isExternal(symbol.name, prefix)
            val category: NamingRule.Category? = category(symbol.kind, parent, prefix)
            if (!external && category != null && parent != SymbolKind.Interface && !symbol.name.startsWith("__")) {
                result.putIfAbsent(start, Candidate(symbol.name, category, TextRange(start, end)))
            }
            if (symbol.kind == SymbolKind.Function || symbol.kind == SymbolKind.Method) {
                val bodyEnd = min(input.text.length, LSPIJUtils.toOffset(symbol.range.end, input.document))
                for ((name, _, range) in UnusedDeclarations.locals(input.text, end, bodyEnd)) {
                    result[range.startOffset] =
                        Candidate(name, localCategory(input.text, end, range.startOffset), range)
                }
                NamingParameters.collect(input.text, end, bodyEnd, result)
            } else if (symbol.kind == SymbolKind.Struct ||
                symbol.kind == SymbolKind.Class ||
                symbol.kind == SymbolKind.Interface) {
                NamingParameters.collect(
                    input.text,
                    end,
                    min(input.text.length, LSPIJUtils.toOffset(symbol.range.end, input.document)),
                    result)
            }
            if (symbol.children != null) {
                var childParent = symbol.kind
                if (childParent == SymbolKind.Struct || childParent == SymbolKind.Class) {
                    val body = input.text.indexOf('{', end)
                    if (body >= end && input.text.substring(end, body).contains(":")) {
                        // Members may fulfill interface requirements without an explicit override modifier.
                        childParent = SymbolKind.Interface
                    }
                }
                for (child in symbol.children) {
                    collect(input, child, childParent, result)
                }
            }
        }

        private fun category(kind: SymbolKind, parent: SymbolKind?, prefix: String): NamingRule.Category? {
            val category =
                when (kind) {
                    SymbolKind.Struct,
                    SymbolKind.Class -> NamingRule.Category.TYPES
                    SymbolKind.Interface -> NamingRule.Category.INTERFACES
                    SymbolKind.Enum -> NamingRule.Category.ENUMS
                    SymbolKind.EnumMember -> NamingRule.Category.ENUM_MEMBERS
                    SymbolKind.Namespace -> NamingRule.Category.NAMESPACES
                    SymbolKind.Function,
                    SymbolKind.Method ->
                        if (parent == SymbolKind.Struct || parent == SymbolKind.Class) NamingRule.Category.METHODS
                        else NamingRule.Category.FUNCTIONS

                    SymbolKind.Field,
                    SymbolKind.Property -> NamingRule.Category.FIELDS
                    SymbolKind.Constant,
                    SymbolKind.Variable ->
                        when (parent) {
                            SymbolKind.Function,
                            SymbolKind.Method -> NamingRule.Category.LOCALS
                            SymbolKind.Struct,
                            SymbolKind.Class -> NamingRule.Category.FIELDS
                            else -> NamingRule.Category.GLOBALS
                        }

                    else -> null
                }
            if (isStaticConstant(prefix) &&
                (category == NamingRule.Category.GLOBALS ||
                    category == NamingRule.Category.FIELDS ||
                    category == NamingRule.Category.LOCALS)) {
                return NamingRule.Category.CONSTANTS
            }
            return category
        }

        private fun localCategory(text: String, bodyStart: Int, start: Int): NamingRule.Category {
            var boundary = start
            while (boundary > bodyStart && ";{}".indexOf(text[boundary - 1]) < 0) {
                boundary--
            }
            return if (isStaticConstant(text.substring(boundary, start))) NamingRule.Category.CONSTANTS
            else NamingRule.Category.LOCALS
        }

        private fun isStaticConstant(prefix: String): Boolean {
            return prefix.matches("(?s).*\\bstatic\\b.*".toRegex()) && prefix.matches("(?s).*\\bconst\\b.*".toRegex())
        }

        fun rule(input: Input, candidate: Candidate): NamingRule? {
            return input.rules.firstOrNull { it.matches(candidate.category) }
        }
    }
}

/** Uses the same verified, undoable rename plan as Refactor | Rename. */
private class NamingRenameFix(element: PsiElement, private val newName: String) : IntentionAction {
    private val pointer = SmartPointerManager.createPointer(element)
    private val oldName: String = element.text

    override fun getText(): String {
        return SlangBundle.message("naming.rename", newName)
    }

    override fun getFamilyName(): String {
        return SlangBundle.message("naming.inspection")
    }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val element = pointer.element
        return element != null && element.isWritable && oldName == element.text && !isDumb(project)
    }

    override fun startInWriteAction(): Boolean {
        return false
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        VerifiedRenameHandler.RenameUi.queueRename(project, newName, previewRequested = false) {
            pointer.element?.takeIf { it.text == oldName }
        }
    }
}
