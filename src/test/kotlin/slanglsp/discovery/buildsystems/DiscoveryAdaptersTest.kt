package slanglsp.discovery.buildsystems

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import slanglsp.discovery.DiscoveryContext
import slanglsp.discovery.VcpkgDiscovery

internal class DiscoveryAdaptersTest {
    @TempDir var temp: Path? = null

    @Throws(Exception::class)
    private fun project(): Path {
        return Files.createDirectories(temp!!.resolve("project"))
    }

    @Throws(Exception::class)
    private fun file(name: String, content: String): Path {
        val file = temp!!.resolve(name)
        Files.createDirectories(file.parent)

        return Files.writeString(file, content)
    }

    @Throws(Exception::class)
    private fun server(root: String): Path {
        file("$root/vcpkg/status", "Package: shader-slang\n")
        val file = file("$root/x64-linux/tools/shader-slang/slangd", "fixture")
        Assertions.assertTrue(file.toFile().setExecutable(true))

        return file
    }

    @Throws(Exception::class)
    private fun discover(env: Map<String, String> = emptyMap()): String? {
        return VcpkgDiscovery("slangd", "Linux", "amd64").find(project(), env)
    }

    @Test
    @Throws(Exception::class)
    fun cmakeInheritedPresetsLocateExternalCacheAndPreferItsResolvedValue() {
        val selected = server("selected")
        server("initial")
        file(
            "project/presets/base.json",
            $$"""
                {"configurePresets":[{"name":"base","hidden":true,
                "binaryDir":"${sourceDir}/../build/${presetName}",
                "cacheVariables":{"VCPKG_INSTALLED_DIR":"${sourceDir}/../initial"}}]}
                
                """
                .trimIndent())
        file(
            "project/CMakePresets.json",
            """
                {"version":4,"include":["presets/base.json"],"configurePresets":[{"name":"debug","inherits":"base"}]}
                
                """
                .trimIndent())
        file("build/debug/CMakeCache.txt", "VCPKG_INSTALLED_DIR:PATH=" + temp!!.resolve("selected") + "\n")
        Assertions.assertEquals(selected.toString(), discover())
    }

    @Test
    @Throws(Exception::class)
    fun clionProfileLocatesExternalBuildWithoutPresetsOrManifest() {
        val selected = server("custom")
        file(
            "project/.idea/workspace.xml",
            $$"""
                <project><component name="CMakeSettings"><configurations>
                <configuration GENERATION_DIR="$PROJECT_DIR$/../build/profile"/>
                </configurations></component></project>
                
                """
                .trimIndent())
        file("build/profile/CMakeCache.txt", "VCPKG_INSTALLED_DIR:PATH=" + temp!!.resolve("custom") + "\n")
        Assertions.assertEquals(selected.toString(), discover())
    }

    @Test
    @Throws(Exception::class)
    fun slnxFollowsProjectAndPropsWithTargetTripletPath() {
        val selected = server("custom")
        file(
            "project/game.slnx",
            "<Solution><Folder Name=\"/src/\"><Project Path=\"src/game.vcxproj\"/></Folder></Solution>")
        file("project/src/game.vcxproj", "<Project><Import Project=\"../deps.props\"/></Project>")
        file(
            "project/deps.props",
            """
                <Project><PropertyGroup>
                <VcpkgTriplet>arm64-linux</VcpkgTriplet>
                <VcpkgInstalledDir>$(MSBuildThisFileDirectory)../custom/$(VcpkgTriplet)</VcpkgInstalledDir>
                </PropertyGroup></Project>
                
                """
                .trimIndent())
        Assertions.assertEquals(selected.toString(), discover())
    }

    @Test
    @Throws(Exception::class)
    fun xmakeReadsSavedConfigurationWithoutEvaluatingLua() {
        val selected = server("sdk/installed")
        file("project/xmake.lua", "error('do not evaluate this file')")
        file("project/.xmake/linux/x86_64/xmake.conf", "{\n    vcpkg = \"../sdk\",\n    buildir = \"output\"\n}\n")
        Assertions.assertEquals(selected.toString(), discover())
    }

    @Test
    @Throws(Exception::class)
    fun mesonIntrospectionIdentifiesCustomInstallTreeFromDependencyPaths() {
        val selected = server("custom")
        file("project/meson.build", "project('example','cpp')")
        file(
            "project/builddir/meson-info/intro-dependencies.json",
            """
                [{"name":"slang","compile_args":["-I","../../custom/x64-linux/include"]}]
                
                """
                .trimIndent())
        Assertions.assertEquals(selected.toString(), discover())
    }

