package slanglsp.editor.navigation

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.*
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import java.util.concurrent.CompletableFuture
import kotlin.math.max
import kotlin.math.min
import org.eclipse.lsp4j.Location
import slanglsp.SlangBundle
import slanglsp.analysis.AnalysisDeadline
import slanglsp.analysis.AnalysisTiming
import slanglsp.language.SlangFileType
import slanglsp.server.BuiltinFiles.BuiltinFile
import slanglsp.server.nativeclient.NativeNavigationRequests
import slanglsp.server.nativeclient.runningSlangClient
import slanglsp.server.protocol.DocumentUris.fileUri

/** Indexed candidates verified with bounded requests. Callers may hold an outer read lock. */
internal object UsageSearch {
    fun process(element: PsiElement?, scope: SearchScope, consumer: Processor<in UsageInfo?>): Boolean {
        val source = snapshot(element) ?: return true

        val project = source.file.project
        val declarations: MutableSet<SymbolLocation?> = HashSet()
        val virtualFile = source.file.virtualFile

        if (virtualFile is BuiltinFile) {
            declarations.add(runReadActionBlocking { SymbolLocation.at(virtualFile.uri(), source.document, source.offset) })
        } else {
            val references = references(source)
            source.checkUnchanged()

            // A missing response falls back to indexed candidates. Empty is a valid result.
            if (references != null) {
                return processReferences(project, references, scope, consumer)
            }

            for (location in definitions(source.file, source.document, source.offset, verify = true)) {
                declarations.add(SymbolLocation.from(location))
            }
        }

        // slangd returns null for some declaration-site definition requests (including globals).
        // In that case use the source location; candidates still require semantic confirmation.
        if (declarations.isEmpty()) {
            declarations.add(runReadActionBlocking { SymbolLocation.at(fileUri(virtualFile), source.document, source.offset) })
        }

        source.checkUnchanged()

        return processCandidates(source, declarations, scope, consumer)
    }

    /** Absence must be proven: unresolved candidates are not evidence of an unused symbol. */
    @JvmStatic
    fun isUnused(element: PsiElement?): Boolean {
        return isUnused(element, null)
    }

    fun isUnused(element: PsiElement?, timing: AnalysisTiming?, batch: Boolean = false): Boolean {
        val source = snapshot(element) ?: return false
        val project = source.file.project
        if (!batch && runningSlangClient(project) == null) {
            return false
        }

        val started = if (timing != null) System.nanoTime() else 0
        try {
            // The editor client may not know about unopened files. Verify indexed candidates instead.
            return noVerifiedUsage(source)
        } finally {
            timing?.referenceCompleted(System.nanoTime() - started)
        }
    }

