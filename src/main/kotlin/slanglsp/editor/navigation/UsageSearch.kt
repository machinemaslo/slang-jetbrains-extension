package slanglsp.editor.navigation

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.*
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.features.navigation.LSPDefinitionParams
import com.redhat.devtools.lsp4ij.features.navigation.LSPDefinitionSupport
import com.redhat.devtools.lsp4ij.features.references.LSPReferenceParams
import com.redhat.devtools.lsp4ij.features.references.LSPReferenceSupport
import com.redhat.devtools.lsp4ij.usages.LocationData
import java.util.concurrent.CompletableFuture
import kotlin.math.max
import kotlin.math.min
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.TextDocumentIdentifier
import slanglsp.SlangBundle
import slanglsp.analysis.AnalysisDeadline
import slanglsp.analysis.AnalysisTiming
import slanglsp.analysis.BatchAnalysisService
import slanglsp.analysis.ReadActions
import slanglsp.language.SlangFileType
import slanglsp.server.BuiltinFiles.BuiltinFile
import slanglsp.server.client.ClientFeatureAccess
import slanglsp.server.protocol.DocumentUris.fileUri

/** Indexed candidates verified with bounded requests. Legacy callers may hold an outer read lock. */
internal object UsageSearch {
    fun process(element: PsiElement?, scope: SearchScope, consumer: Processor<in UsageInfo?>): Boolean {
        val source = snapshot(element) ?: return true

        val project = source.file.project
        val declarations: MutableSet<SymbolLocation?> = HashSet()
        val virtualFile = source.file.virtualFile

        if (virtualFile is BuiltinFile) {
            val position = ReadActions.compute { LSPIJUtils.toPosition(source.offset, source.document) }
            declarations.add(SymbolLocation(virtualFile.uri(), position.line, position.character))
        } else {
            val references = references(source)
            source.checkUnchanged()

            // LSP4IJ returns null only when no server supports references. Empty is a valid result.
            if (references != null) {
                return processReferences(project, references, scope, consumer)
            }

            for (location in definitions(source.file, source.document, source.offset)) {
                declarations.add(SymbolLocation.from(location.location()))
            }
        }

        // slangd returns null for some declaration-site definition requests (including globals).
        // In that case use the source location; candidates still require semantic confirmation.
        if (declarations.isEmpty()) {
            val position = ReadActions.compute { LSPIJUtils.toPosition(source.offset, source.document) }
            declarations.add(SymbolLocation(fileUri(virtualFile), position.line, position.character))
        }

        source.checkUnchanged()

        return processCandidates(source, declarations, scope, consumer)
    }

    /** Absence must be proven: unresolved candidates are not evidence of an unused symbol. */
    @JvmStatic
    fun isUnused(element: PsiElement?): Boolean {
        return isUnused(element, null)
    }

    fun isUnused(element: PsiElement?, timing: AnalysisTiming?): Boolean {
        val source = snapshot(element) ?: return false

        val started = if (timing == null) 0 else System.nanoTime()
        var references: MutableList<LocationData>?
        try {
            references = references(source)
        } finally {
            timing?.referenceCompleted(System.nanoTime() - started)
        }
        source.checkUnchanged()

        if (references != null) {
            return references.isEmpty()
        }

        val declaration = ReadActions.compute {
            val position = LSPIJUtils.toPosition(source.offset, source.document)
            SymbolLocation(fileUri(source.file.virtualFile), position.line, position.character)
        }
        val scope = GlobalSearchScope.projectScope(source.file.project)

        for (file in candidateFiles(source.file.project, scope, source.name)) {
            val candidate = ReadActions.compute {
                val psi = PsiManager.getInstance(source.file.project).findFile(file)
                val doc = FileDocumentManager.getInstance().getDocument(file)
                if (psi == null || doc == null) {
                    null
                } else {
                    Source(psi, doc, doc.modificationStamp, 0, source.name)
                }
            }

            if (candidate == null) {
                return false
            }

            val offsets = ReadActions.compute {
                UsageCandidates.find(candidate.document.text, source.name).filter { offset ->
                    file != source.file.virtualFile || offset != source.offset
                }
            }

            val allCandidatesUnrelated =
                processDefinitions(
                    candidate.file,
                    candidate.document,
                    offsets,
                    check = {
                        source.checkUnchanged()
                        candidate.checkUnchanged()
                    }) { _, targets ->
                        targets.isNotEmpty() && targets.none { declaration == SymbolLocation.from(it.location()) }
                    }

            if (!allCandidatesUnrelated) {
                return false
            }
        }

        source.checkUnchanged()

        return true
    }

