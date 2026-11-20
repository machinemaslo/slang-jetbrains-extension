package slanglsp.editor.rename

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager.Companion.getInstance
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewUtil
import java.net.URI
import org.jetbrains.annotations.PropertyKey
import slanglsp.SlangBundle
import slanglsp.analysis.AnalysisDeadline
import slanglsp.editor.navigation.SymbolLocation
import slanglsp.editor.navigation.UsageCandidates
import slanglsp.editor.navigation.UsageSearch
import slanglsp.language.SlangFileType
import slanglsp.server.BuiltinFiles.BuiltinFile
import slanglsp.server.protocol.DocumentUris

/** A project-local rename verified by definitions, with an immutable document snapshot. */
internal class RenamePlan
private constructor(
    private val project: Project,
    val oldName: String,
    val newName: String,
    sources: List<Source>,
    edits: List<Edit>
) {
    @JvmRecord
    private data class Source(
        val file: PsiFile,
        val document: Document,
        val stamp: Long,
        val text: String,
        val uri: URI
    ) {
        fun check() {
            check(
                !(!file.isValid ||
                    !file.virtualFile.isValid ||
                    document.modificationStamp != stamp ||
                    (uri != DocumentUris.fileUri(file.virtualFile)))) {
                    SlangBundle.message("rename.files.changed.during.rename.run.rename.again")
                }
        }
    }

    @JvmRecord private data class Edit(val source: Source?, val offset: Int)

    private val sources = sources.toList()
    private val edits = edits.toList()

    @JvmRecord
    private data class Declaration(
        val source: Source?,
        val symbol: SymbolLocation?,
        val offset: Int,
        val confirmed: Boolean
    )

    fun usages(): Array<UsageInfo> {
        return runReadActionBlocking {
            validate()
            edits
                .map { edit ->
                    UsageInfo(edit.source!!.file, edit.offset, edit.offset + oldName.length)
                }
                .toTypedArray()
        }
    }

    fun requiresPreview(): Boolean {
        val usages = usages()
        return runReadActionBlocking {
            UsageViewUtil.reportNonRegularUsages(usages, project) ||
                UsageViewUtil.hasReadOnlyUsages(usages) ||
                edits.any { !it.source!!.document.isWritable } ||
                hasUnloadedModules()
        }
    }

    /** The platform exposes unloaded-module detection only through this experimental API. */
    @Suppress("UnstableApiUsage")
    private fun hasUnloadedModules(): Boolean {
        return !getInstance(project).unloadedModuleDescriptions.isEmpty()
    }

    private fun validate() {
        val original: MutableSet<VirtualFile?> = HashSet()

        for (source in sources) {
            source.check()
            original.add(source.file.virtualFile)
        }

        check(original == projectFiles(project)) {
            SlangBundle.message("rename.project.files.changed.run.rename.again")
        }

        for ((source, offset) in edits) {
            check(source!!.document.text.regionMatches(offset, oldName, 0, oldName.length)) {
                SlangBundle.message("rename.a.usage.changed.run.rename.again")
            }
        }
    }

    fun apply() {
        WriteCommandAction.runWriteCommandAction(
            project,
            SlangBundle.message("rename.command", oldName, newName),
            null,
            {
                // Validate every file before the first edit; never apply a partial stale plan.
                validate()
                for (edit in edits) {
                    check(!(!edit.source!!.file.isWritable || !edit.source.document.isWritable)) {
                        SlangBundle.message("rename.read.only", edit.source.file.name)
                    }
                }
                CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)
                for ((source, offset) in edits.sortedByDescending(Edit::offset)) {
                    source!!.document.replaceString(offset, offset + oldName.length, newName)
                }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            })
    }

    companion object {
        @JvmStatic
        fun validName(name: String?): Boolean {
            return name != null &&
                name.matches("[A-Za-z_][A-Za-z0-9_]*".toRegex()) &&
                UsageCandidates.find(name, name) == listOf(0)
        }

        private fun projectFiles(project: Project): MutableSet<VirtualFile> {
            val scope = GlobalSearchScope.projectScope(project)
            val files: MutableSet<VirtualFile> = LinkedHashSet(FileTypeIndex.getFiles(SlangFileType, scope))

            for (document in FileDocumentManager.getInstance().unsavedDocuments) {
                val file = FileDocumentManager.getInstance().getFile(document)

                if (file != null && scope.contains(file) && file.fileType === SlangFileType) {
                    files.add(file)
                }
            }

            files.removeIf { file: VirtualFile? -> file is BuiltinFile }

            return files
        }

        @JvmStatic
        fun collect(element: PsiElement?, newName: String?): RenamePlan {
            if (!validName(newName)) {
                throw invalidRename("rename.enter.a.slang.identifier.not.a.keyword.or.built.in.type")
            }

            val project = element!!.project
            val name = runReadActionBlocking<@NlsSafe String?> { element.text }

            if (!validName(name) || name == newName) {
                throw invalidRename("rename.choose.a.different.identifier")
            }

            val sources: MutableList<Source> = snapshotSources(project, name!!)
            val edits = AnalysisDeadline.open().use {
                collectEdits(sources, resolveDeclaration(element, sources, name), name)
            }
            val plan = RenamePlan(project, name, newName!!, sources, edits)

            runReadActionBlocking { plan.validate() }

            return plan
        }

        private fun snapshotSources(project: Project, name: String): MutableList<Source> {
            val scope = GlobalSearchScope.projectScope(project)
            val candidates = UsageSearch.candidateFiles(project, scope, name)

            return runReadActionBlocking {
                val result = ArrayList<Source>()
                for (file in projectFiles(project)) {
                    ProgressManager.checkCanceled()
                    val psi = PsiManager.getInstance(project).findFile(file)
                    val document = FileDocumentManager.getInstance().getDocument(file)

                    check(!(psi == null || document == null)) {
                        SlangBundle.message("rename.cannot.read", file.name)
                    }

                    val text = if (candidates.contains(file)) document.text else ""
                    result.add(Source(psi, document, document.modificationStamp, text, DocumentUris.fileUri(file)))
                }
                result
            }
        }

        private fun resolveDeclaration(element: PsiElement, sources: MutableList<Source>, name: String): Declaration {
            val origin = runReadActionBlocking {
                val file = element.containingFile
                sources.firstOrNull { it.file === file }
                    ?: throw invalidRename("rename.only.symbols.declared.in.this.project.s.slang.files.can.be.renamed")
            }

            val originOffset = runReadActionBlocking { element.textOffset }
            val targets: MutableSet<SymbolLocation?> = definitions(origin, originOffset)

            if (targets.size > 1) {
                throw invalidRename("rename.the.symbol.has.multiple.definitions.and.cannot.be.renamed.safely")
            }

            val symbol: SymbolLocation =
                (if (targets.isEmpty())
                    runReadActionBlocking {
                        SymbolLocation.at(origin.uri, origin.document, originOffset)
                    }
                else checkNotNull(targets.single()))
            val source =
                sources.firstOrNull { it.uri == symbol.uri }
                    ?: throw invalidRename("rename.built.in.and.external.declarations.cannot.be.renamed")

            val offset: Int = declarationOffset(source, symbol, name)

            if (offset < 0) {
                throw invalidRename("rename.slangd.did.not.identify.a.matching.declaration")
            }

            val confirmed =
                targets.isNotEmpty() ||
                    RenameDeclarations.contains(source.file, source.document, source.text, name, offset) {
                        source.check()
                    }
            return Declaration(source, symbol, offset, confirmed)
        }

        private fun declarationOffset(source: Source, symbol: SymbolLocation, name: String): Int {
            return runReadActionBlocking {
                source.check()

                if (symbol.line < 0 || symbol.line >= source.document.lineCount) {
                    return@runReadActionBlocking -1
                }

                val offset: Int = source.document.getLineStartOffset(symbol.line) + symbol.character

                if (UsageCandidates.find(source.text, name).contains(offset)) {
                    offset
                } else {
                    -1
                }
            }
        }

        private fun collectEdits(
            sources: MutableList<Source>,
            declaration: Declaration,
            name: String
        ): MutableList<Edit> {
            val edits: MutableList<Edit> = ArrayList()
            edits.add(Edit(declaration.source, declaration.offset))
            val confirmed = booleanArrayOf(declaration.confirmed)

            for (source in sources) {
                val indicator = ProgressManager.getInstance().progressIndicator

                if (indicator != null) {
                    indicator.text2 = source.file.name
                }

                val offsets =
                    UsageCandidates.find(source.text, name).filter { offset ->
                        source !== declaration.source || offset != declaration.offset
                    }

                UsageSearch.processDefinitions(source.file, source.document, offsets, verify = true, check = { source.check() }) {
                    offset,
                    locations ->
                    val targets = locations.mapTo(mutableSetOf(), SymbolLocation::from)
                    if (targets.isEmpty() &&
                        !RenameDeclarations.contains(source.file, source.document, source.text, name, offset) {
                            source.check()
                        }) {
                        throw invalidRename("rename.slangd.could.not.verify.the.rename")
                    }

                    if (declaration.symbol in targets) {
                        if (targets.size != 1) {
                            throw invalidRename("rename.an.ambiguous.usage.prevents.a.safe.rename")
                        }

                        edits.add(Edit(source, offset))
                        confirmed[0] = true
                    }
                    true
                }
            }

            if (!confirmed[0]) {
                throw invalidRename(
                    "rename.slangd.could.not.verify.this.declaration.rename.is.unavailable.for.this.symbol")
            }

            return edits
        }

        private fun invalidRename(
            @PropertyKey(resourceBundle = "messages.SlangBundle") messageKey: String
        ): IllegalArgumentException {
            return IllegalArgumentException(SlangBundle.message(messageKey))
        }

        private fun definitions(source: Source, offset: Int): MutableSet<SymbolLocation?> {
            runReadActionBlocking { source.check() }
            val locations = UsageSearch.definitions(source.file, source.document, offset, verify = true)
            runReadActionBlocking { source.check() }
            val result: MutableSet<SymbolLocation?> = HashSet()
            locations.mapTo(result, SymbolLocation::from)

            return result
        }
    }
}