    private fun noVerifiedUsage(source: Source): Boolean {
        val declaration = runReadActionBlocking {
            SymbolLocation.at(fileUri(source.file.virtualFile), source.document, source.offset)
        }
        val scope = GlobalSearchScope.projectScope(source.file.project)

        for (file in candidateFiles(source.file.project, scope, source.name)) {
            val candidate = runReadActionBlocking {
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

            val offsets = runReadActionBlocking {
                UsageCandidates.find(candidate.document.text, source.name).filter { offset ->
                    file != source.file.virtualFile || offset != source.offset
                }
            }

            val allCandidatesUnrelated =
                processDefinitions(
                    candidate.file,
                    candidate.document,
                    offsets,
                    verify = true,
                    check = {
                        source.checkUnchanged()
                        candidate.checkUnchanged()
                    }) { _, targets ->
                        targets.isNotEmpty() && targets.none { declaration == SymbolLocation.from(it) }
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
        return runReadActionBlocking {
            if (!element.isValid) {
                return@runReadActionBlocking null
            }
            val file = element.containingFile ?: return@runReadActionBlocking null
            val virtualFile = file.virtualFile ?: return@runReadActionBlocking null
            if (!virtualFile.isInLocalFileSystem && virtualFile !is BuiltinFile) {
                return@runReadActionBlocking null
            }
            val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return@runReadActionBlocking null
            val leaf = file.findElementAt(element.textOffset) ?: return@runReadActionBlocking null
            val name = leaf.text ?: return@runReadActionBlocking null
            Source(file, document, document.modificationStamp, leaf.textOffset, name)
        }
    }

    private fun processReferences(
        project: Project,
        references: List<Location>,
        scope: SearchScope,
        consumer: Processor<in UsageInfo?>
    ): Boolean {
        val seen: MutableSet<String?> = HashSet()

        for (reference in references) {
            ProgressManager.checkCanceled()

            val target = NavigationTargets.toPsiElement(reference, project)

            if (target != null && seen.add(reference.toString())) {
                val usage = runReadActionBlocking {
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

            val candidate = runReadActionBlocking {
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

            val offsets = runReadActionBlocking {
                UsageCandidates.find(candidate.document.text, source.name)
            }
            val inScopeOffsets = runReadActionBlocking {
                offsets.filter { offset ->
                    val location = SymbolLocation.at(fileUri(file), candidate.document, offset)
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
                    verify = true,
                    check = {
                        source.checkUnchanged()
                        candidate.checkUnchanged()
                    }) { offset, targets ->
                        if (targets.none { SymbolLocation.from(it) in declarations }) {
                            return@processDefinitions true
                        }
                        val usage = runReadActionBlocking {
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
        return runReadActionBlocking {
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

    private fun references(source: Source): List<Location>? {
        val file = source.file.virtualFile ?: return null
        val future = source.file.project.service<NativeNavigationRequests>()
            .references(file, source.document, source.offset)
        try {
            return AnalysisDeadline.await(future)
        } finally {
            if (!future.isDone) {
                future.cancel(true)
            }
        }
    }

    private fun requestDefinition(file: PsiFile, document: Document, offset: Int, verify: Boolean): DefinitionRequest {
        val name = runReadActionBlocking<@NlsSafe String?> {
            file.findElementAt(offset)?.takeIf(::isSlangIdentifier)?.text
        }

        val future = file.virtualFile?.let {
            file.project.service<NativeNavigationRequests>().definitions(it, document, offset, verify)
        }
        return DefinitionRequest(future, file, offset, name, verify)
    }

    @JvmStatic
    fun definitions(file: PsiFile, document: Document, offset: Int, verify: Boolean = false): List<Location> {
        requestDefinition(file, document, offset, verify).use { request ->
            return request.await()
        }
    }

    // Keep a small window in flight, preserving result order and cancelling unused requests.
    fun processDefinitions(
        file: PsiFile,
        document: Document,
        offsets: List<Int>,
        verify: Boolean = false,
        check: () -> Unit,
        consumer: (offset: Int, targets: List<Location>) -> Boolean
    ): Boolean {
        var start = 0
        while (start < offsets.size) {
            val requests = mutableListOf<DefinitionRequest>()

            try {
                val end = min(start + 8, offsets.size)

                for (i in start..<end) {
                    ProgressManager.checkCanceled()
                    runReadActionBlocking(check)
                    requests.add(requestDefinition(file, document, offsets[i], verify))
                }

                for (i in requests.indices) {
                    val targets = requests[i].await()
                    runReadActionBlocking(check)

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
        val future: CompletableFuture<List<Location>?>?,
        val file: PsiFile,
        val offset: Int,
        val name: String?,
        val verify: Boolean = false,
    ) : AutoCloseable {
        override fun close() {
            if (future != null && !future.isDone) {
                future.cancel(true)
            }
        }

        fun await(): List<Location> {
            val pending = future ?: return emptyList()
            val result = AnalysisDeadline.await(pending) ?: return emptyList()

            return DefinitionLocations.normalize(file, offset, name, result, verify)
        }
    }
}
