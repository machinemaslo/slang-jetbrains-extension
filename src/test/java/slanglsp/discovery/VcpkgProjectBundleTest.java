package slanglsp.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Whole-project fixtures, with an optional real vcpkg installation. */
class VcpkgProjectBundleTest {
    private static final List<String> LOCAL_INSTALLATIONS =
            List.of("vcpkg_installed", "vcpkg/installed", "extern/vcpkg/installed");

    @TempDir Path temporary;

    @ParameterizedTest
    @EnumSource(VcpkgProjectFixture.BuildSystem.class)
    void prefersLocalInstallations(VcpkgProjectFixture.BuildSystem system) throws Exception {
        var fixture = new VcpkgProjectFixture(temporary);
        Path installed = temporary.resolve("external sdk/installed");
        Path expected = fixture.createTool(installed, "x64-linux", "slangd");
        Path root = fixture.createProject(system, installed, "x64-linux");

        // Cross-target tools must not win over the host tool.
        fixture.createTool(installed, "arm64-linux", "slangd");
        assertLinuxTool(root, expected);

        List<Path> localTools = new ArrayList<>();

        for (String directory : LOCAL_INSTALLATIONS) {
            localTools.add(fixture.createTool(root.resolve(directory), "x64-linux", "slangd"));
        }

        for (Path preferred : localTools) {
            assertLinuxTool(root, preferred);
            Files.delete(preferred);
        }

        assertLinuxTool(root, expected);
        Files.delete(expected);

        assertTrue(
                discover(root, "slangd", "Linux", "amd64").isEmpty(), "Target-only installation must not be selected");
    }

    @ParameterizedTest
    @EnumSource(VcpkgProjectFixture.BuildSystem.class)
    void findsWindowsTool(VcpkgProjectFixture.BuildSystem system) throws Exception {
        var fixture = new VcpkgProjectFixture(temporary);
        Path installed = temporary.resolve("external sdk/installed");
        Path expected = fixture.createTool(installed, "x64-windows", "slangd.exe");
        Path root = fixture.createProject(system, installed, "x64-windows");

        assertEquals(Optional.of(expected.toString()), discover(root, "slangd.exe", "Windows 11", "amd64"));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void findsRealInstallation() throws Exception {
        Path vcpkg = configuredVcpkg();
        Host host = Host.current();
        var fixture = new VcpkgProjectFixture(temporary);
        Path installed = temporary.resolve("real sdk/installed");
        Path cmake = fixture.createProject(VcpkgProjectFixture.BuildSystem.CMAKE, installed, host.triplet());

        installSlang(fixture, vcpkg, cmake, installed, host);
        configureCmake(fixture, vcpkg, cmake, installed);
        assertAllAdapters(fixture, cmake, installed, host);
    }

    private Path configuredVcpkg() {
        String root = System.getenv("VCPKG_TEST_ROOT");

        assumeTrue(root != null && !root.isBlank(), "Set VCPKG_TEST_ROOT to opt into a real shader-slang installation");

        return Path.of(root).toAbsolutePath();
    }

    private void installSlang(VcpkgProjectFixture fixture, Path vcpkg, Path project, Path installed, Host host)
            throws Exception {
        String command = host.windows() ? "vcpkg.exe" : "vcpkg";

        fixture.run(project, vcpkg.resolve(command).toString(), "install", "--triplet=" + host.triplet(),
                "--host-triplet=" + host.triplet(), "--x-install-root=" + installed);

        assertTrue(Files.isRegularFile(host.tool(installed)),
                "vcpkg must actually install shader-slang's language server");
    }

    private void configureCmake(VcpkgProjectFixture fixture, Path vcpkg, Path project, Path installed)
            throws Exception {
        Path toolchain = vcpkg.resolve("scripts/buildsystems/vcpkg.cmake");
        fixture.run(project, "cmake", "--preset", "bundle", "-DCMAKE_TOOLCHAIN_FILE=" + toolchain);

        String cache = Files.readString(project.resolve("build/CMakeCache.txt"));
        assertTrue(cache.contains(installed.toString().replace('\\', '/')));
    }

    private void assertAllAdapters(VcpkgProjectFixture fixture, Path cmake, Path installed, Host host)
            throws Exception {
        for (var system : VcpkgProjectFixture.BuildSystem.values()) {
            Path project = system == VcpkgProjectFixture.BuildSystem.CMAKE
                                   ? cmake
                                   : fixture.createProject(system, installed, host.triplet());

            assertEquals(Optional.of(host.tool(installed).toString()),
                    discover(project, host.executable(), host.os(), host.arch()), system.name());
        }
    }

    private void assertLinuxTool(Path project, Path expected) {
        assertEquals(Optional.of(expected.toString()), discover(project, "slangd", "Linux", "amd64"));
    }

    private Optional<String> discover(Path project, String executable, String os, String arch) {
        // Isolate discovery from the developer's PATH and global Xmake configuration.
        Map<String, String> environment = Map.of("XMAKE_GLOBALDIR", temporary.resolve("empty home").toString());

        return new VcpkgDiscovery(executable, os, arch).find(project, environment);
    }

    private record Host(String os, String arch, boolean windows, String triplet) {
        static Host current() {
            String os = System.getProperty("os.name");
            String arch = System.getProperty("os.arch");
            String normalizedOs = os.toLowerCase(Locale.ROOT);
            boolean windows = normalizedOs.startsWith("windows");
            String cpu = arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : "x64";
            String platform = windows ? "-windows" : normalizedOs.contains("mac") ? "-osx" : "-linux";

            return new Host(os, arch, windows, cpu + platform);
        }

        String executable() {
            return windows ? "slangd.exe" : "slangd";
        }

        Path tool(Path installed) {
            return installed.resolve(triplet + "/tools/shader-slang/" + executable());
        }
    }
}
