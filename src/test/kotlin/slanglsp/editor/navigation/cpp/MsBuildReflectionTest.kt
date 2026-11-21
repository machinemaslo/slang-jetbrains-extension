package slanglsp.editor.navigation.cpp

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import slanglsp.TestDependencies
import slanglsp.editor.navigation.cpp.ReflectionMetadataLookup.Result
import slanglsp.editor.rename.MsBuildReflectionProducer

@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "SLANG_MSBUILD_FIXTURE_TESTS", matches = "1")
internal class MsBuildReflectionTest {
    @Test
    fun buildsTheProjectConfigurationsSelectedByTheSolution(@TempDir temporary: Path) {
        val source = Path.of("dev/playground-msbuild").toRealPath()
        val root = Files.createDirectory(temporary.resolve("solution with spaces")).toRealPath()
        val files = listOf(
            "Reflection.sln", "reflection.slang", "PublishAssociation.ps1",
            "host/ReflectionHost.vcxproj", "tool/ReflectionTool.vcxproj",
        ) + listOf("Checked", "Optimized").flatMap { listOf("host/$it/main.cpp", "host/$it/material.cpp") }
        for (file in files) {
            val destination = root.resolve(file)
            Files.createDirectories(destination.parent)
            Files.copy(source.resolve(file), destination)
        }
        val sdk = Path.of(TestDependencies.slangd()).parent.parent.parent
        val shared = Path.of("dev/playground-clion/reflection").toRealPath()
        val helper = Path.of("src/main/resources/reflection").toRealPath()
        val environment = mapOf("SlangSdk" to sdk.toString(), "FixtureSources" to shared.toString(), "ReflectionHelper" to helper.toString())
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("MsBuildReflection", root, false).fixture
        runInEdtAndWait { fixture.setUp() }
        try {
            val project = fixture.project
            val indicator = EmptyProgressIndicator()
            val choices = MsBuildReflectionConfiguration.choices(root, indicator)
            assertEquals(listOf("Editor|Mixed Platforms", "Shipping|Mixed Platforms"), choices.flatMap { it.solutionConfigurations })
            assertEquals(listOf("Checked", "Optimized"), choices.map { it.identity.configuration })
            assertTrue(choices.all { it.identity.platform == "x64" && it.identity.target == "host/ReflectionHost.vcxproj" })

            val lookup = ReflectionMetadataLookup(project)
            val shader = root.resolve("reflection.slang")
            val name = "PlaygroundMaterial"
            val declaration = ReflectionMetadataLookup.Declaration(shader, Files.readString(shader).indexOf(name), name)
            fun plan(changed: Path = shader) = MsBuildReflectionProducer.plan(project, root, setOf(changed), lookup, indicator)
            assertTrue(plan().isEmpty(), "An unbuilt solution must not queue regeneration")
            for ((index, choice) in choices.withIndex()) {
                val solutionConfiguration = choice.solutionConfigurations.single().substringBefore('|')
                if (index == 0) {
                    assertEquals(
                        MsBuildReflectionConfiguration.Association.Rejected(Result.Unavailable.MISSING_PACK),
                        MsBuildReflectionConfiguration.resolve(project, root, choices[1]),
                    )
                }
                command(temporary.resolve("msbuild-$solutionConfiguration.log"), "MSBuild.exe",
                    root.resolve("Reflection.sln").toString(), "/nologo", "/m:1", "/t:Build",
                    "/p:Configuration=$solutionConfiguration", "/p:Platform=Mixed Platforms", environment = environment)
                val config = choice.identity.configuration
                val intermediate = root.resolve("host/artifacts/intermediate/$config/x64")
                val association = MsBuildReflectionConfiguration.resolve(project, root, choice)
                assertTrue(association is MsBuildReflectionConfiguration.Association.Found, "Could not resolve $choice: $association")
                val active = (association as MsBuildReflectionConfiguration.Association.Found).configuration
                assertEquals(intermediate, active.buildDirectory)
                assertEquals(choice.identity, active.reflectionIdentity)
                assertEquals("jb_slanglsp_reflection_bindings", association.producer?.target)
                val pack = intermediate.resolve(".jb-slanglsp/reflection/v1/${choice.identity.packName()}")
                assertTrue(Files.isRegularFile(pack), "Pack should be under the evaluated project IntDir")
                assertEquals(choices.take(index + 1), plan().map { it.choice }, "Only published variants may regenerate")
                command(temporary.resolve("host-$solutionConfiguration.log"),
                    root.resolve("host/artifacts/bin/$config/x64/ReflectionHost.exe").toString())

                val mapped = ProgressManager.getInstance().runProcess<Result>({
                    lookup.find(root, active, declaration, indicator)
                }, indicator)
                assertTrue(mapped is Result.Mapped, "Expected metadata from the mapped project configuration, got $mapped")
                val match = (mapped as Result.Mapped).matches.single()
                assertEquals("${config}Material", match.type)
                assertEquals(intermediate.resolve("generated/bindings.hpp"), match.location.file.toNioPath())
                assertFalse(Files.readString(match.location.file.toNioPath()).contains("#line"))
            }
            assertEquals(choices, MsBuildReflectionConfiguration.choices(root, indicator), "Publishing must not choose the last build")

            val original = Files.readString(shader)
            val renamed = "RenamedPlaygroundMaterial"
            val renamedText = original.replace(name, renamed)
            Files.writeString(shader, renamedText)
            val renamedDeclaration = ReflectionMetadataLookup.Declaration(shader, renamedText.indexOf(renamed), renamed)
            val requests = plan()
            assertEquals(choices, requests.map { it.choice })
            val variants = choices.associateWith { choice ->
                assertInstanceOf(MsBuildReflectionConfiguration.Association.Found::class.java,
                    MsBuildReflectionConfiguration.resolve(project, root, choice)).configuration
            }
            fun lookupRenamed(configuration: ActiveCppConfiguration): Result =
                ProgressManager.getInstance().runProcess<Result>({
                    lookup.find(root, configuration, renamedDeclaration, indicator)
                }, indicator)
            assertTrue(variants.values.all { lookupRenamed(it) == Result.Unavailable.STALE })
            for ((index, request) in requests.withIndex()) {
                val choice = request.choice
                val configuration = choice.identity.configuration
                val host = root.resolve("host/artifacts/bin/$configuration/x64/ReflectionHost.exe")
                assertTrue(plan(host).isEmpty(), "Unrelated changes should not queue regeneration")
                assertTrue(request.isCurrent(project, root))
                assertEquals(setOf(shader), request.affected)
                assertEquals(setOf(shader, root.resolve(choice.solution), root.resolve(choice.identity.target)), request.task.inputs)
                val hostTime = Files.getLastModifiedTime(host)
                val task = request.task
                val engine = checkNotNull(task.tool).toString()
                assertEquals(root.resolve("host/ReflectionHost.vcxproj").toString(), task.arguments().first())
                assertEquals(listOf("/target:jb_slanglsp_reflection_bindings"), task.arguments().filter { it.startsWith("/target:") })
                assertTrue(task.evaluationArguments().none { it.startsWith("/target:") })
                val evaluation = command(temporary.resolve("evaluate-$configuration.log"), engine,
                    *task.evaluationArguments().toTypedArray(), environment = environment)
                request.verifyEvaluation(evaluation)
                if (index == 0) {
                    val differentOutput = command(temporary.resolve("mismatched-intdir.log"), engine,
                        *task.evaluationArguments().toTypedArray(), "/property:IntDir=artifacts/other", environment = environment)
                    assertThrows(IOException::class.java) { request.verifyEvaluation(differentOutput) }
                }
                command(temporary.resolve("regenerate-$configuration.log"), engine, *task.arguments().toTypedArray(),
                    environment = environment)
                val mapped = assertInstanceOf(Result.Mapped::class.java, lookupRenamed(variants.getValue(choice)))
                assertEquals("${configuration}Material", mapped.matches.single().type)
                assertEquals(hostTime, Files.getLastModifiedTime(host), "Rename must not rebuild the C++ host")
            }
            assertTrue(variants.values.all { lookupRenamed(it) is Result.Mapped })

            val solution = root.resolve(choices.first().solution)
            Files.writeString(solution, Files.readString(solution) + "\n")
            assertEquals(
                MsBuildReflectionConfiguration.Association.Rejected(Result.Unavailable.STALE),
                MsBuildReflectionConfiguration.resolve(project, root, choices.first()),
            )
            assertTrue(plan().isEmpty(), "Stale associations should not queue regeneration")
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    private fun command(log: Path, vararg args: String, environment: Map<String, String> = emptyMap()): String {
        val builder = ProcessBuilder(*args).redirectErrorStream(true).redirectOutput(log.toFile())
        builder.environment().putAll(environment)
        val process = builder.start()
        try {
            check(process.waitFor(180, TimeUnit.SECONDS)) { "Timed out: ${args.joinToString(" ")}" }
            val output = Files.readString(log)
            assertEquals(0, process.exitValue(), output)
            return output
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }
}
