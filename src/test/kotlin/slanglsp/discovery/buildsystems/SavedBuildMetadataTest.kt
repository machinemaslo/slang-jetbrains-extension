package slanglsp.discovery.buildsystems

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import slanglsp.discovery.DiscoveryContext

class SavedBuildMetadataTest {
    @TempDir lateinit var directory: Path

    @Test
    fun cmakeKeepsParentOrderAndDistinguishesPresetFromParentEnvironment() {
        write(
            "CMakePresets.json",
            $$"""
            {
              "include": "CMakePresets.json",
              "configurePresets": [
                {"name":"first","hidden":true,"cacheVariables":{"VCPKG_INSTALLED_DIR":{"value":"$env{SDK_ROOT}"}}},
                {"name":"second","hidden":true,"cacheVariables":{"VCPKG_INSTALLED_DIR":"unused"}},
                {
                  "name":"debug",
                  "inherits":["first","second"],
                  "environment":{"SDK_ROOT":"selected"},
                  "toolchainFile":"$penv{SDK_ROOT}/scripts/buildsystems/vcpkg.cmake"
                }
              ]
            }
        """
                .trimIndent())
        val context = DiscoveryContext(directory, mapOf("SDK_ROOT" to "parent"))

        CmakeDiscoveryAdapter().discover(context)

        assertEquals(
            listOf(directory.resolve("selected"), directory.resolve("parent/installed")),
            context.installations.keys.toList(),
        )
    }

    @Test
    fun msbuildReadsClassicSolutionAndRestoresFileDirectoryAfterImport() {
        write("game.sln", "Project(\"{type}\") = \"Game\", \"src/game.vcxproj\", \"{project}\"")
        write(
            "src/game.vcxproj",
            """
            <Project>
                <Import Project="../metadata/deps.props"/>
                <PropertyGroup>
                    <VcpkgManifestInstalledBaseDir>$(MSBuildThisFileDirectory)sdk</VcpkgManifestInstalledBaseDir>
                </PropertyGroup>
                <PropertyGroup Condition="true"><VcpkgInstalledDir>ignored</VcpkgInstalledDir></PropertyGroup>
            </Project>
        """
                .trimIndent())
        write(
            "metadata/deps.props",
            """
            <Project><PropertyGroup>
                <SdkPath>$(MSBuildThisFileDirectory)../selected</SdkPath>
            </PropertyGroup></Project>
        """
                .trimIndent())
        write(
            "Directory.Build.targets",
            """
            <Project><PropertyGroup>
                <VcpkgInstalledDir>$(SdkPath)</VcpkgInstalledDir>
                <VcpkgInstalledDir Condition="true">ignored</VcpkgInstalledDir>
            </PropertyGroup></Project>
        """
                .trimIndent())
        val context = DiscoveryContext(directory, emptyMap())

        MsbuildDiscoveryAdapter().discover(context)

        assertEquals(
            listOf("selected", "src/sdk", "src/vcpkg_installed").map(directory::resolve),
            context.installations.keys.toList(),
        )
    }

    @Test
    fun xmakePrefersProjectConfigAndMesonReadsItsSavedBuildDirectory() {
        write("xmake.lua", "error('must not execute')")
        write(
            "config/.xmake/host/arch/xmake.conf",
            """
            ["vcpkg"] = "project-sdk",
            ["builddir"] = "output",
        """
                .trimIndent())
        write("home/.xmake/xmake.conf", "vcpkg = \"global-sdk\"")
        write("meson.build", "error('must not execute')")
        write(
            "output/meson-info/intro-dependencies.json",
            """
            [{
                "include_directories": "../headers/x64-linux/include",
                "compile_args": ["-I", "../compiler/x64-linux/include"],
                "link_args": ["-L../linker/x64-linux/lib"]
            }]
        """
                .trimIndent())
        for (root in listOf("headers", "compiler", "linker")) {
            write("$root/vcpkg/status", "")
        }
        val context = DiscoveryContext(directory, mapOf("XMAKE_CONFIGDIR" to "config", "XMAKE_GLOBALDIR" to "home"))

        XmakeDiscoveryAdapter().discover(context)
        MesonDiscoveryAdapter().discover(context)

        assertEquals(
            listOf("project-sdk/installed", "global-sdk/installed", "headers", "compiler", "linker")
                .map(directory::resolve),
            context.installations.keys.toList(),
        )
    }

    private fun write(file: String, text: String) {
        val path = directory.resolve(file)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }
}
