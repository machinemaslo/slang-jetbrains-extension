package slanglsp.settings;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.PlatformUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Finds the binary without depending on CLion/Rider's proprietary plugin APIs. */
public final class BundledClangFormat {
    private BundledClangFormat() {
    }

    private static final class Holder {
        private static final String EXECUTABLE = locate(Path.of(PathManager.getHomePath()), PlatformUtils.getPlatformPrefix());
    }

    public static String executable() {
        return Holder.EXECUTABLE;
    }

    static String locate(Path home, String platformPrefix) {
        List<String> roots = switch (platformPrefix) {
            case "CLion" -> List.of("plugins/clion-radler/DotFiles", "bin");
            case "Rider" -> List.of("lib/ReSharperHost", "plugins/rider/lib/ReSharperHost", "bin");
            default -> List.of();
        };
        String name = SystemInfo.isWindows ? "clang-format.exe" : "clang-format";
        String os = SystemInfo.isWindows ? "windows" : SystemInfo.isMac ? "macos" : "linux";
        String arch = System.getProperty("os.arch").matches("aarch64|arm64") ? "arm64" : "x64";
        for (String root : roots) {
            for (String suffix : List.of(os + "-" + arch + "/" + name, name)) {
                Path candidate = home.resolve(root).resolve(suffix);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }
}
