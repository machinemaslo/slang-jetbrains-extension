package slanglsp.editor.navigation.cpp

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import slanglsp.TestDependencies
import slanglsp.editor.navigation.cpp.ReflectionMetadataLookup.Result
import slanglsp.editor.rename.CMakeReflectionProducer
import slanglsp.editor.rename.ReflectionRenameRegistration.Entry

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "SLANG_REFLECTION_CONTRACT_TESTS", matches = "1")
internal class PlaygroundTest {
    @Test
    fun resolvesTheTypeEmittedByEachTargetAndConfiguration(@TempDir temporary: Path) {
        val template = Path.of("dev/playground-clion").toRealPath()
        val workspace = Files.createDirectory(temporary.resolve("playground sources"))
        val sources = listOf("reflection", "reflection/release", "reflection/preview", "reflection/preview-release")
        val files = listOf("CMakeLists.txt", "reflection.slang", "reflection/GenerateBindings.cpp") +
            sources.flatMap { listOf("$it/main.cpp", "$it/material.cpp") }
        for (file in files) {
            val destination = workspace.resolve(file)
            Files.createDirectories(destination.parent)
            Files.copy(template.resolve(file), destination)
        }
        // The installer may have left an older helper beside the generator in a persistent sandbox.
        Files.writeString(workspace.resolve("reflection/${ReflectionHeaderRegistry.HEADER_NAME}"),
            "#error The configured helper directory must take precedence\n")
        val helper = Path.of("src/main/resources/reflection").toRealPath()
        val sdk = Path.of(TestDependencies.slangd()).parent.parent.parent
        val build = temporary.resolve("playground with spaces")
        val secondBuild = temporary.resolve("another profile")
        val log = temporary.resolve("cmake.log")
        fun configure(directory: Path, generator: String, configurations: String) {
            val query = Files.createDirectories(directory.resolve(".cmake/api/v1/query"))
            Files.createFile(query.resolve("codemodel-v2"))
            command(log, "cmake", "-G", generator, "-S", workspace.toString(), "-B", directory.toString(),
                configurations, "-DCMAKE_PREFIX_PATH=$sdk", "-DREFLECTION_HELPER_DIR=$helper")
        }
        configure(build, "Ninja Multi-Config", "-DCMAKE_CONFIGURATION_TYPES=Debug;Release;RelWithDebInfo")
        configure(secondBuild, "Ninja", "-DCMAKE_BUILD_TYPE=Debug")
        Files.writeString(workspace.resolve("CMakePresets.json"), """
            {
              "version": 3,
              "configurePresets": [
                {"name": "multi", "generator": "Ninja Multi-Config", "binaryDir": "$build"},
                {"name": "single", "generator": "Ninja", "binaryDir": "$secondBuild"}
              ]
            }
        """.trimIndent())

        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("Playground", temporary, false).fixture
        runInEdtAndWait { fixture.setUp() }
        try {
            val project = fixture.project
            val lookup = ReflectionMetadataLookup(project)
            val indicator = EmptyProgressIndicator()
            val shader = workspace.resolve("reflection.slang")
            val name = "PlaygroundMaterial"
            val text = Files.readString(shader)
            val declaration = ReflectionMetadataLookup.Declaration(shader, text.indexOf("struct $name") + "struct ".length, name)

            val types = mapOf("playground" to "MaterialConstants", "reflection_preview" to "PreviewConstants")
            for (configuration in listOf("Debug", "Release")) {
                command(log, "cmake", "--build", build.toString(), "--config", configuration, "--target", *types.keys.toTypedArray())
            }
            command(log, "cmake", "--build", secondBuild.toString(), "--target", "playground")
            for (configuration in listOf("Debug", "Release", "Debug")) {
                for ((target, type) in types) {
                    command(log, build.resolve("$configuration/$target").toString())
                    val active = ActiveCppConfiguration.snapshot(project, build, configuration, target)
                    val result = ProgressManager.getInstance().runProcess<Result>({
                        lookup.find(workspace, active, declaration, indicator)
                    }, indicator)
                    val mapped = assertInstanceOf(Result.Mapped::class.java, result)
                    val destination = mapped.matches.single()
                    assertEquals(if (configuration == "Release") "${type}Release" else type, destination.type)
                    val header = build.resolve("generated/$configuration/$target/bindings.hpp")
                    assertEquals(header, destination.location.file.toNioPath())
                    assertFalse(Files.readString(header).contains("#line"))
                }
            }

            val variants = listOf("Debug", "Release").flatMap { configuration ->
                types.keys.map { target -> ActiveCppConfiguration.snapshot(project, build, configuration, target) }
            } + ActiveCppConfiguration.snapshot(project, secondBuild, "Debug", "playground")
            val unbuilt = listOf(
                ActiveCppConfiguration.snapshot(project, build, "RelWithDebInfo", "playground"),
                ActiveCppConfiguration.snapshot(project, secondBuild, "Debug", "reflection_preview"),
            )
            val renamed = "RenamedPlaygroundMaterial"
            val renamedText = text.replace(name, renamed)
            Files.writeString(shader, renamedText)
            val renamedDeclaration = ReflectionMetadataLookup.Declaration(
                shader, renamedText.indexOf("struct $renamed") + "struct ".length, renamed,
            )
            fun lookupRenamed(variant: ActiveCppConfiguration): Result =
                ProgressManager.getInstance().runProcess<Result>({
                    lookup.find(workspace, variant, renamedDeclaration, indicator)
                }, indicator)

            assertTrue(variants.all { lookupRenamed(it) == Result.Unavailable.STALE })
            val hostTimes = variants.associate { variant ->
                val name = if (variant.buildDirectory == build) "${variant.buildType}/${variant.targetName}" else variant.targetName
                val host = variant.buildDirectory.resolve(name)
                host to Files.getLastModifiedTime(host)
            }
            val directories = CMakeReflectionProducer.buildDirectories(project, workspace)
            assertTrue(directories.containsAll(listOf(build, secondBuild)))
            val requests = CMakeReflectionProducer.plan(workspace, directories, setOf(shader), lookup, indicator)
            assertEquals(
                variants.mapTo(hashSetOf()) { Triple(it.buildDirectory, it.buildType, "${it.targetName}_bindings") },
                requests.mapTo(hashSetOf()) { Triple((it.task.entry as Entry.CMake).directory, it.task.configuration, it.task.target) },
            )
            assertEquals(variants.size, requests.size)
            val unrelated = workspace.resolve("reflection/GenerateBindings.cpp")
            assertTrue(CMakeReflectionProducer.plan(workspace, directories, setOf(unrelated), lookup, indicator).isEmpty())
            for (request in requests) {
                assertTrue(request.isCurrent())
                assertEquals(setOf(shader), request.affected)
                val task = request.task
                task.verifyBuild(workspace.toRealPath())
                command(log, checkNotNull(task.tool).toString(), *task.arguments().toTypedArray())
            }

            for (variant in variants) {
                val mapped = assertInstanceOf(Result.Mapped::class.java, lookupRenamed(variant))
                val type = types.getValue(variant.targetName)
                assertEquals(if (variant.buildType == "Release") "${type}Release" else type, mapped.matches.single().type)
            }
            assertTrue(unbuilt.all { lookupRenamed(it) == Result.Unavailable.MISSING_PACK })
            for ((host, time) in hostTimes) {
                assertEquals(time, Files.getLastModifiedTime(host), "The C++ executable should not have been rebuilt: $host")
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    private fun command(log: Path, vararg args: String) {
        val process = ProcessBuilder(*args).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            check(process.waitFor(60, TimeUnit.SECONDS)) { "Timed out: ${args.joinToString(" ")}" }
            assertEquals(0, process.exitValue(), Files.readString(log))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }
}
