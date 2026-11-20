package slanglsp.editor.navigation.cpp

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext

/** Reuses the IDE's word index, then inspects only bounded C++ source candidates. */
internal object ReflectionSearch {
    data class Location(val file: VirtualFile, val stamp: Long, val offset: Int, val line: Int)
    data class Match(val type: String, val location: Location, val typeOnly: Boolean)
    data class Result(
        val matches: List<Match>,
        val sites: List<Location>,
        val incomplete: Boolean,
        val generatedFiles: Set<VirtualFile> = emptySet(),
    )

    fun find(project: Project, slangName: String): Result {
        val matches = mutableListOf<Match>()
        val sites = mutableListOf<Pair<ReflectionSource.Site, Location>>()
        val seen = hashSetOf<VirtualFile>()
        var charsRemaining = MAX_TOTAL_CHARS
        var inspected = 0
        var incomplete = false
        val scope = GlobalSearchScope.projectScope(project)
        val documents = FileDocumentManager.getInstance()

        fun inspect(file: VirtualFile) {
            ProgressManager.checkCanceled()
            if (file.extension?.lowercase() !in cppExtensions || !seen.add(file)) {
                return
            }
            if (inspected >= MAX_FILES || charsRemaining == 0 || file.length > MAX_FILE_CHARS) {
                incomplete = true
                return
            }
            inspected++
            val snapshot = runReadActionBlocking {
                val document = documents.getDocument(file) ?: return@runReadActionBlocking null
                val size = document.textLength
                if (size > MAX_FILE_CHARS || size > charsRemaining) {
                    incomplete = true
                    return@runReadActionBlocking null
                }
                document.immutableCharSequence to document.modificationStamp
            } ?: return

            val (source, stamp) = snapshot
            charsRemaining -= source.length
            val evidence = ReflectionSource.inspect(source, slangName)
            fun location(offset: Int): Location {
                val line = (0 until offset).count { source[it] == '\n' } + 1
                return Location(file, stamp, offset, line)
            }

            for ((name, offset, typeOnly) in evidence.types) {
                if (matches.size == MAX_MATCHES) {
                    incomplete = true
                    break
                }
                matches.add(Match(name, location(offset), typeOnly))
            }
            val site = evidence.site
            if (site != null && sites.size < MAX_FILES) {
                sites.add(site to location(site.offset))
            }
        }

        fun inspectIndexed(words: List<String>) {
            val search = PsiSearchHelper.getInstance(project)
            for (word in words) {
                val files = runReadActionBlocking {
                    val candidates = mutableListOf<VirtualFile>()
                    search.processCandidateFilesForText(scope, UsageSearchContext.ANY, true, word) { file ->
                        if (file.extension?.lowercase() in cppExtensions && file !in seen) {
                            candidates.add(file)
                        }
                        candidates.size <= MAX_FILES
                    }
                    candidates
                }
                if (files.size > MAX_FILES) {
                    incomplete = true
                }
                for (file in files.take(MAX_FILES)) {
                    inspect(file)
                }
            }
        }

        // Prioritize unsaved lookups; a just-typed occurrence can precede the index update.
        val editedFiles = runReadActionBlocking {
            documents.unsavedDocuments.mapNotNull(documents::getFile).filter(scope::contains)
        }
        editedFiles.forEach(::inspect)
        inspectIndexed(ReflectionSource.lookupCalls)
        if (matches.isEmpty()) {
            inspectIndexed(ReflectionSource.layoutCalls)
        }
        if (sites.isEmpty() && !incomplete) {
            inspectIndexed(ReflectionSource.programCalls)
        }

        return Result(
            matches.sortedWith(compareBy({ it.type }, { it.location.file.path }, { it.location.offset })),
            sites.sortedWith(compareBy({ !it.first.namesType }, { it.second.file.path })).map { it.second },
            incomplete,
        )
    }

    private const val MAX_FILES = 96
    private const val MAX_FILE_CHARS = 512 * 1024
    private const val MAX_TOTAL_CHARS = 4 * 1024 * 1024
    private const val MAX_MATCHES = 24
    internal val cppExtensions = setOf(
        "c", "cc", "cp", "cpp", "cxx", "c++", "cu", "ixx", "cppm", "mxx", "cxxm", "cmm", "m", "mm",
        "h", "hh", "hp", "hpp", "hxx", "h++", "cuh", "inc", "inl", "ipp", "tcc", "tpp",
    )
}
