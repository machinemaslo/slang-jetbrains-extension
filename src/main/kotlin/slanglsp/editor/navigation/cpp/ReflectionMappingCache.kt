package slanglsp.editor.navigation.cpp

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFile

/** Remembers the user's choice for this project while both documents and the project roots stay unchanged. */
@Service(Service.Level.PROJECT)
internal class ReflectionMappingCache(private val project: Project) {
    private data class Key(val shader: VirtualFile, val offset: Int)
    private data class Entry(
        val stamp: Long,
        val roots: Long,
        val location: ReflectionSearch.Location,
        val configuration: ActiveCppConfiguration?,
        val generatedFiles: Set<VirtualFile>,
    )
    private val choices = LinkedHashMap<Key, Entry>()

    fun getValid(
        shader: VirtualFile,
        offset: Int,
        stamp: Long,
        configuration: ActiveCppConfiguration?,
    ): ReflectionSearch.Result? {
        val entry = synchronized(choices) { choices[Key(shader, offset)] } ?: return null
        return runReadActionBlocking {
            entry.location.takeIf {
                entry.stamp == stamp && entry.configuration == configuration &&
                    entry.roots == ProjectRootModificationTracker.getInstance(project).modificationCount &&
                    it.file.isValid && FileDocumentManager.getInstance().getDocument(it.file)?.modificationStamp == it.stamp
            }?.let { ReflectionSearch.Result(listOf(ReflectionSearch.Match("", it, true)), emptyList(), false, entry.generatedFiles) }
        }
    }

    fun put(
        shader: VirtualFile,
        offset: Int,
        stamp: Long,
        location: ReflectionSearch.Location,
        configuration: ActiveCppConfiguration?,
        generatedFiles: Set<VirtualFile>,
    ) {
        val roots = ProjectRootModificationTracker.getInstance(project).modificationCount
        synchronized(choices) {
            if (choices.size >= 128) choices.remove(choices.keys.first())
            choices[Key(shader, offset)] = Entry(stamp, roots, location, configuration, generatedFiles)
        }
    }
}
