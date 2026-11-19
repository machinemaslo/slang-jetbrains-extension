import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GenerateClangFormatPortTest {
    @Test
    fun selectsExactVersionAndHostRatherThanAnotherPlatform() {
        val expected = asset("23.1.5", "manylinux_2_27_x86_64.manylinux_2_28_x86_64")
        val assets =
            listOf(
                asset("23.1.4", "manylinux_2_27_x86_64"),
                asset("23.1.5", "musllinux_1_2_x86_64"),
                asset("23.1.5", "macosx_10_9_x86_64"),
                asset("23.1.5", "manylinux_2_26_aarch64"),
                expected)

        assertEquals(expected, GenerateClangFormatPort.selectAsset(assets, "x64-linux", "23.1.5"))
    }

    @Test
    fun selectsWindowsAndMacArchitectures() {
        val platforms =
            mapOf(
                "x64-windows" to "win_amd64",
                "arm64-windows" to "win_arm64",
                "x64-osx" to "macosx_10_9_x86_64",
                "arm64-osx" to "macosx_11_0_arm64",
                "arm64-linux" to "manylinux_2_26_aarch64")
        for ((platform, filenameTag) in platforms) {
            val expected = asset("23.1.5", filenameTag)
            assertEquals(expected, GenerateClangFormatPort.selectAsset(listOf(expected), platform, "23.1.5"))
        }
    }

    @Test
    fun refusesMissingAmbiguousAndYankedBinaries() {
        val binary = asset("23.1.5", "win_amd64")

        assertThrows(GradleException::class.java) {
            GenerateClangFormatPort.selectAsset(listOf(binary), "x64-windows", "23.1.6")
        }
        assertThrows(GradleException::class.java) {
            GenerateClangFormatPort.selectAsset(listOf(binary, binary), "x64-windows", "23.1.5")
        }
        assertThrows(GradleException::class.java) {
            GenerateClangFormatPort.selectAsset(listOf(binary + ("yanked" to true)), "x64-windows", "23.1.5")
        }
    }

    private fun asset(version: String, platform: String): Map<String, Any> =
        mapOf("filename" to "clang_format-$version-py2.py3-none-$platform.whl", "yanked" to false)
}
