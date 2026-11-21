package slanglsp.editor.navigation.cpp

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** The sole production source of Slang-to-C++ mappings. No source search, subprocesses, or alternate builds. */
@Service(Service.Level.PROJECT)
internal class ReflectionMetadataLookup(private val project: Project) {
    data class Declaration(
        val path: Path,
        val offset: Int,
        val name: String,
        val document: Document? = null,
        val stamp: Long? = document?.modificationStamp,
    ) {
        fun isCurrent(): Boolean = document?.modificationStamp == stamp
    }

    sealed interface Result {
        class Mapped(val matches: List<CppDeclaration>, val documentsAreCurrent: () -> Boolean) : Result

        enum class Unavailable : Result {
            NO_ACTIVE_BUILD,
            MISSING_PACK,
            MISSING_SHADER,
            NOT_MAPPED,
            STALE,
            CONFLICTING,
            BUILD_CHANGED,
        }
    }

    private val indexes = RevisionCache<Revision, ReflectionPack.Index>(4)
    private val records = RevisionCache<RecordKey, ReflectionPack.Shader>(8)
    private val mappings = RevisionCache<MappingKey, ValidatedMapping>(64)

    data class RenameInputs(val affected: Set<Path>, val recorded: Set<Path>)

    /** Identify recorded inputs without requiring the pre-rename source bytes to still match. */
    fun inputsForRename(
        workspace: Path,
        buildDirectory: Path,
        identity: ReflectionBuildIdentity,
        changed: Set<Path>,
        indicator: ProgressIndicator,
    ): RenameInputs? {
        try {
            val projectRoot = Root(workspace, Result.Unavailable.MISSING_SHADER)
            val buildRoot = Root(buildDirectory, Result.Unavailable.MISSING_PACK)
            val pack = metadataRevision(buildRoot, identity)
            val files = Validation(projectRoot, buildRoot, indicator)
            val affected = linkedSetOf<Path>()
            val recorded = linkedSetOf<Path>()
            for (path in changed) {
                indicator.checkCanceled()
                if (!path.startsWith(projectRoot.path) || path == projectRoot.path) {
                    continue
                }
                val relative = projectRoot.path.relativize(path).joinToString("/")
                requireMetadata(isReflectionPath(relative))
                val shader = loadRecord(pack, identity, relative, indicator) ?: continue
                if (shader.bindings.isEmpty()) {
                    continue
                }
                affected.add(path)
                recorded.add(path)
                for (input in shader.inputs) {
                    recorded.add(files.resolve(input.file).second)
                }
            }
            if (affected.isEmpty() || !pack.isCurrent()) {
                return null
            }
            return RenameInputs(affected + changed.intersect(recorded), recorded)
        } catch (_: Unavailable) {
            return null
        } catch (_: InvalidReflectionMetadata) {
            return null
        } catch (_: InvalidPathException) {
            return null
        } catch (_: IOException) {
            return null
        }
    }

    fun find(
        workspace: Path,
        configuration: ActiveCppConfiguration?,
        declaration: Declaration,
        indicator: ProgressIndicator,
    ): Result {
        if (configuration == null || configuration.targetName.isEmpty()) {
            return Result.Unavailable.NO_ACTIVE_BUILD
        }
        indicator.checkCanceled()
        if (!ActiveCppConfiguration.isCurrent(project, configuration)) {
            return Result.Unavailable.BUILD_CHANGED
        }

        val result = try {
            lookup(workspace, configuration, declaration, indicator)
        } catch (failure: Unavailable) {
            failure.reason
        } catch (_: InvalidReflectionMetadata) {
            Result.Unavailable.CONFLICTING
        } catch (_: InvalidPathException) {
            Result.Unavailable.CONFLICTING
        } catch (_: IOException) {
            Result.Unavailable.CONFLICTING
        }
        indicator.checkCanceled()
        return if (ActiveCppConfiguration.isCurrent(project, configuration)) result else Result.Unavailable.BUILD_CHANGED
    }

