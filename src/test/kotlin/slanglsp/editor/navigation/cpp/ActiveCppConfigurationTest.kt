package slanglsp.editor.navigation.cpp

import com.intellij.testFramework.LightVirtualFile
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ActiveCppConfigurationTest {
    @Test
    fun selectsOnlyTheActiveProfileWithoutGuessingDirectoryNames() {
        val root = Path.of("workspace").toAbsolutePath()
        val output = root.resolve("build/generated")
        val configuration = ActiveCppConfiguration(
            "active", root.resolve("out/cmake-abc123"),
            setOf(output.resolve("debug")), setOf(output.resolve("bench"), output.resolve("release"), output.resolve("test")),
        )
        val matches = listOf("bench", "debug", "release", "test").map {
            candidate(output.resolve(it).resolve("shader/shader_reflection.inc"))
        }

        assertEquals(listOf(matches[1]), configuration.select(matches))
        assertEquals(listOf(matches[1]), configuration.copy(
            // A broad shared build root must not take precedence over specific inactive output roots.
            activeRoots = configuration.activeRoots + listOf(root.resolve("build")),
        ).select(matches))
    }

    @Test
    fun readsIncludePathsAndDoesNotSelectADifferentProfilesOutput() {
        val root = Path.of("workspace").toAbsolutePath()
        val directory = root.resolve("cmake")
        val includes = ActiveCppConfiguration.includeDirectories(directory, listOf(
            "-Wall", "-I", "../generated/zebra", "-isystem../sdk", "/I\"../headers with spaces\"", "-DDEBUG",
        ))
        assertEquals(setOf(root.resolve("generated/zebra"), root.resolve("sdk"), root.resolve("headers with spaces")), includes)
        val configuration = ActiveCppConfiguration(
            "Zebra", directory, includes, setOf(root.resolve("generated/otter")),
        )

        assertEquals(emptyList<ReflectionSearch.Match>(), configuration.select(listOf(candidate(root.resolve("generated/otter/anything.inc")))))
        val ordinary = candidate(root.resolve("src/shared.hpp"))
        assertEquals(listOf(ordinary), configuration.select(listOf(ordinary)))
    }

    private fun candidate(path: Path): ReflectionSearch.Match {
        val file = object : LightVirtualFile(path.fileName.toString()) {
            override fun getPath(): String = path.toString()
        }
        return ReflectionSearch.Match("RenderPageCacheGpu", ReflectionSearch.Location(file, 0, 0, 1), false)
    }
}
