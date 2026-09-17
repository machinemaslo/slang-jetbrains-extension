package slanglsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    @Test void findsNestedManifestAndOverlaySlangPort() throws Exception {
        file("engine/vcpkg.json", "{}");
        Path tool = executable("engine/vcpkg_installed/x64-linux/tools/slang/slangd");
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

    @Test void readsRelativeInstallDirectoryAndCustomHostTriplet() throws Exception {
        file("vcpkg.json", "{}");
        Path tool = executable("custom/my-host/tools/shader-slang/slangd");
        file("build/CMakeCache.txt", "_VCPKG_INSTALLED_DIR:PATH=../custom\nVCPKG_HOST_TRIPLET:STRING=my-host\n");
        assertEquals(Optional.of(tool.toString()), discover());
    }

    @Test void findsCmakeDefaultInstallation() throws Exception {
        file("vcpkg.json", "{}");
        file("cmake-build-debug/CMakeCache.txt", "// No explicit install root\n");
        Path tool = executable("cmake-build-debug/vcpkg_installed/x64-linux/tools/shader-slang/slangd");
        assertEquals(Optional.of(tool.toString()), discover());
    }

    @Test void rejectsForeignArchitectureAndNonExecutableFiles() throws Exception {
        file("vcpkg.json", "{}");
        executable("vcpkg_installed/arm64-linux/tools/shader-slang/slangd");
        Path tool = file("vcpkg_installed/x64-linux/tools/shader-slang/slangd", "not executable");
        assertTrue(tool.toFile().setExecutable(false));
        assertTrue(discover().isEmpty());
    }

    @Test void doesNotUseUnrelatedToolsOrWalkPackageSources() throws Exception {
        executable("bin/slangd");
        file("vcpkg/ports/shader-slang/vcpkg.json", "{}");
        executable("vcpkg/ports/shader-slang/vcpkg_installed/x64-linux/tools/shader-slang/slangd");
        assertTrue(discover().isEmpty());
    }

    @Test void handlesMalformedCacheAndMissingPackage() throws Exception {
        file("vcpkg.json", "{}");
        file("build/CMakeCache.txt", "bad data\nVCPKG_INSTALLED_DIR:PATH=\u0000invalid\n");
        assertTrue(discover().isEmpty());
    }

    @Test void supportsWindowsExecutableAndTripletVariants() throws Exception {
        file("vcpkg.json", "{}");
        Path tool = executable("vcpkg_installed/x64-windows-static/tools/shader-slang/slangd.exe");
        assertEquals(Optional.of(tool.toString()),
                new VcpkgSlangdDiscovery("slangd.exe", "Windows 11", "amd64").find(root));
    }

    @Test void supportsAppleSilicon() throws Exception {
        file("vcpkg.json", "{}");
        Path tool = executable("vcpkg_installed/arm64-osx/tools/shader-slang/slangd");
        assertEquals(Optional.of(tool.toString()),
                new VcpkgSlangdDiscovery("slangd", "Mac OS X", "aarch64").find(root));
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

    @Test void cachesMissesUntilInvalidatedOrExpired() {
        AtomicInteger scans = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        SlangDiscoveryCache cache = new SlangDiscoveryCache(() -> {
            scans.incrementAndGet(); return Optional.empty();
        }, now::get, 60);
        for (int i = 0; i < 100; i++) assertTrue(cache.get().isEmpty());
        assertEquals(1, scans.get());
        cache.invalidate();
        cache.get();
        assertEquals(2, scans.get());
        now.set(60);
        cache.get();
        assertEquals(3, scans.get());
    }

    @Test void cachesHitsAndFindsNewlyInstalledPackageAfterInvalidation() throws Exception {
        file("vcpkg.json", "{}");
        AtomicInteger scans = new AtomicInteger();
        SlangDiscoveryCache cache = new SlangDiscoveryCache(() -> {
            scans.incrementAndGet(); return discover();
        }, () -> 0, 60);
        assertTrue(cache.get().isEmpty());
        Path tool = executable("vcpkg_installed/x64-linux/tools/shader-slang/slangd");
        assertTrue(cache.get().isEmpty());
        cache.invalidate();
        for (int i = 0; i < 100; i++) assertEquals(Optional.of(tool.toString()), cache.get());
        assertEquals(2, scans.get());
    }
}