    private fun snapshot(element: PsiElement?): Source? {
        if (element == null) {
            return null
        }
        return ReadActions.compute {
            if (!element.isValid) {
                return@compute null
            }
            val file = element.containingFile ?: return@compute null
            val document = LSPIJUtils.getDocument(file) ?: return@compute null
            val leaf = file.findElementAt(element.textOffset) ?: return@compute null
            val name = leaf.text ?: return@compute null
            Source(file, document, document.modificationStamp, leaf.textOffset, name)
        }
    }

    // LSP4IJ's documented PSI conversion requires its client features.
    private fun processReferences(
        project: Project,
        references: MutableList<LocationData>,
        scope: SearchScope,
        consumer: Processor<in UsageInfo?>
    ): Boolean {
        val seen: MutableSet<String?> = HashSet()

        for (reference in references) {
            ProgressManager.checkCanceled()

            val target = ClientFeatureAccess.toPsiElement(reference, project)

            if (target != null && seen.add(reference.location().toString())) {
                val usage = ReadActions.compute {
                    if (PsiSearchScopeUtil.isInScope(scope, target)) {
                        UsageInfo(target)
                    } else {
                        null
                    }
                }

                if (usage != null && !consumer.process(usage)) {
                    return false
                }
            }
        }

        return true
    }

    private fun processCandidates(
        source: Source,
        declarations: MutableSet<SymbolLocation?>,
        scope: SearchScope,
        consumer: Processor<in UsageInfo?>
    ): Boolean {
        val project = source.file.project
        val files = candidateFiles(project, scope, source.name)
        var visited = 0

        for (file in files) {
            source.checkUnchanged()
            val indicator = ProgressManager.getInstance().progressIndicator

            if (indicator != null) {
                indicator.text = SlangBundle.message("usages.progress", source.name)
                indicator.text2 = file.name
                indicator.fraction = (visited++).toDouble() / max(1, files.size)
            }

            val candidate = ReadActions.compute {
                val psi = PsiManager.getInstance(project).findFile(file)
                val doc = FileDocumentManager.getInstance().getDocument(file)
                if (psi == null || doc == null) {
                    null
                } else {
                    Source(psi, doc, doc.modificationStamp, 0, source.name)
                }
            }

            if (candidate == null) {
                continue
            }

            val offsets = ReadActions.compute {
                UsageCandidates.find(candidate.document.text, source.name)
            }
            val inScopeOffsets = ReadActions.compute {
                offsets.filter { offset ->
                    val position = LSPIJUtils.toPosition(offset, candidate.document)
                    val location = SymbolLocation(fileUri(file), position.line, position.character)
                    if (location in declarations) {
                        return@filter false
                    }
                    val leaf = candidate.file.findElementAt(offset)
                    leaf != null && PsiSearchScopeUtil.isInScope(scope, leaf)
                }
            }

            val completed =
                processDefinitions(
                    candidate.file,
                    candidate.document,
                    inScopeOffsets,
                    check = {
                        source.checkUnchanged()
                        candidate.checkUnchanged()
                    }) { offset, targets ->
                        if (targets.none { SymbolLocation.from(it.location()) in declarations }) {
                            return@processDefinitions true
                        }
                        val usage = ReadActions.compute {
                            UsageInfo(candidate.file, offset, offset + source.name.length)
                        }
                        consumer.process(usage)
                    }

            if (!completed) {
                return false
            }
        }

        return true
    }

    @JvmStatic
    fun candidateFiles(project: Project, scope: SearchScope, name: String): MutableSet<VirtualFile> {
        return ReadActions.compute {
            val files: MutableSet<VirtualFile?> = LinkedHashSet()
            if (scope is LocalSearchScope) {
                for (element in scope.scope) {
                    if (element.containingFile != null) {
                        files.add(element.containingFile.virtualFile)
                    }
                }
            } else {
                val global = scope as? GlobalSearchScope ?: GlobalSearchScope.projectScope(project)
                PsiSearchHelper.getInstance(project)
                    .processAllFilesWithWord(
                        name,
                        GlobalSearchScope.getScopeRestrictedByFileTypes(global, SlangFileType),
                        Processor { psi: PsiFile? ->
                            files.add(psi!!.virtualFile)
                            true
                        },
                        true)
            }

            // A just-typed occurrence may not be in the word index yet.
            for (document in FileDocumentManager.getInstance().unsavedDocuments) {
                val file = FileDocumentManager.getInstance().getFile(document)

                if (file != null && scope.contains(file) && document.text.contains(name)) {
                    files.add(file)
                }
            }

            files.removeIf { file: VirtualFile? ->
                file == null || !file.isValid || file is BuiltinFile || file.fileType !== SlangFileType
            }
            files.filterNotNullTo(LinkedHashSet())
        }
    }

