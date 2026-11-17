package slanglsp.settings;

import com.intellij.openapi.util.SystemInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Installation layouts, not directory names copied from the implementation. */
class BundledClangFormatTest {
    @TempDir
    Path root;

    private static String executableName() {
        return SystemInfo.isWindows ? "clang-format.exe" : "clang-format";
    }

    private static String clionBinary() {
        return "plugins/clion-radler/DotFiles/" + platformBinary();
    }

    private static String platformBinary() {
        String platform = SystemInfo.isWindows ? "windows" : SystemInfo.isMac ? "macos" : "linux";
        String architecture = System.getProperty("os.arch").matches("aarch64|arm64") ? "arm64" : "x64";
        return platform + "-" + architecture + "/" + executableName();
    }

    static Stream<Arguments> installations() {
        return Stream.of(
                Arguments.of("CLion", clionBinary()),
                Arguments.of("CLion", "bin/" + executableName()),
                Arguments.of("Rider", "lib/ReSharperHost/" + platformBinary()),
                Arguments.of("Rider", "plugins/rider/lib/ReSharperHost/" + platformBinary()),
                Arguments.of("Rider", "bin/" + executableName()));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("installations")
    void discoversRealInstallationLayoutsIncludingIssue9(String ide, String relative) throws Exception {
        Path home = root.resolve("JetBrains IDE with spaces");
        Path binary = executable(home.resolve(relative));
        assertEquals(binary.toString(), BundledClangFormat.locate(home, ide),
                "The real Windows distribution uses windows-x64, not win-x64 (issue #9)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLion", "Rider"})
    void missingBinaryDoesNotInventAnExecutable(String ide) {
        assertNull(BundledClangFormat.locate(root, ide));
    }

    @ParameterizedTest
    @ValueSource(strings = {"idea", "Idea", "RustRover"})
    void otherIdesDoNotOfferBundledClangFormat(String ide) throws Exception {
        executable(root.resolve(clionBinary()));
        executable(root.resolve("bin").resolve(executableName()));
        assertNull(BundledClangFormat.locate(root, ide));
    }

    @Test
    void directoriesNamedLikeExecutablesAreNotAccepted() throws Exception {
        Files.createDirectories(root.resolve(clionBinary()));
        assertNull(BundledClangFormat.locate(root, "CLion"));
    }

    @Test
    void bundledToolWinsOverUnrelatedBinTool() throws Exception {
        Path bundled = executable(root.resolve(clionBinary()));
        executable(root.resolve("bin").resolve(executableName()));
        assertEquals(bundled.toString(), BundledClangFormat.locate(root, "CLion"));
    }

    private static Path executable(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "Discovery fixture only; never executed.\n");
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }
}