    private fun lookup(
        workspace: Path,
        configuration: ActiveCppConfiguration,
        declaration: Declaration,
        indicator: ProgressIndicator,
    ): Result {
        val projectRoot = Root(workspace, Result.Unavailable.MISSING_SHADER)
        val buildRoot = Root(configuration.buildDirectory, Result.Unavailable.MISSING_PACK)
        val sourcePath = declaration.path.toAbsolutePath().normalize()
        if (!sourcePath.startsWith(projectRoot.path) || sourcePath == projectRoot.path) {
            return Result.Unavailable.MISSING_SHADER
        }
        val relative = projectRoot.path.relativize(sourcePath).joinToString("/")
        requireMetadata(isReflectionPath(relative))
        val identity = configuration.reflectionIdentity
        val pack = metadataRevision(buildRoot, identity)
        val key = MappingKey(pack, projectRoot.real, buildRoot.real, sourcePath, declaration.offset, declaration.name)
        val files = Validation(projectRoot, buildRoot, indicator)
        mappings[key]?.let { cached ->
            if (cached.sourceDocument?.get() === declaration.document && cached.sourceStamp == declaration.stamp &&
                declaration.isCurrent() && cached.revisions.all { files.checkRevision(it) } &&
                cached.result.documentsAreCurrent()) {
                return cached.result
            }
        }

        val shader = loadRecord(pack, identity, relative, indicator) ?: return Result.Unavailable.MISSING_SHADER
        val source = files.text(projectRoot, sourcePath, shader.hash)
        if (!runReadActionBlocking {
                val document = declaration.document
                declaration.isCurrent() && (document == null ||
                    (!FileDocumentManager.getInstance().isDocumentUnsaved(document) && document.matches(source.text)))
            }) {
            return Result.Unavailable.STALE
        }
        for (input in shader.inputs) {
            indicator.checkCanceled()
            files.validate(input)
        }
        for (binding in shader.bindings) {
            indicator.checkCanceled()
            source.checkIdentifier(binding.source)
        }
        val byteOffset = source.byteOffset(declaration.offset) ?: return Result.Unavailable.NOT_MAPPED
        val bindingIndex = shader.bindings.binarySearchBy(byteOffset.toLong()) { it.source.offset }
        if (bindingIndex < 0 || shader.bindings[bindingIndex].source.name != declaration.name) {
            return Result.Unavailable.NOT_MAPPED
        }

        val destinations = HashSet<Pair<Path, Long>>()
        val matches = shader.bindings[bindingIndex].destinations.map { destination ->
            indicator.checkCanceled()
            val (root, path) = files.resolve(destination.snapshot.file)
            val text = files.text(root, path, destination.snapshot.hash)
            requireMetadata(destinations.add(text.revision.realPath to destination.declaration.offset))
            text.checkIdentifier(destination.declaration)
            val offset = text.documentOffset(destination.declaration.offset.toInt())
            val location = files.location(path, text, offset)
            CppDeclaration(destination.declaration.name, location)
        }
        val revisions = listOf(pack) + files.revisions
        if (revisions.any { !files.checkRevision(it) }) {
            return Result.Unavailable.STALE
        }
        val fileStamps = runReadActionBlocking { files.documentFiles.associateWith { it.modificationStamp } }
        val result = Result.Mapped(matches) {
            runReadActionBlocking {
                val manager = FileDocumentManager.getInstance()
                fileStamps.all { (file, stamp) ->
                    file.isValid && file.modificationStamp == stamp &&
                        manager.getCachedDocument(file)?.let(manager::isDocumentUnsaved) != true
                } &&
                    matches.all {
                        val location = it.location
                        manager.getCachedDocument(location.file)?.let { doc -> doc.modificationStamp == location.stamp } != false
                    }
            }
        }
        if (!declaration.isCurrent() || !result.documentsAreCurrent()) {
            return Result.Unavailable.STALE
        }
        if (revisions.all(Revision::cacheable)) {
            mappings.put(key, ValidatedMapping(revisions, declaration.document?.let(::WeakReference), declaration.stamp, result))
        }
        return result
    }

    private fun metadataRevision(build: Root, identity: ReflectionBuildIdentity): Revision =
        build.revision(build.path.resolve(".jb-slanglsp/reflection/v1").resolve(identity.packName()), Result.Unavailable.MISSING_PACK)

    private fun loadRecord(
        pack: Revision,
        identity: ReflectionBuildIdentity,
        relative: String,
        indicator: ProgressIndicator,
    ): ReflectionPack.Shader? {
        val key = RecordKey(pack, relative)
        records[key]?.let { return it }
        val shader = try {
            val cachedIndex = indexes[pack]
            val record = cachedIndex?.find(relative)
            if (cachedIndex != null && record == null) {
                return null
            }
            FileChannel.open(pack.realPath).use { channel ->
                val index = cachedIndex ?: ReflectionPack.index(channel, identity, indicator).also {
                    if (!pack.isCurrent()) {
                        throw Unavailable(Result.Unavailable.STALE)
                    }
                    if (pack.cacheable) {
                        indexes.put(pack, it)
                    }
                }
                (record ?: index.find(relative))?.let { ReflectionPack.shader(channel, it, relative, indicator) }
            }
        } catch (failure: InvalidReflectionMetadata) {
            if (!pack.isCurrent()) {
                throw Unavailable(Result.Unavailable.STALE)
            }
            throw failure
        }
        if (!pack.isCurrent()) {
            throw Unavailable(Result.Unavailable.STALE)
        }
        if (shader != null && pack.cacheable) {
            records.put(key, shader)
        }
        return shader
    }

