package slanglsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.*;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VcpkgSlangdDiscoveryTest {
    @TempDir Path root;

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
        return new VcpkgSlangdDiscovery("slangd", "Linux", "amd64").find(root);
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
                new VcpkgSlangdDiscovery(name, os, arch).find(root));
    }

    @Test void readsCustomInstallDirectoryFromCmakeCache() throws Exception {
        file("vcpkg.json", "{}");
        Path tool = executable("extern/vcpkg/installed/x64-linux/tools/shader-slang/slangd");
        file("build/cache/debug/CMakeCache.txt", "VCPKG_INSTALLED_DIR:PATH="
                + root.resolve("extern/vcpkg/installed") + "\nVCPKG_TARGET_TRIPLET:STRING=arm64-linux\n");
        executable("extern/vcpkg/installed/arm64-linux/tools/shader-slang/slangd");
        assertEquals(Optional.of(tool.toString()), discover());
    }

    @Test void explicitPathAndDisabledSettingSkipDiscovery() throws Exception {
        Path explicit = executable("explicit/slangd");
        Path onPath = executable("path/slangd");
        var mustNotDiscover = (java.util.function.Supplier<Optional<String>>) () -> {
            fail("vcpkg must not be scanned"); return Optional.empty();
        };
        assertEquals(Optional.of(explicit.toString()), SlangExecutableLocator.find(
                explicit.getParent().toString(), true, mustNotDiscover, onPath.getParent().toString(), "slangd"));
        assertEquals(Optional.of(onPath.toString()), SlangExecutableLocator.find(
                "", false, mustNotDiscover, onPath.getParent().toString(), "slangd"));
    }

    @Test void vcpkgWinsOverPathAndMissingVcpkgFallsBack() throws Exception {
        Path local = executable("local/slangd");
        Path onPath = executable("path/slangd");
        assertEquals(Optional.of(local.toString()), SlangExecutableLocator.find(
                "", true, () -> Optional.of(local.toString()), onPath.getParent().toString(), "slangd"));
        assertEquals(Optional.of(onPath.toString()), SlangExecutableLocator.find(
                "missing", true, Optional::empty, onPath.getParent().toString(), "slangd"));
    }

}
