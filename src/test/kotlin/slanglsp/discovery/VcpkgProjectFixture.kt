package slanglsp.discovery

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions
import slanglsp.TestDependencies

/** Builds project metadata and package trees shared by the discovery bundle. */
internal class VcpkgProjectFixture(private val temporary: Path) {
    internal enum class BuildSystem {
        CMAKE,
        MSBUILD,
        XMAKE,
        MESON
    }

    @Throws(Exception::class)
    fun createTool(installed: Path, triplet: String, executable: String): Path {
        write(
            installed.resolve("vcpkg/status"),
            """
                Package: shader-slang
                Architecture: $triplet
                Status: install ok installed
                
                """
                .trimIndent() + "\n")

        val tool =
            write(installed.resolve("$triplet/tools/shader-slang/$executable"), "discovery fixture; never executed\n")
        Assertions.assertTrue(tool.toFile().setExecutable(true))

        return tool
    }

    @Throws(Exception::class)
    fun createProject(system: BuildSystem, installed: Path, triplet: String): Path {
        val root = temporary.resolve(system.name.lowercase() + " project")
        writeJson(
            root.resolve("vcpkg.json"),
            mapOf(
                "name" to "discovery-bundle",
                "version-string" to "0.1.0",
                "builtin-baseline" to TestDependencies.baseline(),
                "dependencies" to listOf("shader-slang"),
            ))
        write(root.resolve("main.cpp"), "int main() { return 0; }\n")

        when (system) {
            BuildSystem.CMAKE -> createCmake(root, installed, triplet)
            BuildSystem.MSBUILD -> createMsbuild(root, installed, triplet)
            BuildSystem.XMAKE -> createXmake(root, installed)
            BuildSystem.MESON -> createMeson(root, installed, triplet)
        }

        return root
    }

    @Throws(Exception::class)
    private fun createCmake(root: Path, installed: Path, triplet: String) {
        write(
            root.resolve("CMakeLists.txt"),
            """
                cmake_minimum_required(VERSION 3.25)
                project(discovery_bundle LANGUAGES NONE)
                
                """
                .trimIndent())

        val variables = mapOf("VCPKG_INSTALLED_DIR" to portablePath(installed), "VCPKG_HOST_TRIPLET" to triplet)
        val preset =
            mapOf(
                "name" to "bundle",
                "generator" to "Ninja",
                "binaryDir" to $$"${sourceDir}/build",
                "cacheVariables" to variables,
            )

        writeJson(root.resolve("CMakePresets.json"), mapOf("version" to 3, "configurePresets" to listOf(preset)))
    }

    @Throws(Exception::class)
    private fun createMsbuild(root: Path, installed: Path, triplet: String?) {
        write(
            root.resolve("bundle.slnx"),
            """
                <Solution>
                    <Project Path="src/bundle.vcxproj"/>
                </Solution>
                
                """
                .trimIndent())
        write(
            root.resolve("src/bundle.vcxproj"),
            """
                <Project>
                    <Import Project="../dependencies.props"/>
                </Project>
                
                """
                .trimIndent())
        write(
            root.resolve("dependencies.props"),
            """
                <Project>
                    <PropertyGroup>
                        <VcpkgManifestInstalledBaseDir>${escapeXml(portablePath(installed))}</VcpkgManifestInstalledBaseDir>
                        <VcpkgHostTriplet>$triplet</VcpkgHostTriplet>
                    </PropertyGroup>
                </Project>
                
                """
                .trimIndent() + "\n")
    }

    @Throws(Exception::class)
    private fun createXmake(root: Path, installed: Path) {
        write(
            root.resolve("xmake.lua"),
            """
                add_rules("mode.debug", "mode.release")
                add_requires("vcpkg::shader-slang")

                target("bundle")
                    set_kind("binary")
                    add_files("main.cpp")
                
                """
                .trimIndent())

        // Saved configuration fixture, not the output of an invocation of Xmake
        val vcpkg: String? = JSON.toJson(portablePath(installed.parent))
        write(
            root.resolve(".xmake/linux/x86_64/xmake.conf"),
            """
                {
                    vcpkg = $vcpkg,
                }
                
                """
                .trimIndent() + "\n")
    }

    @Throws(Exception::class)
    private fun createMeson(root: Path, installed: Path, triplet: String?) {
        write(
            root.resolve("meson.build"),
            """
                project('discovery-bundle', 'cpp')
                slang = dependency('slang', method: 'pkg-config')
                
                """
                .trimIndent())

        // Saved introspection fixture, not the output of a Meson invocation.
        val include = portablePath(installed) + "/" + triplet + "/include"
        val dependency = mapOf("name" to "slang", "compile_args" to listOf("-I", include))

        writeJson(root.resolve("builddir/meson-info/intro-dependencies.json"), listOf(dependency))
    }

    @Throws(Exception::class)
    fun run(root: Path, vararg command: String?) {
        val log = Files.createTempFile(temporary, "setup-", ".log")
        val process =
            ProcessBuilder(*command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()

        try {
            Assertions.assertTrue(
                process.waitFor(10, TimeUnit.MINUTES), "Setup timed out: " + command.contentToString())
            Assertions.assertEquals(0, process.exitValue()) { commandOutput(command, log) }
        } finally {
            process.descendants().forEach { obj: ProcessHandle? -> obj!!.destroy() }

            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private fun commandOutput(command: Array<out String?>, log: Path): String {
        return try {
            command.contentToString() + "\n" + Files.readString(log)
        } catch (failure: Exception) {
            failure.toString()
        }
    }

    @Throws(Exception::class)
    private fun write(file: Path, text: String): Path {
        Files.createDirectories(file.parent)

        return Files.writeString(file, text)
    }

    @Throws(Exception::class)
    private fun writeJson(file: Path, value: Any) {
        write(file, JSON.toJson(value))
    }

    companion object {
        private val JSON: Gson = GsonBuilder().setPrettyPrinting().create()

        private fun portablePath(path: Path): String {
            return path.toString().replace('\\', '/')
        }

        private fun escapeXml(value: String): String {
            return value.replace("&", "&amp;").replace("<", "&lt;")
        }
    }
}
