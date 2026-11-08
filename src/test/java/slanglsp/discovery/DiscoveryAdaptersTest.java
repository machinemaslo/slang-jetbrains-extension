package slanglsp.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryAdaptersTest {
    @TempDir Path temp;

    private Path project() throws Exception { return Files.createDirectories(temp.resolve("project")); }

    private Path file(String name, String content) throws Exception {
        Path file = temp.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private Path server(String root) throws Exception {
        file(root + "/vcpkg/status", "Package: shader-slang\n");
        Path file = file(root + "/x64-linux/tools/shader-slang/slangd", "fixture");
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    private Optional<String> discover(Map<String, String> env) throws Exception {
        return new VcpkgDiscovery("slangd", "Linux", "amd64").find(project(), env);
    }

    @Test void cmakeInheritedPresetsLocateExternalCacheAndPreferItsResolvedValue() throws Exception {
        Path selected = server("selected");
        server("initial");
        file("project/presets/base.json", """
                {"configurePresets":[{"name":"base","hidden":true,
                "binaryDir":"${sourceDir}/../build/${presetName}",
                "cacheVariables":{"VCPKG_INSTALLED_DIR":"${sourceDir}/../initial"}}]}
                """);
        file("project/CMakePresets.json", """
                {"version":4,"include":["presets/base.json"],"configurePresets":[{"name":"debug","inherits":"base"}]}
                """);
        file("build/debug/CMakeCache.txt", "VCPKG_INSTALLED_DIR:PATH=" + temp.resolve("selected") + "\n");
        assertEquals(Optional.of(selected.toString()), discover(Map.of()));
    }

    @Test void clionProfileLocatesExternalBuildWithoutPresetsOrManifest() throws Exception {
        Path selected = server("custom");
        file("project/.idea/workspace.xml", """
                <project><component name="CMakeSettings"><configurations>
                <configuration GENERATION_DIR="$PROJECT_DIR$/../build/profile"/>
                </configurations></component></project>
                """);
        file("build/profile/CMakeCache.txt", "VCPKG_INSTALLED_DIR:PATH=" + temp.resolve("custom") + "\n");
        assertEquals(Optional.of(selected.toString()), discover(Map.of()));
    }

    @Test void slnxFollowsProjectAndPropsWithTargetTripletPath() throws Exception {
        Path selected = server("custom");
        file("project/game.slnx", "<Solution><Folder Name=\"/src/\"><Project Path=\"src/game.vcxproj\"/></Folder></Solution>");
        file("project/src/game.vcxproj", "<Project><Import Project=\"../deps.props\"/></Project>");
        file("project/deps.props", """
                <Project><PropertyGroup>
                <VcpkgTriplet>arm64-linux</VcpkgTriplet>
                <VcpkgInstalledDir>$(MSBuildThisFileDirectory)../custom/$(VcpkgTriplet)</VcpkgInstalledDir>
                </PropertyGroup></Project>
                """);
        assertEquals(Optional.of(selected.toString()), discover(Map.of()));
    }

    @Test void xmakeReadsSavedConfigurationWithoutEvaluatingLua() throws Exception {
        Path selected = server("sdk/installed");
        file("project/xmake.lua", "error('do not evaluate this file')");
        file("project/.xmake/linux/x86_64/xmake.conf", "{\n    vcpkg = \"../sdk\",\n    buildir = \"output\"\n}\n");
        assertEquals(Optional.of(selected.toString()), discover(Map.of()));
    }

    @Test void mesonIntrospectionIdentifiesCustomInstallTreeFromDependencyPaths() throws Exception {
        Path selected = server("custom");
        file("project/meson.build", "project('example','cpp')");
        file("project/builddir/meson-info/intro-dependencies.json", """
                [{"name":"slang","compile_args":["-I","../../custom/x64-linux/include"]}]
                """);
        assertEquals(Optional.of(selected.toString()), discover(Map.of()));
    }

    @Test void conventionalRootsWinInOrderBeforeBuildMetadataAndEnvironment() throws Exception {
        Path manifest = server("project/vcpkg_installed");
        Path checkout = server("project/vcpkg/installed");
        Path external = server("project/extern/vcpkg/installed");
        Path configured = server("custom");
        server("global/installed");
        file("project/build/CMakeCache.txt", "VCPKG_INSTALLED_DIR:PATH=" + temp.resolve("custom") + "\n");
        Map<String, String> env = Map.of("VCPKG_ROOT", temp.resolve("global").toString());
        for (Path expected : new Path[]{manifest, checkout, external, configured}) {
            assertEquals(Optional.of(expected.toString()), discover(env));
            Files.delete(expected);
        }
        assertEquals(Optional.of(temp.resolve("global/installed/x64-linux/tools/shader-slang/slangd").toString()),
                discover(env));
    }

    @Test void makeAndStandaloneCompilationDatabaseDoNotSupplyInstallations() throws Exception {
        server("custom");
        file("project/Makefile", "VCPKG_INSTALLED_DIR = " + temp.resolve("custom") + "\n");
        file("project/compile_commands.json", """
                [{"directory":".","arguments":["c++","-I","../custom/x64-linux/include"]}]
                """);
        assertTrue(discover(Map.of()).isEmpty());
    }

    @Test void commonRootsWorkWithoutBuildSystemAndLocalWinsOverEnvironment() throws Exception {
        Path selected = server("project/extern/vcpkg/installed");
        server("global/installed");
        assertEquals(Optional.of(selected.toString()), discover(Map.of("VCPKG_ROOT", temp.resolve("global").toString())));
        Files.delete(selected);
        assertEquals(Optional.of(temp.resolve("global/installed/x64-linux/tools/shader-slang/slangd").toString()),
                discover(Map.of("VCPKG_ROOT", temp.resolve("global").toString())));
    }

    @Test void malformedMetadataAndDynamicExpressionsDoNotBlockFallbackOrScanUnrelatedTrees() throws Exception {
        file("project/CMakePresets.json", "{broken");
        file("project/Makefile", "VCPKG_ROOT = $(shell touch should-not-exist)\n");
        file("project/Directory.Build.props", "<!DOCTYPE Project SYSTEM 'file:///should-not-read'><Project/>");
        server("project/unrelated/deep/vcpkg_installed");
        assertTrue(discover(Map.of()).isEmpty());
        assertFalse(Files.exists(project().resolve("should-not-exist")));
        Path selected = server("fallback/installed");
        assertEquals(Optional.of(selected.toString()), discover(Map.of("VCPKG_ROOT", temp.resolve("fallback").toString())));
    }
}
