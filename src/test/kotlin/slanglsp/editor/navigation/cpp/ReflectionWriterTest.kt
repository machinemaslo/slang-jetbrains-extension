package slanglsp.editor.navigation.cpp

import com.intellij.openapi.progress.EmptyProgressIndicator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Arrays
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@EnabledOnOs(OS.LINUX) // Runs a native generator and creates a filesystem symlink.
internal class ReflectionWriterTest {
    @ParameterizedTest
    @ValueSource(strings = ["c++17", "c++20"])
    fun emitsIndexedVariantPacksWithoutRewritingGeneratorOutputs(standard: String, @TempDir root: Path) {
        val workspace = Files.createDirectory(root.resolve("shader project"))
        val build = Files.createDirectory(root.resolve("separate build directory"))
        val header = ReflectionHeaderRegistry.HEADER_NAME
        copyResource("/reflection/$header", root.resolve(header))
        val generator = root.resolve("generator.cpp")
        copyResource("/reflection-writer/generator.cpp", generator)
        val executable = root.resolve("generator")
        command(
            root.resolve("compile.log"), "c++", "-std=$standard", "-Wall", "-Wextra", "-Werror", "-pedantic",
            generator.toString(), "-o", executable.toString(),
        )
        command(root.resolve("run.log"), executable.toString(), workspace.toString(), build.toString())

        val debug = packPath(build, "Debug")
        val release = packPath(build, "Release")
        val paths = Files.list(debug.parent).use { files -> files.toList().toSet() }
        assertEquals(setOf(debug, release), paths, "Exactly one file per variant and no temporary files")
        val pack = Index(debug, "Debug")
        assertEquals(2, pack.count)
        assertEquals(0, Index(release, "Release").count)
        assertNull(pack.record("retired.slang"), "Full replacement must remove omitted shaders")

        val unusedPath = "café no output.slang"
        val noOutput = checkNotNull(pack.record(unusedPath))
        assertEquals(unusedPath, noOutput.text())
        assertArrayEquals(sha256(Files.readAllBytes(workspace.resolve(unusedPath))), noOutput.bytes(32))
        assertEquals(0, noOutput.int, "No additional inputs")
        assertEquals(0, noOutput.int, "No bindings")
        assertEquals(0, noOutput.remaining())

        val material = checkNotNull(pack.record("material.slang"))
        assertEquals("material.slang", material.text())
        val sourceBytes = Files.readAllBytes(workspace.resolve("material.slang"))
        assertArrayEquals(sha256(sourceBytes), material.bytes(32))
        assertEquals(1, material.int, "One additional input")
        assertEquals(0, material.get().toInt(), "Project root")
        assertEquals("build receipt.bin", material.text())
        assertArrayEquals(sha256(ByteArray(130)), material.bytes(32))

        assertEquals(1, material.int, "One binding")
        assertIdentifier(sourceBytes, material, "Paint")
        assertEquals(2, material.int, "Two destinations")
        val cppBytes = Files.readAllBytes(build.resolve("bindings.hpp"))
        for (name in listOf("HostPaint", "HostPaintView")) {
            assertEquals(1, material.get().toInt(), "Build root")
            assertEquals("bindings.hpp", material.text())
            assertArrayEquals(sha256(cppBytes), material.bytes(32))
            assertIdentifier(cppBytes, material, name)
        }
        assertEquals(0, material.remaining())
        assertTrue("\r\n" in cppBytes.decodeToString(), "The writer must not normalize the generated file")

        FileChannel.open(debug).use { channel ->
            val indicator = EmptyProgressIndicator()
            val identity = ReflectionBuildIdentity("cmake", "render target", "Debug", "")
            val index = ReflectionPack.index(channel, identity, indicator)
            assertNull(index.find("retired.slang"))
            val unused = ReflectionPack.shader(channel, checkNotNull(index.find(unusedPath)), unusedPath, indicator)
            assertTrue(unused.bindings.isEmpty())
            val path = "material.slang"
            val mapped = ReflectionPack.shader(channel, checkNotNull(index.find(path)), path, indicator)
            assertEquals(listOf("HostPaint", "HostPaintView"), mapped.bindings.single().destinations.map { it.declaration.name })
        }
    }

    private fun assertIdentifier(bytes: ByteArray, declaration: ByteBuffer, expected: String) {
        val start = Math.toIntExact(declaration.long)
        val name = declaration.bytes(declaration.int)
        assertEquals(expected, name.decodeToString())
        assertArrayEquals(name, bytes.copyOfRange(start, start + name.size))
        val content = bytes.decodeToString()
        assertEquals(content.take(content.indexOf(expected)).encodeToByteArray().size, start)
    }

    private class Index(path: Path, configuration: String) {
        private val pack = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN)
        val count: Int
        private val keys: List<ByteArray>
        private val ranges: List<Pair<Int, Int>>

        init {
            assertArrayEquals("JBSLRPK\u0000".encodeToByteArray(), pack.bytes(8))
            assertEquals(1, pack.int)
            count = pack.int
            assertEquals(listOf("cmake", "render target", configuration, ""), List(4) { pack.text() })
            val firstRecord = pack.position() + count * 48
            val indexKeys = ArrayList<ByteArray>(count)
            val indexRanges = ArrayList<Pair<Int, Int>>(count)
            fun expectedEnd(): Int = indexRanges.lastOrNull()?.let { (start, size) -> start + size } ?: firstRecord
            while (indexRanges.size < count) {
                val key = pack.bytes(32)
                val previous = indexKeys.lastOrNull()
                assertTrue(previous == null || Arrays.compareUnsigned(previous, key) < 0, "Index keys must be sorted")
                indexKeys += key
                val start = Math.toIntExact(pack.long)
                val size = Math.toIntExact(pack.long)
                assertEquals(expectedEnd(), start, "Index entries must be contiguous and follow the index")
                indexRanges.add(start to size)
            }
            keys = indexKeys
            ranges = indexRanges
            assertEquals(pack.limit(), expectedEnd(), "No trailing bytes outside records")
        }

        fun record(sourcePath: String): ByteBuffer? {
            val key = sha256(sourcePath.encodeToByteArray())
            val index = keys.binarySearch { Arrays.compareUnsigned(it, key) }
            if (index < 0) {
                return null
            }
            val (offset, length) = ranges[index]
            return pack.slice(offset, length).order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    private fun packPath(build: Path, configuration: String): Path {
        val key = sha256("cmake\nrender target\n$configuration\n".encodeToByteArray())
        return build.resolve(".jb-slanglsp/reflection/v1/${HexFormat.of().formatHex(key)}.pack")
    }

    private fun copyResource(name: String, target: Path) {
        checkNotNull(javaClass.getResourceAsStream(name)).use {
            Files.copy(it, target)
        }
    }

    private fun command(log: Path, vararg args: String) {
        val process = ProcessBuilder(*args).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Timed out: ${args.joinToString(" ")}" }
            assertEquals(0, process.exitValue(), Files.readString(log))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }
}

private fun ByteBuffer.bytes(size: Int): ByteArray = ByteArray(size).also { get(it) }

private fun ByteBuffer.text(): String = bytes(int).decodeToString(throwOnInvalidSequence = true)

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
