package slanglsp.settings;

import com.intellij.openapi.util.SystemInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Installation layouts, not directory names copied from the implementation. */
class BundledClangFormatTest {
    @TempDir
    Path root;

    static Stream<String> installations() {
        String platform = SystemInfo.isWindows ? "windows" : SystemInfo.isMac ? "macos" : "linux";
        String architecture = System.getProperty("os.arch").matches("aarch64|arm64") ? "arm64" : "x64";
        String executable = SystemInfo.isWindows ? "clang-format.exe" : "clang-format";
        return Stream.of(
                "plugins/clion-radler/DotFiles/" + platform + "-" + architecture + "/" + executable,
                "lib/ReSharperHost/" + platform + "-" + architecture + "/" + executable,
                "plugins/rider/lib/ReSharperHost/" + platform + "-" + architecture + "/" + executable,
                "bin/" + executable);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("installations")
    void discoversRealInstallationLayoutsIncludingIssue9(String relative) throws Exception {
        Path home = root.resolve("JetBrains IDE with spaces");
        Path binary = executable(home.resolve(relative));
        assertEquals(binary.toString(), BundledClangFormat.locate(home),
                "The real Windows distribution uses windows-x64, not win-x64 (issue #9)");
    }

    @Test
    void missingBinaryDoesNotInventAnExecutable() {
        assertNull(BundledClangFormat.locate(root));
    }

    @Test
    void directoriesNamedLikeExecutablesAreNotAccepted() throws Exception {
        String name = SystemInfo.isWindows ? "clang-format.exe" : "clang-format";
        Files.createDirectories(root.resolve("bin").resolve(name));
        assertNull(BundledClangFormat.locate(root));
    }

    @Test
    void bundledToolWinsOverUnrelatedBinTool() throws Exception {
        String relative = installations().findFirst().orElseThrow();
        Path bundled = executable(root.resolve(relative));
        executable(root.resolve("bin").resolve(SystemInfo.isWindows ? "clang-format.exe" : "clang-format"));
        assertEquals(bundled.toString(), BundledClangFormat.locate(root));
    }

    private static Path executable(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "Discovery fixture only; never executed.\n");
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }
}
