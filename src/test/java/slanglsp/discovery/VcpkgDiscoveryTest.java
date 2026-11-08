package slanglsp.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.*;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VcpkgDiscoveryTest {
    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({"bin, slangd", "Bin, slangd.exe"})
    void discoversVulkanSdkWithVcpkgDisabledAndNoPath(String bin, String name) throws Exception {
        Path tool = executable("Vulkan SDK/" + bin + "/" + name);
        assertEquals(Optional.of(tool.toString()), ExecutableLocator.find("", false,
                () -> { fail("vcpkg discovery is disabled"); return Optional.empty(); },
                null, root.resolve("Vulkan SDK").toString(), name));
    }

    @Test void preservesPriorityAndFallsBackWhenVulkanSdkHasNoServer() throws Exception {
        Path explicit = executable("explicit/slangd");
        Path local = executable("local/slangd");
        Path sdk = executable("sdk/bin/slangd");
        Path onPath = executable("path/slangd");
        String path = onPath.getParent().toString(), sdkRoot = root.resolve("sdk").toString();
        assertEquals(Optional.of(explicit.toString()), ExecutableLocator.find(
                explicit.getParent().toString(), true, () -> Optional.of(local.toString()), path, sdkRoot, "slangd"));
        assertEquals(Optional.of(local.toString()), ExecutableLocator.find(
                "", true, () -> Optional.of(local.toString()), path, sdkRoot, "slangd"));
        assertEquals(Optional.of(sdk.toString()), ExecutableLocator.find(
                "", true, Optional::empty, path, sdkRoot, "slangd"));
        Files.delete(sdk);
        executable("sdk/bin/slangc");
        for (String missing : new String[]{sdkRoot, "", "invalid\u0000path"}) {
            assertEquals(Optional.of(onPath.toString()), ExecutableLocator.find(
                    "", false, Optional::empty, path, missing, "slangd"));
        }
    }

    @Test void findsServerBesideLinkedCompilerButPrefersServerOnPath() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"),
                "Creating symlinks may require elevated privileges on Windows");
        Path compiler = executable("sdk/slangc");
        Path bin = Files.createDirectories(root.resolve("bin"));
        Files.createSymbolicLink(bin.resolve("slangc"), compiler);
        assertTrue(ExecutableLocator.find("", false, Optional::empty, bin.toString(), null, "slangd").isEmpty());
        Path server = executable("sdk/slangd");
        assertEquals(Optional.of(server.toRealPath().toString()),
                ExecutableLocator.find("", false, Optional::empty, bin.toString(), null, "slangd"));
        Path direct = executable("other/slangd");
        assertEquals(Optional.of(direct.toString()), ExecutableLocator.find("", false, Optional::empty,
                bin + java.io.File.pathSeparator + direct.getParent(), null, "slangd"));
    }

    private Path file(String relative, String content) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }

    private Path executable(String relative) throws Exception {
        Path path = file(relative, "test fixture");
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }

    private Optional<String> discover() {
        return new VcpkgDiscovery("slangd", "Linux", "amd64").find(root);
    }

    @Test void findsManifestInstallationIncludingTransitivePackage() throws Exception {
        file("vcpkg.json", "{\"dependencies\":[\"some-parent-package\"]}");
        Path tool = executable("vcpkg_installed/x64-linux/tools/shader-slang/slangd");
        assertEquals(Optional.of(tool.toString()), discover());
    }

    @ParameterizedTest
    @CsvSource({
            "Windows 11, amd64, x64-windows, slangd.exe",
            "Windows 11, aarch64, arm64-windows, slangd.exe",
            "Mac OS X, x86_64, x64-osx, slangd",
            "Mac OS X, aarch64, arm64-osx, slangd",
            "Darwin, arm64, arm64-osx, slangd",
            "Linux, amd64, x64-linux, slangd",
            "Linux, aarch64, arm64-linux, slangd"
    })
    void selectsHostToolAcrossOperatingSystems(String os, String arch, String triplet, String name) throws Exception {
        file("vcpkg.json", "{}");
        for (String candidate : new String[]{"x64-windows", "arm64-windows", "x64-osx", "arm64-osx", "x64-linux", "arm64-linux"}) {
            executable("vcpkg_installed/" + candidate + "/tools/shader-slang/"
                    + (candidate.endsWith("windows") ? "slangd.exe" : "slangd"));
        }
        assertEquals(Optional.of(root.resolve("vcpkg_installed/" + triplet + "/tools/shader-slang/" + name).toString()),
                new VcpkgDiscovery(name, os, arch).find(root));
    }

    @Test void readsCustomInstallDirectoryFromCmakeCache() throws Exception {
        file("vcpkg.json", "{}");
        Path tool = executable("custom-install/x64-linux/tools/shader-slang/slangd");
        file("CMakePresets.json", """
                {"version":3,"configurePresets":[{"name":"debug","binaryDir":"${sourceDir}/build/cache/debug"}]}
                """);
        file("build/cache/debug/CMakeCache.txt", "VCPKG_INSTALLED_DIR:PATH="
                + root.resolve("custom-install") + "\nVCPKG_TARGET_TRIPLET:STRING=arm64-linux\n");
        executable("custom-install/arm64-linux/tools/shader-slang/slangd");
        assertEquals(Optional.of(tool.toString()), discover());
    }

    @Test void explicitPathAndDisabledSettingSkipDiscovery() throws Exception {
        Path explicit = executable("explicit/slangd");
        Path onPath = executable("path/slangd");
        var mustNotDiscover = (java.util.function.Supplier<Optional<String>>) () -> {
            fail("vcpkg must not be scanned"); return Optional.empty();
        };
        assertEquals(Optional.of(explicit.toString()), ExecutableLocator.find(
                explicit.getParent().toString(), true, mustNotDiscover, onPath.getParent().toString(), null, "slangd"));
        assertEquals(Optional.of(onPath.toString()), ExecutableLocator.find(
                "", false, mustNotDiscover, onPath.getParent().toString(), null, "slangd"));
    }

    @Test void vcpkgWinsOverPathAndMissingVcpkgFallsBack() throws Exception {
        Path local = executable("local/slangd");
        Path onPath = executable("path/slangd");
        assertEquals(Optional.of(local.toString()), ExecutableLocator.find(
                "", true, () -> Optional.of(local.toString()), onPath.getParent().toString(), null, "slangd"));
        assertEquals(Optional.of(onPath.toString()), ExecutableLocator.find(
                "missing", true, Optional::empty, onPath.getParent().toString(), null, "slangd"));
    }

}
