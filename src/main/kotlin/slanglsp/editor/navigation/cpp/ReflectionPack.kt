package slanglsp.editor.navigation.cpp

import com.intellij.openapi.progress.ProgressIndicator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Arrays
import java.util.HexFormat

internal data class ReflectionBuildIdentity(
    val system: String,
    val target: String,
    val configuration: String,
    val platform: String,
) {
    private val fields get() = listOf(system, target, configuration, platform)

    fun packName(): String = HexFormat.of().formatHex(reflectionDigest(fields.joinToString("\n").encodeToByteArray())) + ".pack"

    fun verify(reader: ReflectionPack.Reader) {
        for (expected in fields) {
            requireMetadata(reader.text(MAX_IDENTITY_FIELD) == expected)
        }
    }

    private companion object {
        const val MAX_IDENTITY_FIELD = 64 * 1024
    }
}

internal class InvalidReflectionMetadata : Exception()

internal fun requireMetadata(condition: Boolean) {
    if (!condition) {
        throw InvalidReflectionMetadata()
    }
}

internal fun reflectionDigest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun isReflectionPath(path: String): Boolean =
    path.isNotEmpty() && '\\' !in path && path.getOrNull(1) != ':' && path.none { it < ' ' || it == '\u007f' } &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }

/** Version 1 wire decoding, independent of the IDE's file system, build model and document offsets. */
internal object ReflectionPack {
    enum class Root { PROJECT, BUILD }

    data class FileKey(val root: Root, val path: String)
    class Snapshot(val file: FileKey, val hash: ByteArray)
    class Declaration(val offset: Long, val name: String, val bytes: ByteArray) {
        val end: Long get() = offset + bytes.size
    }
    data class Destination(val snapshot: Snapshot, val declaration: Declaration)
    data class Binding(val source: Declaration, val destinations: List<Destination>)
    class Shader(val hash: ByteArray, val inputs: List<Snapshot>, val bindings: List<Binding>)

    class Index(private val entries: ByteBuffer) {
        fun find(path: String): Record? {
            val key = reflectionDigest(path.encodeToByteArray())
            var low = 0
            var high = entries.limit() / ENTRY_SIZE - 1
            while (low <= high) {
                val middle = low + (high - low) / 2
                val at = middle * ENTRY_SIZE
                val order = entries.compareKey(at, key)
                when {
                    order < 0 -> low = middle + 1
                    order == 0 -> return Record(entries.getLong(at + DIGEST_SIZE), entries.getLong(at + DIGEST_SIZE + 8).toInt())
                    else -> high = middle - 1
                }
            }
            return null
        }
    }

    data class Record(val offset: Long, val size: Int)

    fun index(channel: FileChannel, identity: ReflectionBuildIdentity, indicator: ProgressIndicator): Index {
        val size = channel.size()
        val reader = Reader(channel, size = size)
        requireMetadata(reader.bytes(8).contentEquals(MAGIC))
        requireMetadata(reader.u32() == 1L)
        val count = reader.u32()
        val indexSize = count * ENTRY_SIZE
        identity.verify(reader)
        requireMetadata(indexSize <= MAX_INDEX_BYTES && indexSize <= size - reader.position)
        val entries = reader.buffer(indexSize.toInt())
        var expected = reader.position
        val key = ByteArray(DIGEST_SIZE)
        for (at in 0 until entries.limit() step ENTRY_SIZE) {
            indicator.checkCanceled()
            if (at != 0) {
                requireMetadata(entries.compareKey(at, key) > 0)
            }
            entries.get(at, key)
            val start = entries.getLong(at + DIGEST_SIZE)
            val length = entries.getLong(at + DIGEST_SIZE + 8)
            requireMetadata(start == expected && length in MIN_RECORD_BYTES..MAX_RECORD_BYTES && length <= size - start)
            expected += length
        }
        requireMetadata(expected == size)
        return Index(entries)
    }

    fun shader(channel: FileChannel, record: Record, requestedPath: String, indicator: ProgressIndicator): Shader {
        val data = Reader(Reader(channel, record.offset, channel.size()).buffer(record.size))
        requireMetadata(data.path() == requestedPath)
        val hash = data.bytes(DIGEST_SIZE)
        val inputCount = data.count(MIN_SNAPSHOT_BYTES)
        val inputKeys = HashSet<FileKey>()
        val inputs = List(inputCount) {
            indicator.checkCanceled()
            data.snapshot().also { requireMetadata(inputKeys.add(it.file)) }
        }
        var end = 0L
        val bindings = List(data.count(MIN_DECLARATION_BYTES + 4 + MIN_SNAPSHOT_BYTES + MIN_DECLARATION_BYTES)) {
            indicator.checkCanceled()
            val source = data.declaration()
            requireMetadata(source.offset >= end)
            end = source.end
            val destinations = data.count(MIN_SNAPSHOT_BYTES + MIN_DECLARATION_BYTES)
            requireMetadata(destinations > 0)
            val seen = HashSet<Pair<FileKey, Long>>()
            Binding(source, List(destinations) {
                indicator.checkCanceled()
                val snapshot = data.snapshot()
                val declaration = data.declaration()
                requireMetadata(seen.add(snapshot.file to declaration.offset))
                Destination(snapshot, declaration)
            })
        }
        requireMetadata(data.remaining == 0L)
        return Shader(hash, inputs, bindings)
    }

