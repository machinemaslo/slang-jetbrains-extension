package slanglsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