    @Test
    @Throws(Exception::class)
    fun conventionalRootsWinInOrderBeforeBuildMetadataAndEnvironment() {
        val manifest = server("project/vcpkg_installed")
        val checkout = server("project/vcpkg/installed")
        val external = server("project/extern/vcpkg/installed")
        val configured = server("custom")
        server("global/installed")
        file("project/build/CMakeCache.txt", "VCPKG_INSTALLED_DIR:PATH=" + temp!!.resolve("custom") + "\n")
        val env = mapOf("VCPKG_ROOT" to temp!!.resolve("global").toString())

        for (expected in arrayOf(manifest, checkout, external, configured)) {
            Assertions.assertEquals(expected.toString(), discover(env))
            Files.delete(expected)
        }
        Assertions.assertEquals(
            temp!!.resolve("global/installed/x64-linux/tools/shader-slang/slangd").toString(), discover(env))
    }

    @Test
    @Throws(Exception::class)
    fun makeAndStandaloneCompilationDatabaseDoNotSupplyInstallations() {
        server("custom")
        file("project/Makefile", "VCPKG_INSTALLED_DIR = " + temp!!.resolve("custom") + "\n")
        file(
            "project/compile_commands.json",
            """
                [{"directory":".","arguments":["c++","-I","../custom/x64-linux/include"]}]
                
                """
                .trimIndent())
        Assertions.assertNull(discover())
    }

    @Test
    @Throws(Exception::class)
    fun commonRootsWorkWithoutBuildSystemAndLocalWinsOverEnvironment() {
        val selected = server("project/extern/vcpkg/installed")
        server("global/installed")
        val env = mapOf("VCPKG_ROOT" to temp!!.resolve("global").toString())
        Assertions.assertEquals(selected.toString(), discover(env))
        Files.delete(selected)
        Assertions.assertEquals(
            temp!!.resolve("global/installed/x64-linux/tools/shader-slang/slangd").toString(), discover(env))
    }

    @Test
    @Throws(Exception::class)
    fun malformedMetadataAndDynamicExpressionsDoNotBlockFallbackOrScanUnrelatedTrees() {
        file("project/CMakePresets.json", "{broken")
        file("project/Makefile", "VCPKG_ROOT = $(shell touch should-not-exist)\n")
        file("project/Directory.Build.props", "<!DOCTYPE Project SYSTEM 'file:///should-not-read'><Project/>")
        server("project/unrelated/deep/vcpkg_installed")
        Assertions.assertNull(discover())
        Assertions.assertFalse(Files.exists(project().resolve("should-not-exist")))
        val selected = server("fallback/installed")
        Assertions.assertEquals(
            selected.toString(), discover(mapOf("VCPKG_ROOT" to temp!!.resolve("fallback").toString())))
    }

    @Test
    @Throws(Exception::class)
    fun rejectsHomeExpansionButAcceptsEmbeddedTildes() {
        val context = DiscoveryContext(project(), emptyMap())

        Assertions.assertNull(context.resolve(temp, "~/sdk"))
        Assertions.assertNull(context.resolve(temp, "~runner/sdk"))
        Assertions.assertEquals(
            temp!!.resolve("RUNNER~1/sdk").toAbsolutePath().normalize(), context.resolve(temp, "RUNNER~1/sdk"))
    }

    @Test
    fun rejectsDoctypesButAcceptsDoctypeTextInComments() {
        val context = DiscoveryContext(project(), emptyMap())
        val internal = file("project/internal.xml", """<!DOCTYPE Project [<!ENTITY value "example">]><Project/>""")
        val external = file("project/external.xml", """<!DOCTYPE Project SYSTEM "file:///not-read.dtd"><Project/>""")
        val regular = file("project/regular.xml", "<!-- <!DOCTYPE example> --><Project/>")

        Assertions.assertNull(context.xml(internal))
        Assertions.assertNull(context.xml(external))
        Assertions.assertEquals("Project", context.xml(regular)?.documentElement?.tagName)
    }

    @Test
    fun expandsNestedVariablesWithoutInterpretingReplacementCharacters() {
        val variables = mapOf("base" to $$"lib\\sdk$1", "include" to "$(base)/include")
        Assertions.assertEquals(
            $$"lib\\sdk$1/include/${unknown}", DiscoveryContext.expand($$"${include}/${unknown}", variables))
        Assertions.assertNull(DiscoveryContext.expand($$"${oversized}", mapOf("oversized" to "x".repeat(8193))))
    }
}
