package slanglsp.editor.navigation.cpp

import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import slanglsp.TestDependencies
import slanglsp.editor.navigation.cpp.ReflectionMetadataLookup.Result
import slanglsp.editor.navigation.cpp.ReflectionMetadataLookup.Result.Unavailable

/** The fixture emits v1 without using the bundled header or the IDE reader. */
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "SLANG_REFLECTION_CONTRACT_TESTS", matches = "1")
internal class ReflectionMetadataContractTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "SLANG_REFLECTION_BENCHMARK", matches = "1")
    fun benchmarkDirectLookup(@TempDir temporary: Path) = withWorkspace(temporary) {
        configure()
        build("Debug")
        benchmark()
    }

    @Test
    fun resolvesTheBuiltTypeForEachVariantOfTheSameShader(@TempDir temporary: Path) = withWorkspace(temporary) {
        configure()
        build("Debug")
        build("Release")

        val debug = read("Debug")
        val release = read("Release")
        val debugAgain = read("Debug")
        assertAll(
            { assertBinding("Debug", debug) },
            { assertBinding("Release", release) },
            { assertBinding("Debug", debugAgain) },
        )
    }

    @ParameterizedTest
    @EnumSource(InvalidMetadata::class)
    fun rejectsUnusableMetadataWithoutPickingALegacyDecoy(problem: InvalidMetadata, @TempDir temporary: Path) =
        withWorkspace(temporary) {
            configure()
            build("Debug")
            assertBinding("Debug", read("Debug"))
            when (problem) {
                InvalidMetadata.STALE_SHADER -> Files.writeString(shader, Files.readString(shader) + "\n// edited\n")
                InvalidMetadata.STALE_OUTPUT -> changeOutputWithoutChangingMtime()
                InvalidMetadata.STALE_INPUT -> Files.writeString(root.resolve("generator-input.txt"), "regenerated\n")
                InvalidMetadata.DUPLICATE_BINDING -> duplicateBinding("Debug")
                InvalidMetadata.WRONG_VARIANT -> replaceVariant("Debug", "Other")
                InvalidMetadata.CORRUPT_INDEX -> corruptIndex("Debug")
                InvalidMetadata.ESCAPED_OUTPUT -> escapeOutput("Debug")
            }

            assertEquals(problem.expected, read("Debug"), "$problem must not resolve to the legacy decoy")
        }

    @Test
    fun distinguishesMissingAndUnmappedWithoutSearchingOtherBuilds(@TempDir temporary: Path) = withWorkspace(temporary) {
        configure()
        build("Release")
        assertEquals(Unavailable.MISSING_PACK, read("Debug"))
        assertBinding("Release", read("Release"))
        assertEquals(Unavailable.MISSING_PACK, read("Release", config = configuration("Release").copy(targetName = "other")))
        assertEquals(Unavailable.NOT_MAPPED, read("Release", name = "Unmapped", offset = 0))
        Files.delete(packPath("Release"))
        assertEquals(Unavailable.MISSING_PACK, read("Release"), "A removed pack must invalidate a warm result")
    }

    @Test
    fun neverReturnsOnlyTheFreshDestinationsOfABinding(@TempDir temporary: Path) = withWorkspace(temporary) {
        configure()
        build("Debug")
        val alternate = addDestination("Debug")
        val result = read("Debug") as Result.Mapped
        assertEquals(listOf("DebugPayload", "AlternatePayload"), result.matches.map { it.type })
        assertSame(result, read("Debug"))

        Files.writeString(alternate, "// stale\n")
        assertEquals(Unavailable.STALE, read("Debug"))
    }

    @Test
    fun reusesAValidatedMappingAndRejectsUnsavedSourceAndOutput(@TempDir temporary: Path) = withWorkspace(temporary) {
        configure()
        build("Debug")
        val source = document(shader)
        val original = read("Debug", source = source)
        assertBinding("Debug", original)
        assertSame(original, read("Debug", source = source), "An unchanged revision should reuse validation")

        val output = document(output("Debug"))
        edit(output)
        assertEquals(Unavailable.STALE, read("Debug", source = source))
        runInEdtAndWait { FileDocumentManager.getInstance().reloadFromDisk(output) }
        assertBinding("Debug", read("Debug", source = source))

        edit(source)
        assertEquals(Unavailable.STALE, read("Debug", source = source))
        runInEdtAndWait { FileDocumentManager.getInstance().reloadFromDisk(source) }
    }

    @Test
    fun resolvesOnlyTheNewRelativeShaderAndBuildPathsAfterAMove(@TempDir temporary: Path) = withWorkspace(temporary) {
        configure()
        build("Debug")
        val originalBuild = buildDirectory
        val originalShader = shader
        val movedShader = root.resolve("moved shaders/material.slang")
        Files.createDirectories(movedShader.parent)
        Files.move(originalShader, movedShader)

        shaderPath = "moved shaders/material.slang"
        buildDirectory = originalBuild.resolveSibling("relocated build")
        configure()
        build("Debug")
        val moved = read("Debug")
        val resultInOldDirectory = read("Debug", configuration("Debug").copy(buildDirectory = originalBuild))
        assertAll(
            { assertBinding("Debug", moved) },
            { assertEquals(Unavailable.MISSING_SHADER, resultInOldDirectory, "The old pack does not index the renamed shader") },
        )
    }

    @Test
    fun discardsAResultWhenTheRunSelectionChangesDuringLookup(@TempDir temporary: Path) = withWorkspace(temporary) {
        configure()
        build("Debug")
        build("Release")
        val manager = RunManager.getInstance(project)
        val factory = VariantFixtureType().factory
        val debug = manager.createConfiguration("Debug fixture", factory)
        val release = manager.createConfiguration("Release fixture", factory)
        runInEdtAndWait {
            manager.addConfiguration(debug)
            manager.addConfiguration(release)
            manager.selectedConfiguration = debug
        }

        val snapshot = configuration("Debug")
        assertTrue(ActiveCppConfiguration.isCurrent(project, snapshot))
        var switched = false
        var checks = 0
        val delegate = EmptyProgressIndicator()
        // Only the cancellation checkpoint is used here; the obsolete Java defaults are unused no-ops.
        @Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
        val indicator = object : ProgressIndicator by delegate {
            override fun checkCanceled() {
                delegate.checkCanceled()
                if (++checks == 3) {
                    runInEdtAndWait { manager.selectedConfiguration = release }
                    switched = true
                }
            }
        }

        val result = read("Debug", snapshot, indicator)
        assertTrue(switched, "The lookup must check cancellation during its work")
        assertFalse(ActiveCppConfiguration.isCurrent(project, snapshot))
        assertEquals(Unavailable.BUILD_CHANGED, result)
    }

    enum class InvalidMetadata(val expected: Unavailable) {
        STALE_SHADER(Unavailable.STALE),
        STALE_OUTPUT(Unavailable.STALE),
        STALE_INPUT(Unavailable.STALE),
        DUPLICATE_BINDING(Unavailable.CONFLICTING),
        WRONG_VARIANT(Unavailable.CONFLICTING),
        CORRUPT_INDEX(Unavailable.CONFLICTING),
        ESCAPED_OUTPUT(Unavailable.CONFLICTING),
    }

    private fun withWorkspace(temporary: Path, test: Workspace.() -> Unit) {
        val root = Files.createDirectory(temporary.resolve("shader workspace")).toRealPath()
        val fixture = IdeaTestFixtureFactory.getFixtureFactory()
            .createFixtureBuilder("ReflectionMetadataContract", root, false).getFixture()
        runInEdtAndWait { fixture.setUp() }
        try {
            Workspace(fixture.project, root, temporary.resolve("build with spaces")).run(test)
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    private class Workspace(val project: Project, val root: Path, var buildDirectory: Path) {
        var shaderPath = "shaders/material.slang"
        val shader: Path get() = root.resolve(shaderPath)

        init {
            for (path in listOf(
                "CMakeLists.txt", "WriteFixtureMetadata.cmake", "WriteFixturePack.cpp", "main.cpp", "generator-input.txt", shaderPath,
            )) {
                val output = root.resolve(path)
                Files.createDirectories(output.parent)
                checkNotNull(javaClass.getResourceAsStream("/reflection-metadata/$path")).use {
                    Files.copy(it, output)
                }
            }
            Files.writeString(shader, "\uFEFF" + Files.readString(shader).replace("\n", "\r\n"))
        }

        fun configure() {
            val slangc = Path.of(TestDependencies.slangd()).resolveSibling("slangc")
            command(
                "cmake", "-G", "Ninja Multi-Config", "-S", root.toString(), "-B", buildDirectory.toString(),
                "-DSLANGC=$slangc", "-DFIXTURE_SHADER=$shaderPath", "-DCMAKE_CONFIGURATION_TYPES=Debug;Release",
            )
        }

        fun build(variant: String) {
            command("cmake", "--build", buildDirectory.toString(), "--config", variant, "--target", "host")
            command(buildDirectory.resolve("$variant/host").toString())
            assertTrue(Files.isRegularFile(packPath(variant)), "The build must produce a pack for $variant")
        }

        fun output(variant: String): Path = buildDirectory.resolve("generated/$variant/bindings.hpp")

        fun benchmark() {
            val active = configuration("Debug")
            val missing = configuration("Release")
            val name = "PlaygroundMaterial"
            val request = ReflectionMetadataLookup.Declaration(shader, editorText(shader).indexOf(name), name)
            val warm = ReflectionMetadataLookup(project)
            val indicator = EmptyProgressIndicator()
            ProgressManager.getInstance().runProcess({
                fun sample(label: String, config: ActiveCppConfiguration, lookup: () -> ReflectionMetadataLookup) {
                    val times = LongArray(200) {
                        val service = lookup()
                        val start = System.nanoTime()
                        val result = service.find(root, config, request, indicator)
                        val elapsed = System.nanoTime() - start
                        check(if (config == missing) result == Unavailable.MISSING_PACK else result is Result.Mapped)
                        elapsed
                    }.sorted()
                    println("reflection $label, n=${times.size}: p50=${times[99] / 1_000} µs; p95=${times[189] / 1_000} µs")
                }
                repeat(40) { warm.find(root, active, request, indicator) }
                sample("cold hit (new lookup caches, warm OS/VFS)", active) { ReflectionMetadataLookup(project) }
                sample("warm hit", active) { warm }
                sample("missing pack", missing) { warm }
            }, indicator)
        }

        fun configuration(variant: String): ActiveCppConfiguration =
            ActiveCppConfiguration.snapshot(project, buildDirectory, variant, "host")

        fun read(
            variant: String,
            config: ActiveCppConfiguration = configuration(variant),
            indicator: ProgressIndicator = EmptyProgressIndicator(),
            source: Document? = null,
            name: String = "PlaygroundMaterial",
            offset: Int = editorText(shader).indexOf(name),
        ): Result {
            val request = ReflectionMetadataLookup.Declaration(shader, offset, name, source)
            return ProgressManager.getInstance().runProcess<Result>({
                project.service<ReflectionMetadataLookup>().find(root, config, request, indicator)
            }, indicator)
        }

        fun assertBinding(variant: String, result: Result) {
            val name = "${variant}Payload"
            assertTrue(result is Result.Mapped, "Expected $name from the pack, got $result")
            val matches = (result as Result.Mapped).matches
            assertEquals(listOf(name), matches.map { it.type }, "$variant should use its v1 pack, not the legacy decoy")
            val match = matches.single()
            assertEquals(output(variant), match.location.file.toNioPath())
            assertEquals(editorText(output(variant)).indexOf(name), match.location.offset, "IDE offsets are not byte offsets")
        }

        fun document(path: Path): Document {
            val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            lateinit var document: Document
            runInEdtAndWait { document = checkNotNull(FileDocumentManager.getInstance().getDocument(file)) }
            return document
        }

        fun edit(document: Document) {
            runInEdtAndWait {
                WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "\n// unsaved\n") }
                assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
            }
        }

        fun changeOutputWithoutChangingMtime() {
            val output = output("Debug")
            val modified = Files.getLastModifiedTime(output)
            Files.writeString(output, Files.readString(output).replace("roughness", "Roughness"))
            Files.setLastModifiedTime(output, modified)
        }

        fun escapeOutput(variant: String) {
            val escaped = root.resolveSibling("outside.hpp")
            Files.move(output(variant), escaped)
            Files.createSymbolicLink(output(variant), escaped)
        }

        fun packPath(variant: String): Path {
            val key = digest("cmake\nhost\n$variant\n")
            return buildDirectory.resolve(".jb-slanglsp/reflection/v1/$key.pack")
        }

        fun duplicateBinding(variant: String) {
            val path = packPath(variant)
            val bytes = Files.readAllBytes(path)
            val pack = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val lengthField = pack.seekBindingCount()
            val countField = pack.position()
            assertEquals(1, pack.int, "The fixture has one binding to duplicate")
            val binding = bytes.copyOfRange(pack.position(), bytes.size)
            pack.putInt(countField, 2)
            pack.putLong(lengthField, pack.getLong(lengthField) + binding.size)
            Files.write(path, bytes + binding)
        }

        fun addDestination(variant: String): Path {
            val name = "AlternatePayload"
            val relative = "generated/$variant/alternate.hpp"
            val contents = "struct $name {};\n".encodeToByteArray()
            val output = Files.write(buildDirectory.resolve(relative), contents)
            val path = packPath(variant)
            val bytes = Files.readAllBytes(path)
            val pack = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val lengthField = pack.seekBindingCount()
            assertEquals(1, pack.int)
            pack.position(pack.position() + 8)
            pack.skipText()
            val countField = pack.position()
            assertEquals(1, pack.int)

            val destination = ByteBuffer.allocate(1 + 4 + relative.length + 32 + 8 + 4 + name.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(1).putInt(relative.length).put(relative.encodeToByteArray())
                .put(MessageDigest.getInstance("SHA-256").digest(contents))
                .putLong("struct ".length.toLong()).putInt(name.length).put(name.encodeToByteArray()).array()
            pack.putInt(countField, 2)
            pack.putLong(lengthField, pack.getLong(lengthField) + destination.size)
            Files.write(path, bytes + destination)
            return output
        }

        private fun ByteBuffer.seekBindingCount(): Int {
            val pack = this
            assertEquals(1, pack.getInt(12), "The fixture has one index entry")
            pack.position(16)
            repeat(4) { pack.skipText() }
            val offset = pack.getLong(pack.position() + 32).toInt()
            val lengthField = pack.position() + 40
            pack.position(offset)
            pack.skipText()
            pack.position(pack.position() + 32)
            assertEquals(1, pack.int, "The fixture has one additional input")
            assertEquals(0, pack.get().toInt(), "The fixture input is relative to the project")
            pack.skipText()
            pack.position(pack.position() + 32)
            return lengthField
        }

        fun replaceVariant(variant: String, replacement: String) {
            val path = packPath(variant)
            val bytes = Files.readAllBytes(path)
            val pack = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            pack.position(16)
            repeat(2) { pack.skipText() }
            val encoded = replacement.encodeToByteArray()
            assertEquals(encoded.size, pack.int, "Changing the fixture identity must not shift the index")
            pack.put(encoded)
            val replacementPath = Files.write(path.resolveSibling("replacement.pack"), bytes)
            Files.setLastModifiedTime(replacementPath, Files.getLastModifiedTime(path))
            Files.move(replacementPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        fun corruptIndex(variant: String) {
            val path = packPath(variant)
            val bytes = Files.readAllBytes(path)
            val pack = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            pack.position(16)
            repeat(4) { pack.skipText() }
            pack.putLong(pack.position() + 40, Long.MAX_VALUE)
            Files.write(path, bytes)
        }

        private fun ByteBuffer.skipText() {
            val length = int
            position(position() + length)
        }

        private fun command(vararg args: String) {
            val log = root.resolve("fixture.log")
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

        private fun digest(value: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()))

        private fun editorText(path: Path): String = Files.readString(path).removePrefix("\uFEFF").replace("\r\n", "\n")
    }

    private class VariantFixtureType : ConfigurationTypeBase(
        "SlangContractFixture", "Slang contract fixture", "Run selection for reflection contract tests",
        NotNullLazyValue.createValue { AllIcons.General.Information },
    ) {
        val factory = object : ConfigurationFactory(this) {
            override fun getId(): String = "SlangContractFixture"

            override fun createTemplateConfiguration(project: Project) =
                object : RunConfigurationBase<Any>(project, this, "Slang contract fixture") {
                    override fun getConfigurationEditor() = error("The fixture has no configuration editor")

                    override fun getState(executor: Executor, environment: ExecutionEnvironment) = null
                }
        }

        init {
            addFactory(factory)
        }
    }
}