    class Reader private constructor(
        private val channel: FileChannel?,
        private val content: ByteBuffer?,
        start: Long,
        private val size: Long,
    ) {
        constructor(channel: FileChannel, start: Long = 0, size: Long) : this(channel, null, start, size)
        constructor(content: ByteBuffer) : this(null, content, 0, content.limit().toLong())

        var position = start
            private set
        val remaining get() = size - position

        fun buffer(length: Int): ByteBuffer {
            requireMetadata(length >= 0 && length.toLong() <= remaining)
            val offset = position
            position += length
            val result = if (content != null) {
                content.slice(offset.toInt(), length)
            } else {
                ByteBuffer.allocate(length).also { target ->
                    val file = checkNotNull(channel)
                    while (target.hasRemaining()) {
                        requireMetadata(file.read(target, offset + target.position()) > 0)
                    }
                    target.flip()
                }
            }
            return result.order(ByteOrder.LITTLE_ENDIAN)
        }

        fun bytes(length: Int): ByteArray = ByteArray(length).also { buffer(length).get(it) }

        fun u32(): Long {
            val data = content
            if (data == null) {
                return Integer.toUnsignedLong(buffer(4).int)
            }
            requireMetadata(remaining >= 4)
            val value = data.getInt(position.toInt())
            position += 4
            return Integer.toUnsignedLong(value)
        }

        fun text(max: Int = MAX_RECORD_BYTES.toInt()): String = decodeMetadataText(encodedText(max))

        fun path(): String = text().also { requireMetadata(isReflectionPath(it)) }

        fun count(minItemBytes: Int): Int {
            val count = u32()
            requireMetadata(count <= remaining / minItemBytes && count <= MAX_ITEMS_PER_RECORD)
            return count.toInt()
        }

        fun snapshot(): Snapshot {
            val root = when (buffer(1).get().toInt()) {
                0 -> Root.PROJECT
                1 -> Root.BUILD
                else -> throw InvalidReflectionMetadata()
            }
            return Snapshot(FileKey(root, path()), bytes(DIGEST_SIZE))
        }

        fun declaration(): Declaration {
            val offset = buffer(8).long
            val raw = encodedText(MAX_RECORD_BYTES.toInt())
            requireMetadata(offset >= 0 && offset <= Long.MAX_VALUE - raw.size && raw.isNotEmpty() &&
                raw.first().isIdentifierStart() && raw.all(Byte::isIdentifierByte))
            return Declaration(offset, decodeMetadataText(raw), raw)
        }

        private fun encodedText(max: Int): ByteArray {
            val length = u32()
            requireMetadata(length <= max && length <= remaining)
            return bytes(length.toInt())
        }
    }

    private fun ByteBuffer.compareKey(offset: Int, key: ByteArray): Int =
        Arrays.compareUnsigned(array(), offset, offset + DIGEST_SIZE, key, 0, DIGEST_SIZE)

    private val MAGIC = "JBSLRPK\u0000".encodeToByteArray()
    private const val DIGEST_SIZE = 32
    private const val ENTRY_SIZE = DIGEST_SIZE + 8 + 8
    private const val MIN_RECORD_BYTES = 4L + 1 + DIGEST_SIZE + 4 + 4
    private const val MIN_SNAPSHOT_BYTES = 1 + 4 + 1 + DIGEST_SIZE
    private const val MIN_DECLARATION_BYTES = 8 + 4 + 1
    private const val MAX_INDEX_BYTES = 32L * 1024 * 1024
    private const val MAX_RECORD_BYTES = 16L * 1024 * 1024
    private const val MAX_ITEMS_PER_RECORD = 131_072
}

internal fun decodeMetadataText(bytes: ByteArray, start: Int = 0): String =
    try {
        bytes.decodeToString(startIndex = start, throwOnInvalidSequence = true)
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw InvalidReflectionMetadata()
    }

private fun Byte.isIdentifierStart(): Boolean = this < 0 || toInt() in 'a'.code..'z'.code ||
    toInt() in 'A'.code..'Z'.code || toInt() == '_'.code

internal fun Byte.isIdentifierByte(): Boolean = isIdentifierStart() || toInt() in '0'.code..'9'.code