    private class Validation(
        private val project: Root,
        private val build: Root,
        private val indicator: ProgressIndicator,
    ) {
        private val vfs = LocalFileSystem.getInstance()
        private val checked = mutableMapOf<Path, CheckedFile>()
        val documentFiles = linkedSetOf<VirtualFile>()
        val revisions get() = checked.values.map { it.revision }
        private var bytesRemaining = MAX_VALIDATION_BYTES

        fun checkRevision(file: Revision): Boolean {
            indicator.checkCanceled()
            return file.isCurrent() && checkDocuments(file)
        }

        fun checkDocuments(file: Revision): Boolean {
            val virtual = listOfNotNull(vfs.findFileByNioFile(file.path), vfs.findFileByNioFile(file.realPath)).distinct()
            documentFiles.addAll(virtual)
            return runReadActionBlocking {
                val manager = FileDocumentManager.getInstance()
                virtual.none { manager.getCachedDocument(it)?.let(manager::isDocumentUnsaved) == true }
            }
        }

        fun resolve(file: ReflectionPack.FileKey): Pair<Root, Path> {
            val root = if (file.root == ReflectionPack.Root.PROJECT) project else build
            val path = root.path.resolve(file.path).normalize()
            requireMetadata(path.startsWith(root.path) && path != root.path)
            return root to path
        }

        fun validate(snapshot: ReflectionPack.Snapshot) {
            val (root, path) = resolve(snapshot.file)
            check(root, path, snapshot.hash, needsText = false)
        }

        fun text(root: Root, path: Path, hash: ByteArray): CheckedText =
            checkNotNull(check(root, path, hash, needsText = true).text)

        private fun check(root: Root, path: Path, hash: ByteArray, needsText: Boolean): CheckedFile {
            val prior = checked[path]
            if (prior != null) {
                requireMetadata(prior.revision.realPath.startsWith(root.real))
                requireMetadata(prior.hash.contentEquals(hash))
                if (!needsText || prior.text != null) {
                    return prior
                }
            }
            val revision = root.revision(path, Result.Unavailable.STALE)
            if (!checkDocuments(revision)) {
                throw Unavailable(Result.Unavailable.STALE)
            }
            requireMetadata(revision.size <= bytesRemaining && (!needsText || revision.size <= MAX_TEXT_BYTES))
            bytesRemaining -= revision.size
            val bytes = if (needsText) ByteArray(revision.size.toInt()) else null
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                Files.newInputStream(revision.realPath).use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    var offset = 0L
                    while (true) {
                        indicator.checkCanceled()
                        val length = stream.read(buffer)
                        if (length == -1) {
                            break
                        }
                        if (length > revision.size - offset) {
                            throw Unavailable(Result.Unavailable.STALE)
                        }
                        digest.update(buffer, 0, length)
                        bytes?.let { buffer.copyInto(it, offset.toInt(), 0, length) }
                        offset += length
                    }
                    if (offset != revision.size) {
                        throw Unavailable(Result.Unavailable.STALE)
                    }
                }
            } catch (_: IOException) {
                throw Unavailable(Result.Unavailable.STALE)
            }
            if (!digest.digest().contentEquals(hash) || !revision.isCurrent()) {
                throw Unavailable(Result.Unavailable.STALE)
            }
            return CheckedFile(revision, hash, bytes?.let { CheckedText(revision, it) }).also { checked[path] = it }
        }

        fun location(path: Path, text: CheckedText, offset: Int): CppLocation {
            val file = vfs.findFileByNioFile(path) ?: vfs.refreshAndFindFileByNioFile(path)
                ?: throw Unavailable(Result.Unavailable.STALE)
            documentFiles.add(file)
            fun locate(): CppLocation? = runReadActionBlocking {
                val manager = FileDocumentManager.getInstance()
                val document = manager.getDocument(file) ?: return@runReadActionBlocking null
                if (manager.isDocumentUnsaved(document)) {
                    throw Unavailable(Result.Unavailable.STALE)
                }
                if (!document.matches(text.text)) {
                    return@runReadActionBlocking null
                }
                CppLocation(file, document.modificationStamp, offset, document.getLineNumber(offset) + 1)
            }
            locate()?.let { return it }
            file.refresh(false, false)
            return locate() ?: throw Unavailable(Result.Unavailable.STALE)
        }
    }

    private class CheckedFile(val revision: Revision, val hash: ByteArray, val text: CheckedText?)

    private class CheckedText(val revision: Revision, private val bytes: ByteArray) {
        private val bom = if (bytes.size >= 3 && bytes[0] == 0xef.toByte() &&
            bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) 3 else 0
        val text = StringUtil.convertLineSeparators(decodeMetadataText(bytes, bom))

        fun checkIdentifier(declaration: ReflectionPack.Declaration) {
            requireMetadata(declaration.offset >= bom && declaration.end <= bytes.size)
            val start = declaration.offset.toInt()
            val end = declaration.end.toInt()
            requireMetadata((start == bom || !bytes[start - 1].isIdentifierByte()) &&
                (end == bytes.size || !bytes[end].isIdentifierByte()) &&
                declaration.bytes.indices.all { bytes[start + it] == declaration.bytes[it] })
        }

        fun byteOffset(offset: Int): Int? {
            val (byte, character) = walk { _, character -> character >= offset }
            return byte.takeIf { character == offset }
        }

        fun documentOffset(offset: Int): Int {
            val (byte, character) = walk { byte, _ -> byte >= offset }
            requireMetadata(byte == offset)
            return character
        }

        // The bytes have already been strictly decoded. Count JVM UTF-16 units and normalize line endings, as the IDE does.
        private inline fun walk(stop: (Int, Int) -> Boolean): Pair<Int, Int> {
            var byte = bom
            var character = 0
            while (byte < bytes.size && !stop(byte, character)) {
                val value = bytes[byte].toInt() and 0xff
                byte += when {
                    value == '\r'.code && bytes.getOrNull(byte + 1) == '\n'.code.toByte() -> 2
                    value < 0x80 -> 1
                    value < 0xe0 -> 2
                    value < 0xf0 -> 3
                    else -> 4
                }
                character += if (value >= 0xf0) 2 else 1
            }
            return byte to character
        }
    }

    private class Root(directory: Path, unavailable: Result.Unavailable) {
        val path: Path = directory.toAbsolutePath().normalize()
        val real: Path = try {
            path.toRealPath()
        } catch (_: NoSuchFileException) {
            throw Unavailable(unavailable)
        }

        fun revision(path: Path, missing: Result.Unavailable): Revision = try {
            Revision.read(path).also { requireMetadata(it.realPath.startsWith(real)) }
        } catch (_: NoSuchFileException) {
            throw Unavailable(missing)
        }
    }

    private data class Revision(
        val path: Path,
        val realPath: Path,
        val size: Long,
        val modified: FileTime,
        val created: FileTime,
        val fileKey: Any?,
        val changeTime: FileTime?,
    ) {
        val cacheable: Boolean get() = fileKey != null

        fun isCurrent(): Boolean = try {
            read(path) == this
        } catch (_: IOException) {
            false
        } catch (_: InvalidReflectionMetadata) {
            false
        }

        companion object {
            fun read(path: Path): Revision {
                val real = path.toRealPath()
                if ("unix" in real.fileSystem.supportedFileAttributeViews()) {
                    val info = Files.readAttributes(real, "unix:isRegularFile,size,lastModifiedTime,creationTime,fileKey,ctime")
                    requireMetadata(info["isRegularFile"] == true)
                    return Revision(
                        path = path,
                        realPath = real,
                        size = info.getValue("size") as Long,
                        modified = info.getValue("lastModifiedTime") as FileTime,
                        created = info.getValue("creationTime") as FileTime,
                        fileKey = info["fileKey"],
                        changeTime = info["ctime"] as FileTime?,
                    )
                }
                val info = Files.readAttributes(real, BasicFileAttributes::class.java)
                requireMetadata(info.isRegularFile)
                return Revision(path, real, info.size(), info.lastModifiedTime(), info.creationTime(), info.fileKey(), null)
            }
        }
    }

    private data class ValidatedMapping(
        val revisions: List<Revision>,
        val sourceDocument: WeakReference<Document>?,
        val sourceStamp: Long?,
        val result: Result.Mapped,
    )

    private data class RecordKey(val pack: Revision, val path: String)
    private data class MappingKey(
        val pack: Revision,
        val project: Path,
        val build: Path,
        val path: Path,
        val offset: Int,
        val name: String,
    )

    private class RevisionCache<K, V>(private val limit: Int) {
        private val values = LinkedHashMap<K, V>(limit, 0.75f, true)

        @Synchronized
        operator fun get(key: K): V? = values[key]

        @Synchronized
        fun put(key: K, value: V) {
            values[key] = value
            if (values.size > limit) {
                values.remove(values.keys.first())
            }
        }
    }

    private class Unavailable(val reason: Result.Unavailable) : Exception()

    private companion object {
        const val MAX_TEXT_BYTES = 64L * 1024 * 1024
        const val MAX_VALIDATION_BYTES = 512L * 1024 * 1024
    }
}

private fun Document.matches(text: String): Boolean = StringUtil.equals(immutableCharSequence, text)