    private fun references(source: Source): MutableList<LocationData>? {
        if (BatchAnalysisService.isBatch) {
            val future =
                source.file.project
                    .getService(BatchAnalysisService::class.java)
                    .references(source.file, source.document, source.offset)
            try {
                return AnalysisDeadline.await(future)?.mapTo(ArrayList()) { checkNotNull(it) }
            } finally {
                if (!future.isDone) {
                    future.cancel(true)
                }
            }
        }
        val support = LSPReferenceSupport(source.file)
        val future =
            ReadActions.compute<CompletableFuture<MutableList<LocationData?>?>> {
                val params =
                    LSPReferenceParams(
                        TextDocumentIdentifier(), LSPIJUtils.toPosition(source.offset, source.document), source.offset)
                params.context = ReferenceContext(false)
                support.getReferences(params)
            }

        try {
            return AnalysisDeadline.await(future)?.mapTo(ArrayList()) { checkNotNull(it) }
        } finally {
            if (!future.isDone) {
                support.cancel()
            }
        }
    }

    private fun requestDefinition(file: PsiFile, document: Document, offset: Int): DefinitionRequest {
        if (BatchAnalysisService.isBatch) {
            val name =
                ReadActions.compute<@NlsSafe String?> {
                    val leaf = file.findElementAt(offset)
                    if (isSlangIdentifier(leaf)) {
                        leaf?.text
                    } else {
                        null
                    }
                }
            return DefinitionRequest(
                null,
                file.project
                    .getService<BatchAnalysisService?>(BatchAnalysisService::class.java)!!
                    .definitions(file, document, offset),
                file,
                offset,
                name)
        }
        val support = LSPDefinitionSupport(file)

        try {
            val future =
                ReadActions.compute<CompletableFuture<MutableList<LocationData?>?>?> {
                    support.getDefinitions(
                        LSPDefinitionParams(TextDocumentIdentifier(), LSPIJUtils.toPosition(offset, document), offset))
                }

            val name =
                ReadActions.compute<@NlsSafe String?> {
                    val leaf = file.findElementAt(offset)
                    if (isSlangIdentifier(leaf)) {
                        leaf?.text
                    } else {
                        null
                    }
                }

            return DefinitionRequest(support, future, file, offset, name)
        } catch (failure: RuntimeException) {
            support.cancel()
            throw failure
        }
    }

    @JvmStatic
    fun definitions(file: PsiFile, document: Document, offset: Int): List<LocationData> {
        requestDefinition(file, document, offset).use { request ->
            return request.await()
        }
    }

    // Keep a small window in flight, preserving result order and cancelling unused requests.
    fun processDefinitions(
        file: PsiFile,
        document: Document,
        offsets: List<Int>,
        check: () -> Unit,
        consumer: (offset: Int, targets: List<LocationData>) -> Boolean
    ): Boolean {
        var start = 0
        while (start < offsets.size) {
            val requests = mutableListOf<DefinitionRequest>()

            try {
                val end = min(start + 8, offsets.size)

                for (i in start..<end) {
                    ProgressManager.checkCanceled()
                    ReadActions.run(check)
                    requests.add(requestDefinition(file, document, offsets[i]))
                }

                for (i in requests.indices) {
                    val targets = requests[i].await()
                    ReadActions.run(check)

                    if (!consumer(offsets[start + i], targets)) {
                        return false
                    }
                }
            } finally {
                requests.forEach { it.close() }
            }
            start += 8
        }

        return true
    }

    @JvmRecord
    private data class Source(
        val file: PsiFile,
        val document: Document,
        val stamp: Long,
        val offset: Int,
        val name: String
    ) {
        fun checkUnchanged() {
            ProgressManager.checkCanceled()

            if (document.modificationStamp != stamp) {
                throw ProcessCanceledException()
            }
        }
    }

    @JvmRecord
    private data class DefinitionRequest(
        val support: LSPDefinitionSupport?,
        val future: CompletableFuture<MutableList<LocationData?>?>?,
        val file: PsiFile,
        val offset: Int,
        val name: String?
    ) : AutoCloseable {
        override fun close() {
            if (!future!!.isDone) {
                if (support == null) {
                    future.cancel(true)
                } else {
                    support.cancel()
                }
            }
        }

        fun await(): List<LocationData> {
            val result = AnalysisDeadline.await(future) ?: return emptyList()
            return DefinitionLocations.normalize(file, offset, name, result.map { checkNotNull(it) })
        }
    }
}
