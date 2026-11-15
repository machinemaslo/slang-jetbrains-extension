import org.gradle.api.GradleException;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class GenerateClangFormatPortTest {
    private static Map<String, Object> asset(String version, String platform) {
        return Map.of("filename", "clang_format-" + version + "-py2.py3-none-" + platform + ".whl",
                "yanked", false);
    }

    @Test
    public void selectsExactVersionAndHostRatherThanAnotherPlatform() {
        var expected = asset("23.1.5", "manylinux_2_27_x86_64.manylinux_2_28_x86_64");
        var assets = List.of(
                asset("23.1.4", "manylinux_2_27_x86_64"),
                asset("23.1.5", "musllinux_1_2_x86_64"),
                asset("23.1.5", "macosx_10_9_x86_64"),
                asset("23.1.5", "manylinux_2_26_aarch64"),
                expected);
        assertEquals(expected, GenerateClangFormatPort.selectAsset(assets, "x64-linux", "23.1.5"));
    }

    @Test
    public void selectsWindowsAndMacArchitectures() {
        for (var platform : Map.of(
                "x64-windows", "win_amd64", "arm64-windows", "win_arm64",
                "x64-osx", "macosx_10_9_x86_64", "arm64-osx", "macosx_11_0_arm64",
                "arm64-linux", "manylinux_2_26_aarch64").entrySet()) {
            var expected = asset("23.1.5", platform.getValue());
            assertEquals(expected, GenerateClangFormatPort.selectAsset(
                    List.of(expected), platform.getKey(), "23.1.5"));
        }
    }

    @Test
    public void refusesMissingAmbiguousAndYankedBinaries() {
        var binary = asset("23.1.5", "win_amd64");
        assertThrows(GradleException.class, () -> GenerateClangFormatPort.selectAsset(
                List.of(binary), "x64-windows", "23.1.6"));
        assertThrows(GradleException.class, () -> GenerateClangFormatPort.selectAsset(
                List.of(binary, binary), "x64-windows", "23.1.5"));
        assertThrows(GradleException.class, () -> GenerateClangFormatPort.selectAsset(
                List.of(Map.of("filename", binary.get("filename"), "yanked", true)), "x64-windows", "23.1.5"));
    }
}
