package slanglsp.formatting.clang

import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import slanglsp.formatting.clang.BundledClangFormat.locate

/** Installation layouts, not directory names copied from the implementation. */
internal class BundledClangFormatTest {
    @TempDir var root: Path? = null

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("installations")
    @Throws(Exception::class)
    fun discoversRealInstallationLayoutsIncludingIssue9(ide: String, relative: String) {
        val home = root!!.resolve("JetBrains IDE with spaces")
        val binary: Path = executable(home.resolve(relative))
        Assertions.assertEquals(
            binary.toString(),
            locate(home, ide),
            "The real Windows distribution uses windows-x64, not win-x64 (issue #9)")
    }

    @ParameterizedTest
    @ValueSource(strings = ["CL", "RD"])
    fun missingBinaryDoesNotInventAnExecutable(ide: String) {
        Assertions.assertNull(locate(root!!, ide))
    }

    @ParameterizedTest
    @ValueSource(strings = ["IU", "IC", "RR"])
    @Throws(Exception::class)
    fun otherIdesDoNotOfferBundledClangFormat(ide: String) {
        executable(root!!.resolve(clionBinary()))
        executable(root!!.resolve("bin").resolve(executableName()))
        Assertions.assertNull(locate(root!!, ide))
    }

    @Test
    @Throws(Exception::class)
    fun directoriesNamedLikeExecutablesAreNotAccepted() {
        Files.createDirectories(root!!.resolve(clionBinary()))
        Assertions.assertNull(locate(root!!, "CL"))
    }

    @Test
    @Throws(Exception::class)
    fun bundledToolWinsOverUnrelatedBinTool() {
        val bundled: Path = executable(root!!.resolve(clionBinary()))
        executable(root!!.resolve("bin").resolve(executableName()))
        Assertions.assertEquals(bundled.toString(), locate(root!!, "CL"))
    }

    companion object {
        private fun executableName(): String {
            return if (SystemInfo.isWindows) "clang-format.exe" else "clang-format"
        }

        private fun clionBinary(): String {
            return "plugins/clion-radler/DotFiles/" + platformBinary()
        }

        private fun platformBinary(): String {
            val platform = if (SystemInfo.isWindows) "windows" else if (SystemInfo.isMac) "macos" else "linux"
            val architecture = if (System.getProperty("os.arch").matches("aarch64|arm64".toRegex())) "arm64" else "x64"
            return platform + "-" + architecture + "/" + executableName()
        }

        @JvmStatic
        fun installations(): Stream<Arguments?> {
            return Stream.of(
                Arguments.of("CL", clionBinary()),
                Arguments.of("CL", "bin/" + executableName()),
                Arguments.of("RD", "lib/ReSharperHost/" + platformBinary()),
                Arguments.of("RD", "plugins/rider/lib/ReSharperHost/" + platformBinary()),
                Arguments.of("RD", "bin/" + executableName()))
        }

        @Throws(Exception::class)
        private fun executable(file: Path): Path {
            Files.createDirectories(file.parent)
            Files.writeString(file, "Discovery fixture only; never executed.\n")
            Assertions.assertTrue(file.toFile().setExecutable(true))
            return file
        }
    }
}
