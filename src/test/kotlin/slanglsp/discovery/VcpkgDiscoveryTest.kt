package slanglsp.discovery

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import slanglsp.discovery.ExecutableLocator.find

internal class VcpkgDiscoveryTest {
    @TempDir var root: Path? = null

    @ParameterizedTest
    @CsvSource("bin, slangd", "Bin, slangd.exe")
    @Throws(Exception::class)
    fun discoversVulkanSdkWithVcpkgDisabledAndNoPath(bin: String?, name: String) {
        val tool = executable("Vulkan SDK/$bin/$name")
        Assertions.assertEquals(
            tool.toString(),
            find(
                "",
                false,
                {
                    Assertions.fail<String>("vcpkg discovery is disabled")
                },
                null,
                root!!.resolve("Vulkan SDK").toString(),
                name))
    }

    @Test
    @Throws(Exception::class)
    fun resolvesExplicitDirectoryAgainstProjectRoot() {
        val tool = executable("vcpkg_installed/tools/shader-slang/slangd")
        val project = Files.createDirectories(root!!.resolve("build/playground"))
        Assertions.assertEquals(
            tool.toString(),
            find("../../vcpkg_installed/tools/shader-slang", false, { null }, null, null, "slangd", project.toString()))
        Assertions.assertEquals(
            tool.toString(), find(tool.parent.toString(), false, { null }, null, null, "slangd", project.toString()))
    }

    @Test
    @Throws(Exception::class)
    fun preservesPriorityAndFallsBackWhenVulkanSdkHasNoServer() {
        val explicit = executable("explicit/slangd")
        val local = executable("local/slangd")
        val sdk = executable("sdk/bin/slangd")
        val onPath = executable("path/slangd")
        val path = onPath.parent.toString()
        val sdkRoot = root!!.resolve("sdk").toString()
        Assertions.assertEquals(
            explicit.toString(), find(explicit.parent.toString(), true, { local.toString() }, path, sdkRoot, "slangd"))
        Assertions.assertEquals(local.toString(), find("", true, { local.toString() }, path, sdkRoot, "slangd"))
        Assertions.assertEquals(sdk.toString(), find("", true, { null }, path, sdkRoot, "slangd"))
        Files.delete(sdk)
        executable("sdk/bin/slangc")
        for (missing in arrayOf(sdkRoot, "", "invalid\u0000path")) {
            Assertions.assertEquals(onPath.toString(), find("", false, { null }, path, missing, "slangd"))
        }
    }

    @Test
    @Throws(Exception::class)
    fun findsServerBesideLinkedCompilerButPrefersServerOnPath() {
        Assumptions.assumeFalse(
            System.getProperty("os.name").startsWith("Windows"),
            "Creating symlinks may require elevated privileges on Windows")
        val compiler = executable("sdk/slangc")
        val bin = Files.createDirectories(root!!.resolve("bin"))
        Files.createSymbolicLink(bin.resolve("slangc"), compiler)
        Assertions.assertNull(find("", false, { null }, bin.toString(), null, "slangd"))
        val server = executable("sdk/slangd")
        Assertions.assertEquals(
            server.toRealPath().toString(), find("", false, { null }, bin.toString(), null, "slangd"))
        val direct = executable("other/slangd")
        Assertions.assertEquals(
            direct.toString(),
            find("", false, { null }, bin.toString() + File.pathSeparator + direct.parent, null, "slangd"))
    }

    @Throws(Exception::class)
    private fun file(relative: String, content: String): Path {
        val path = root!!.resolve(relative)
        Files.createDirectories(path.parent)

        return Files.writeString(path, content)
    }

    @Throws(Exception::class)
    private fun executable(relative: String): Path {
        val path = file(relative, "test fixture")
        Assertions.assertTrue(path.toFile().setExecutable(true))

        return path
    }

    private fun discover(): String? {
        return VcpkgDiscovery("slangd", "Linux", "amd64").find(root!!)
    }

    @Test
    @Throws(Exception::class)
    fun findsManifestInstallationIncludingTransitivePackage() {
        file("vcpkg.json", "{\"dependencies\":[\"some-parent-package\"]}")
        val tool = executable("vcpkg_installed/x64-linux/tools/shader-slang/slangd")
        Assertions.assertEquals(tool.toString(), discover())
    }

    @ParameterizedTest
    @CsvSource(
        "Windows 11, amd64, x64-windows, slangd.exe",
        "Windows 11, aarch64, arm64-windows, slangd.exe",
        "Mac OS X, x86_64, x64-osx, slangd",
        "Mac OS X, aarch64, arm64-osx, slangd",
        "Darwin, arm64, arm64-osx, slangd",
        "Linux, amd64, x64-linux, slangd",
        "Linux, aarch64, arm64-linux, slangd")
    @Throws(Exception::class)
    fun selectsHostToolAcrossOperatingSystems(os: String, arch: String, triplet: String?, name: String) {
        file("vcpkg.json", "{}")

        for (candidate in arrayOf("x64-windows", "arm64-windows", "x64-osx", "arm64-osx", "x64-linux", "arm64-linux")) {
            executable(
                "vcpkg_installed/" +
                    candidate +
                    "/tools/shader-slang/" +
                    (if (candidate.endsWith("windows")) "slangd.exe" else "slangd"))
        }
        Assertions.assertEquals(
            root!!.resolve("vcpkg_installed/$triplet/tools/shader-slang/$name").toString(),
            VcpkgDiscovery(name, os, arch).find(root!!))
    }

    @Test
    @Throws(Exception::class)
    fun readsCustomInstallDirectoryFromCmakeCache() {
        file("vcpkg.json", "{}")
        val tool = executable("custom-install/x64-linux/tools/shader-slang/slangd")
        file(
            "CMakePresets.json",
            $$"""
                {"version":3,"configurePresets":[{"name":"debug","binaryDir":"${sourceDir}/build/cache/debug"}]}
                
                """
                .trimIndent())
        file(
            "build/cache/debug/CMakeCache.txt",
            "VCPKG_INSTALLED_DIR:PATH=" +
                root!!.resolve("custom-install") +
                "\nVCPKG_TARGET_TRIPLET:STRING=arm64-linux\n")
        executable("custom-install/arm64-linux/tools/shader-slang/slangd")
        Assertions.assertEquals(tool.toString(), discover())
    }

    @Test
    @Throws(Exception::class)
    fun explicitPathAndDisabledSettingSkipDiscovery() {
        val explicit = executable("explicit/slangd")
        val onPath = executable("path/slangd")
        val mustNotDiscover: () -> String? = {
            Assertions.fail<String>("vcpkg must not be scanned")
        }
        Assertions.assertEquals(
            explicit.toString(),
            find(explicit.parent.toString(), true, mustNotDiscover, onPath.parent.toString(), null, "slangd"))
        Assertions.assertEquals(
            onPath.toString(), find("", false, mustNotDiscover, onPath.parent.toString(), null, "slangd"))
    }

    @Test
    @Throws(Exception::class)
    fun vcpkgWinsOverPathAndMissingVcpkgFallsBack() {
        val local = executable("local/slangd")
        val onPath = executable("path/slangd")
        Assertions.assertEquals(
            local.toString(), find("", true, { local.toString() }, onPath.parent.toString(), null, "slangd"))
        Assertions.assertEquals(
            onPath.toString(), find("missing", true, { null }, onPath.parent.toString(), null, "slangd"))
    }
}
