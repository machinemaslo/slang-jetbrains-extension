package slanglsp.editor.navigation.cpp

import com.intellij.ide.util.gotoByName.GotoClassModel2
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.util.indexing.FindSymbolParameters
import java.nio.file.Path
import slanglsp.language.SlangFileType

/** Ask Nova for the type, including included files outside project content roots. Keep only locations. */
internal object GeneratedStructLookup {
    fun find(project: Project, filePath: Path, shader: CharSequence, offset: Int, indicator: ProgressIndicator): List<ReflectionSearch.Match> {
        if (shader.length > MAX_BYTES) {
            return emptyList()
        }
        val structs = ReflectionProbeMatcher.structs(shader.toString())
        val source = structs.firstOrNull { it.offset == offset } ?: return emptyList()
        val shaderLine = shader.subSequence(0, offset).count { it == '\n' } + 1
        val lineStart = shader.lastIndexOf('\n', offset) + 1
        val lineEnd = shader.indexOf('\n', offset).let { if (it < 0) shader.length else it }
        val uniqueLine = structs.count { it.offset in lineStart until lineEnd } == 1
        val scope = GlobalSearchScope.allScope(project)
        val files = linkedSetOf<VirtualFile>()
        val model = GotoClassModel2(project)
        try {
            // Nova may consult its backend here; let a pending write cancel and restart the read.
            val indexed = runBlockingCancellable {
                readAction {
                    val symbols = model.getElementsByName(source.name, FindSymbolParameters.wrap(source.name, scope), indicator)
                    symbols.asSequence().filterIsInstance<PsiElement>().mapNotNull { symbol ->
                        val psi = symbol.containingFile ?: return@mapNotNull null
                        val file = psi.virtualFile ?: return@mapNotNull null
                        file.takeIf {
                            file.fileType !== SlangFileType && (file.extension?.lowercase() in ReflectionSearch.cppExtensions ||
                                psi.language.id in setOf("C++", "ObjectiveC"))
                        }
                    }.take(MAX_FILES).toList()
                }
            }
            files.addAll(indexed)
        } finally {
            Disposer.dispose(model)
        }

        // A language plugin may index words before its native type index has caught up.
        runReadActionBlocking {
            val search = PsiSearchHelper.getInstance(project)
            val pathWord = Regex("[A-Za-z0-9_]+").findAll(filePath.fileName.toString().substringBeforeLast('.'))
                .maxByOrNull { it.value.length }?.value
            for (word in listOfNotNull(pathWord, source.name).distinct()) {
                if (files.size >= MAX_FILES) break
                search.processCandidateFilesForText(scope, UsageSearchContext.ANY, true, word) {
                    if (it.extension?.lowercase() in ReflectionSearch.cppExtensions) files.add(it)
                    files.size < MAX_FILES
                }
            }
        }
        var remaining = MAX_BYTES * 4
        return buildList {
            for (file in files) {
                indicator.checkCanceled()
                if (!file.isValid || file.length > minOf(remaining, MAX_BYTES)) {
                    continue
                }
                val snapshot = runReadActionBlocking {
                    val document = FileDocumentManager.getInstance().getDocument(file)
                        ?: return@runReadActionBlocking null
                    if (document.textLength > minOf(remaining, MAX_BYTES)) null
                    else document.immutableCharSequence to document.modificationStamp
                } ?: continue
                val text = snapshot.first.toString()
                remaining -= text.length
                val mapped = if (uniqueLine) sourceMappedLines(text, filePath, shaderLine) else emptySet()
                var line = 1
                var cursor = 0
                for ((name, offset1, fields) in ReflectionProbeMatcher.structs(text)) {
                    while (cursor < offset1) {
                        if (text[cursor++] == '\n') line++
                    }
                    if (line in mapped || (source.fields.isNotEmpty() && name == source.name && fields == source.fields)) {
                        add(ReflectionSearch.Match(name, ReflectionSearch.Location(file, snapshot.second, offset1, line), line in mapped))
                    }
                }
            }
        }
    }

    /** Standard #line and compiler linemarkers refer to the following physical line. */
    internal fun sourceMappedLines(text: String, file: Path, line: Int): Set<Int> {
        if ('#' !in text) return emptySet()
        val result = mutableSetOf<Int>()
        var candidate = -1
        var cursor = 0
        var physical = 1
        var sameFile = false
        for ((text1, offset) in sourceTokens(text).filter { it.text.startsWith("#") }) {
            while (cursor < offset) {
                if (text[cursor++] == '\n') physical++
            }
            if (candidate in 1 until physical) result.add(candidate)
            val marker = lineDirective.matchEntire(text1)
            if (marker == null) {
                // Conditional compilation and macros aren't source mapping evidence.
                candidate = -1
                sameFile = false
                continue
            }
            val path = marker.groups[2]?.value
            if (path != null) {
                sameFile = runCatching { Path.of(path.replace("\\\\", "\\")).normalize() == file.normalize() }.getOrDefault(false)
            }
            val mapped = marker.groupValues[1].toIntOrNull()
            candidate = if (sameFile && mapped != null && mapped in 1..line) physical + 1 + (line - mapped) else -1
        }
        if (candidate > 0) result.add(candidate)
        return result
    }

    private val lineDirective = Regex("""#\s*(?:line\s+)?(\d+)(?:\s+"([^"]+)")?(?:\s+\d+)*\s*""")
    private const val MAX_FILES = 32
    private const val MAX_BYTES = 2 * 1024 * 1024
}
