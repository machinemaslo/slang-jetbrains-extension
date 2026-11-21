package slanglsp.editor.rename

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

internal class ReflectionRenameRegistrationTest {
    @Test
    fun queuesOnlyAffectedGenerationTargetsAndCoalescesTheirInputs(@TempDir workspace: Path) {
        val registration = ReflectionRenameRegistration.parse(workspace, """
            {
              "version": 1,
              "tasks": [
                {
                  "system": "cmake",
                  "buildDirectory": "out/debug",
                  "configuration": "Debug",
                  "generationTarget": "jb_slanglsp_reflection_lighting",
                  "inputs": ["shaders/lighting.slang", "tools/bindings.hpp"]
                },
                {
                  "system": "cmake",
                  "buildDirectory": "out/debug",
                  "configuration": "Debug",
                  "generationTarget": "jb_slanglsp_reflection_water",
                  "inputs": ["shaders/water.slang"]
                }
              ]
            }
        """.trimIndent())
        val shader = workspace.resolve("shaders/lighting.slang")
        val namingInput = workspace.resolve("tools/bindings.hpp")
        val unrelated = workspace.resolve("src/renderer.cpp")

        assertTrue(registration.affected(listOf(unrelated)).isEmpty())
        val (task, inputs) = registration.affected(listOf(shader, unrelated, namingInput, shader)).entries.single()
        assertEquals(setOf(shader, namingInput), inputs)
        assertEquals(
            listOf("--build", workspace.resolve("out/debug").toString(), "--target", "jb_slanglsp_reflection_lighting", "--config", "Debug"),
            task.arguments(),
        )
    }

    @Test
    fun neverSubstitutesAHostOrDefaultTarget(@TempDir workspace: Path) {
        for (target in listOf("", "all", "clean", "Rebuild", "renderer", "--target", "jb_slanglsp_reflection_a;Build")) {
            assertThrows(IllegalArgumentException::class.java) {
                ReflectionRenameRegistration.parse(workspace, cmake(target))
            }
        }
    }

    @Test
    fun checksTheActualCMakeConfigurationBeforeExecuting(@TempDir workspace: Path) {
        val input = workspace.resolve("shaders/lighting.slang")
        val task = ReflectionRenameRegistration.parse(workspace, cmake("jb_slanglsp_reflection_lighting"))
            .affected(listOf(input)).keys.single()
        val build = Files.createDirectories(workspace.resolve("out/debug"))
        val cache = build.resolve("CMakeCache.txt")
        fun cacheWith(configuration: String) {
            Files.writeString(cache, "CMAKE_HOME_DIRECTORY:INTERNAL=$workspace\n$configuration\n")
        }

        cacheWith("CMAKE_BUILD_TYPE:STRING=Release")
        assertThrows(IOException::class.java) { task.verifyBuild(workspace.toRealPath()) }
        cacheWith("CMAKE_BUILD_TYPE:STRING=Debug")
        task.verifyBuild(workspace.toRealPath())
        cacheWith("CMAKE_CONFIGURATION_TYPES:STRING=Debug;Release")
        task.verifyBuild(workspace.toRealPath())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun addressesOneVcxprojTargetInsteadOfTheSolution(@TempDir workspace: Path) {
        val registration = ReflectionRenameRegistration.parse(workspace, """
            {
              "version": 1,
              "tasks": [{
                "system": "msbuild",
                "project": "host/Graphics.vcxproj",
                "configuration": "Checked",
                "platform": "x64",
                "generationTarget": "jb_slanglsp_reflection_graphics",
                "inputs": ["shaders/lighting.slang"]
              }]
            }
        """.trimIndent())
        val task = registration.affected(listOf(workspace.resolve("shaders/lighting.slang"))).keys.single()
        assertEquals(
            listOf(
                workspace.resolve("host/Graphics.vcxproj").toString(), "/nologo", "/noautoresponse",
                "/nodeReuse:false", "/verbosity:minimal", "/property:Configuration=Checked",
                "/property:Platform=x64", "/target:jb_slanglsp_reflection_graphics",
            ),
            task.arguments(),
        )
    }

    private fun cmake(target: String): String = """
        {
          "version": 1,
          "tasks": [{
            "system": "cmake",
            "buildDirectory": "out/debug",
            "configuration": "Debug",
            "generationTarget": "$target",
            "inputs": ["shaders/lighting.slang"]
          }]
        }
    """.trimIndent()
}
