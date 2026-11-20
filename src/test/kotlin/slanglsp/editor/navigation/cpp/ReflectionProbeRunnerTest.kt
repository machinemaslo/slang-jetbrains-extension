package slanglsp.editor.navigation.cpp

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import slanglsp.TestDependencies

internal class ReflectionProbeRunnerTest {
    @Test
    fun parsesDirectNinjaGraphOutputs() {
        val output = """
            ../shader.slang:
              input: phony
                original.slang
              outputs:
                arbitrary name.inc
                stamp
        """.trimIndent()
        assertEquals(
            ReflectionProbeRunner.Query("phony", listOf("arbitrary name.inc", "stamp")),
            ReflectionProbeRunner.parseQuery(output),
        )
    }

    @Test
    fun tracesAndCachesTheBuiltNativeGeneratorOnTheHostPlatform(@TempDir directory: Path) {
        val project = Files.createDirectory(directory.resolve("project with spaces")).toRealPath()
        val build = Files.createDirectory(project.resolve("build"))
        val fixtureBuild = project.resolve("compiled tool")
        val sdk = Path.of(TestDependencies.slangd()).toAbsolutePath().parent.parent.parent
        for (resource in listOf("CMakeLists.txt", "reflection-generator.cc")) {
            checkNotNull(javaClass.getResourceAsStream(resource)).use { Files.copy(it, project.resolve(resource)) }
        }
        // Only fixture setup compiles a generator. No build command is available in the replay's graph.
        runTool(project, "cmake", "-S", project.toString(), "-B", fixtureBuild.toString(), "-DSLANG_SDK=$sdk")
        runTool(project, "cmake", "--build", fixtureBuild.toString(), "--config", "Release", "--target", "reflect")
        val program = fixtureBuild.resolve("bin").resolve(if (File.separatorChar == '\\') "reflect.exe" else "reflect")
        val sourceText = "public struct LightingParams { public float3 SunDirection; public float Power; };\n"
        val shader = Files.writeString(project.resolve("shader.slang"), sourceText)
        val artifact = build.resolve("arbitrary output.blob")
        runTool(build, program.toString(), shader.toString(), artifact.toString())
        val original = Files.readString(artifact)
        val invocation = "\"$program\" \"../shader.slang\" \"arbitrary output.blob\""
        val manifest = Files.writeString(build.resolve("build.ninja"), """
            rule CUSTOM_COMMAND
              command = $invocation
            build arbitrary${'$'} output.blob: CUSTOM_COMMAND ../shader.slang
        """.trimIndent() + "\n")

        val runner = ReflectionProbeRunner()
        val input = ReflectionProbeRunner.Source(project, shader, sourceText.indexOf("LightingParams"), sourceText)
        val target = runner.targets(build, shader).single()
        val match = runner.probe(build, input, target)!!

        assertEquals("T42", match.declaration.name)
        assertEquals(artifact, match.declaration.file)
        assertEquals(original.indexOf("T42"), match.declaration.offset)
        assertEquals(original, Files.readString(artifact))
        assertEquals(sourceText, Files.readString(shader))
        assertEquals(match, runner.cached(shader, input.offset, sourceText))
        assertNull(runner.cached(shader, input.offset, sourceText + " "))

        // A structured compilation database uses argv, so spaces and platform separators do not need shell parsing.
        val database = Files.writeString(project.resolve("compile_commands.json"), Gson().toJson(listOf(
            mapOf("directory" to build.toString(), "file" to shader.toString(), "output" to artifact.toString(),
                "arguments" to listOf(program.toString(), shader.toString(), artifact.toString())),
        )))
        val entry = ReflectionInvocationDiscovery.databaseTargets(database, shader).single()
        assertEquals(match, ReflectionProbeRunner().probe(build, input, entry))

        // Stale live output cannot be searched at the offsets of a scratch probe.
        Files.writeString(artifact, "// outdated\n$original")
        assertNull(runner.cached(shader, input.offset, sourceText))
        assertThrows(ReflectionProbeRunner.Failure::class.java) { runner.probe(build, input, target) }
        assertEquals("// outdated\n$original", Files.readString(artifact))

        assertNull(ReflectionGeneratorReplay.bind(build, shader, target.copy(command = "$invocation && echo done")))
        assertNull(ReflectionGeneratorReplay.bind(build, shader, target.copy(command = "\"$program\" \"../shader.slang\"")))
        assertEquals(listOf(manifest), runner.discoverBuilds(build))
    }

    private fun runTool(directory: Path, vararg command: String) {
        val log = Files.createTempFile(directory, "probe-fixture-", ".log")
        val process = ProcessBuilder(*command).directory(directory.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            check(process.waitFor(60, TimeUnit.SECONDS)) { "Fixture tool timed out: ${command.first()}" }
            assertEquals(0, process.exitValue(), Files.readString(log))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
