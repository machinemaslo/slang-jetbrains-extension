package slanglsp.discovery

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import slanglsp.TestDependencies

/** Whole-project fixtures, including a real vcpkg installation. */
internal class VcpkgProjectBundleTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @EnumSource(VcpkgProjectFixture.BuildSystem::class)
    @Throws(Exception::class)
    fun prefersLocalInstallations(system: VcpkgProjectFixture.BuildSystem) {
        val fixture = VcpkgProjectFixture(temporary)
        val installed = temporary.resolve("external sdk/installed")
        val expected = fixture.createTool(installed, "x64-linux", "slangd")
        val root = fixture.createProject(system, installed, "x64-linux")

        // Cross-target tools must not win over the host tool.
        fixture.createTool(installed, "arm64-linux", "slangd")
        assertLinuxTool(root, expected)

        val localTools: MutableList<Path> = ArrayList()

        for (directory in LOCAL_INSTALLATIONS) {
            localTools.add(fixture.createTool(root.resolve(directory), "x64-linux", "slangd"))
        }

        for (preferred in localTools) {
            assertLinuxTool(root, preferred)
            Files.delete(preferred)
        }

        assertLinuxTool(root, expected)
        Files.delete(expected)

        Assertions.assertNull(
            discover(root, "slangd", "Linux", "amd64"), "Target-only installation must not be selected")
    }

    @ParameterizedTest
    @EnumSource(VcpkgProjectFixture.BuildSystem::class)
    @Throws(Exception::class)
    fun findsWindowsTool(system: VcpkgProjectFixture.BuildSystem) {
        val fixture = VcpkgProjectFixture(temporary)
        val installed = temporary.resolve("external sdk/installed")
        val expected = fixture.createTool(installed, "x64-windows", "slangd.exe")
        val root = fixture.createProject(system, installed, "x64-windows")

        Assertions.assertEquals(expected.toString(), discover(root, "slangd.exe", "Windows 11", "amd64"))
    }

    @ParameterizedTest
    @EnumSource(VcpkgProjectFixture.BuildSystem::class)
    @Throws(Exception::class)
    fun findsInstallationUnderWindowsShortUserDirectory(system: VcpkgProjectFixture.BuildSystem) {
        val fixture = VcpkgProjectFixture(temporary.resolve("RUNNER~1/Temp"))
        val installed = temporary.resolve("RUNNER~1/Temp/external sdk/installed")
        val expected = fixture.createTool(installed, "x64-windows", "slangd.exe")
        val root = fixture.createProject(system, installed, "x64-windows")

        Assertions.assertEquals(expected.toString(), discover(root, "slangd.exe", "Windows 11", "amd64"))
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    @Throws(Exception::class)
    fun findsRealInstallation() {
        val vcpkg = TestDependencies.vcpkgRoot()
        val host: Host = Host.current()
        val fixture = VcpkgProjectFixture(temporary)
        val installed = temporary.resolve("real sdk/installed")
        val cmake = fixture.createProject(VcpkgProjectFixture.BuildSystem.CMAKE, installed, host.triplet)

        installSlang(fixture, vcpkg, cmake, installed, host)
        configureCmake(fixture, vcpkg, cmake, installed)
        assertAllAdapters(fixture, cmake, installed, host)
    }

    @Throws(Exception::class)
    private fun installSlang(fixture: VcpkgProjectFixture, vcpkg: Path, project: Path, installed: Path, host: Host) {
        val command = if (host.windows) "vcpkg.exe" else "vcpkg"

        fixture.run(
            project,
            vcpkg.resolve(command).toString(),
            "install",
            "--triplet=" + host.triplet,
            "--host-triplet=" + host.triplet,
            "--x-install-root=$installed")

        Assertions.assertTrue(
            Files.isRegularFile(host.tool(installed)), "vcpkg must actually install shader-slang's language server")
    }

    @Throws(Exception::class)
    private fun configureCmake(fixture: VcpkgProjectFixture, vcpkg: Path, project: Path, installed: Path) {
        val toolchain = vcpkg.resolve("scripts/buildsystems/vcpkg.cmake")
        fixture.run(project, "cmake", "--preset", "bundle", "-DCMAKE_TOOLCHAIN_FILE=$toolchain")

        val cache = Files.readString(project.resolve("build/CMakeCache.txt"))
        Assertions.assertTrue(cache.contains(installed.toString().replace('\\', '/')))
    }

    @Throws(Exception::class)
    private fun assertAllAdapters(fixture: VcpkgProjectFixture, cmake: Path, installed: Path, host: Host) {
        for (system in VcpkgProjectFixture.BuildSystem.entries) {
            val project =
                if (system == VcpkgProjectFixture.BuildSystem.CMAKE) cmake
                else fixture.createProject(system, installed, host.triplet)

            Assertions.assertEquals(
                host.tool(installed).toString(), discover(project, host.executable(), host.os, host.arch), system.name)
        }
    }

    private fun assertLinuxTool(project: Path, expected: Path) {
        Assertions.assertEquals(expected.toString(), discover(project, "slangd", "Linux", "amd64"))
    }

    private fun discover(project: Path, executable: String, os: String, arch: String): String? {
        // Isolate discovery from the developer's PATH and global Xmake configuration.
        val environment = mapOf("XMAKE_GLOBALDIR" to temporary.resolve("empty home").toString())

        return VcpkgDiscovery(executable, os, arch).find(project, environment)
    }

    @JvmRecord
    private data class Host(val os: String, val arch: String, val windows: Boolean, val triplet: String) {
        fun executable(): String {
            return if (windows) "slangd.exe" else "slangd"
        }

        fun tool(installed: Path): Path {
            return installed.resolve(triplet + "/tools/shader-slang/" + executable())
        }

        companion object {
            fun current(): Host {
                val os = System.getProperty("os.name")
                val arch = System.getProperty("os.arch")
                val normalizedOs = os.lowercase()
                val windows = normalizedOs.startsWith("windows")
                val cpu = if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"
                val platform = if (windows) "-windows" else if (normalizedOs.contains("mac")) "-osx" else "-linux"

                return Host(os, arch, windows, cpu + platform)
            }
        }
    }

    companion object {
        private val LOCAL_INSTALLATIONS = mutableListOf("vcpkg_installed", "vcpkg/installed", "extern/vcpkg/installed")
    }
}
