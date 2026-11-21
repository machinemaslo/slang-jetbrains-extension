package slanglsp.editor.navigation.cpp

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.IOException
import java.nio.file.Files
import java.util.HexFormat

/** The checked-in header is the registration; the cached VFS file follows moves within the project. */
@Service(Service.Level.PROJECT)
internal class ReflectionHeaderRegistry(private val project: Project) {
    sealed interface Lookup {
        data class Found(val file: VirtualFile) : Lookup
        data object Missing : Lookup
        data object Ambiguous : Lookup
    }

    data class Update(val file: VirtualFile, val installedVersion: Int, val bundledVersion: Int, internal val digest: String)

    @Volatile
    private var cached: VirtualFile? = null

    fun find(root: VirtualFile): Lookup {
        cached?.takeIf { isHeader(root, it) }?.let { return Lookup.Found(it) }
        val files = FilenameIndex.getVirtualFilesByName(HEADER_NAME, GlobalSearchScope.projectScope(project))
            .filter { isHeader(root, it) }

        return when (files.size) {
            0 -> Lookup.Missing
            1 -> Lookup.Found(files.single().also { cached = it })
            else -> Lookup.Ambiguous
        }
    }

    fun isHeaderFolder(root: VirtualFile, folder: VirtualFile): Boolean =
        folder.isValid && folder.isDirectory && isProjectContent(root, folder)

    @Throws(IOException::class)
    fun create(root: VirtualFile, folder: VirtualFile): VirtualFile {
        if (!isHeaderFolder(root, folder)) {
            throw IOException("The selected folder is outside the project's indexed content.")
        }

        val existing = folder.findChild(HEADER_NAME)
        val file = existing ?: createWithTemplate(folder)
        if (!isHeader(root, file)) {
            throw IOException("The requested header path does not refer to a project file.")
        }

        cached = file
        return file
    }

    /** The setup-only version left an empty file. Upgrade it only with explicit consent. */
    fun canUpgrade(root: VirtualFile, file: VirtualFile): Boolean {
        if (!isHeader(root, file) || file.length != 0L) {
            return false
        }
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(file)
        return document == null || (document.textLength == 0 && !documents.isDocumentUnsaved(document))
    }

    @Throws(IOException::class)
    fun upgradeEmpty(root: VirtualFile, file: VirtualFile) {
        if (!canUpgrade(root, file)) {
            throw IOException("The reflection header is no longer empty.")
        }
        file.setBinaryContent(templateBytes())
    }

    fun outdated(root: VirtualFile, file: VirtualFile): Update? {
        if (!isHeader(root, file) || file.length !in 1L..MAX_HEADER_BYTES.toLong()) {
            return null
        }
        val bytes = try {
            val path = file.toNioPath().toRealPath()
            if (!path.startsWith(root.toNioPath().toRealPath()) || !Files.isRegularFile(path)) {
                return null
            }
            Files.newInputStream(path).use { it.readNBytes(MAX_HEADER_BYTES + 1) }
        } catch (_: IOException) {
            return null
        }
        if (bytes.size > MAX_HEADER_BYTES) {
            return null
        }

        val text = bytes.decodeToString().removePrefix("\uFEFF")
        val installed = versionIn(text) ?: legacyVersions[digest(text.replace("\r\n", "\n").encodeToByteArray())]
            ?: return null
        val bundled = bundledVersion
        return if (installed < bundled) Update(file, installed, bundled, digest(bytes)) else null
    }

    /** Replace only the exact revision the user confirmed, never unsaved editor changes. */
    @Throws(IOException::class)
    fun replaceOutdated(root: VirtualFile, update: Update): Boolean {
        val documents = FileDocumentManager.getInstance()
        if (documents.getCachedDocument(update.file)?.let(documents::isDocumentUnsaved) == true ||
            outdated(root, update.file) != update) {
            return false
        }
        update.file.setBinaryContent(templateBytes())
        return true
    }

    private fun createWithTemplate(folder: VirtualFile): VirtualFile {
        val contents = templateBytes()
        val file = folder.createChildData(this, HEADER_NAME)
        try {
            file.setBinaryContent(contents)
        } catch (failure: IOException) {
            try {
                file.delete(this)
            } catch (cleanup: IOException) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        return file
    }

    private fun isHeader(root: VirtualFile, file: VirtualFile): Boolean =
        file.isValid && !file.isDirectory && file.name == HEADER_NAME && isProjectContent(root, file)

    private fun isProjectContent(root: VirtualFile, file: VirtualFile): Boolean =
        file.isInLocalFileSystem && VfsUtilCore.isAncestor(root, file, false) &&
            ProjectFileIndex.getInstance(project).isInContent(file)

    companion object {
        const val HEADER_NAME = "jb-slanglsp-reflection-meta.hpp"
        private const val MAX_HEADER_BYTES = 1024 * 1024
        private val versionMarker = Regex(
            """^[ \t]*#[ \t]*define[ \t]+JB_SLANGLSP_REFLECTION_META_VERSION[ \t]+([0-9]+)[ \t]*\r?$""",
            RegexOption.MULTILINE,
        )
        // Only recognize exact unversioned drafts the installer previously shipped. Unknown custom headers stay untouched.
        private val legacyVersions = mapOf(
            "6e7504f2bb75d121a27fa4d502bfee857af9eddd37583bd5510d3381a65e4106" to 1,
            "bbe8ed133eb5bb7b9e144d6e63ce147d0b85292be13dd36f1e8b08e8f5ff22ba" to 2,
        )
        private val bundledVersion by lazy {
            checkNotNull(versionIn(templateBytes().decodeToString())) { "Bundled reflection helper has no version" }
        }

        private fun versionIn(text: String): Int? =
            versionMarker.findAll(text).singleOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

        private fun digest(bytes: ByteArray): String = HexFormat.of().formatHex(reflectionDigest(bytes))

        private fun templateBytes(): ByteArray =
            ReflectionHeaderRegistry::class.java.getResourceAsStream("/reflection/$HEADER_NAME")?.use { it.readBytes() }
                ?: throw IOException("The bundled reflection metadata helper is missing.")
    }
}
