package slanglsp.formatting.clang

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path

/** Finds the binary without depending on CLion/Rider's proprietary plugin APIs. */
object BundledClangFormat {
    @JvmStatic
    fun executable(): String? {
        return Holder.EXECUTABLE
    }

    @JvmStatic
    fun locate(home: Path, productCode: String): String? {
        val roots =
            when (productCode) {
                "CL" -> mutableListOf("plugins/clion-radler/DotFiles", "bin")
                "RD" -> mutableListOf("lib/ReSharperHost", "plugins/rider/lib/ReSharperHost", "bin")
                else -> mutableListOf()
            }
        val name = if (SystemInfo.isWindows) "clang-format.exe" else "clang-format"
        val os = if (SystemInfo.isWindows) "windows" else if (SystemInfo.isMac) "macos" else "linux"
        val arch = if (System.getProperty("os.arch").matches("aarch64|arm64".toRegex())) "arm64" else "x64"
        for (root in roots) {
            for (suffix in listOf("$os-$arch/$name", name)) {
                val candidate = home.resolve(root).resolve(suffix)
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toString()
                }
            }
        }
        return null
    }

    private object Holder {
        val EXECUTABLE: String? =
            locate(Path.of(PathManager.getHomePath()), ApplicationInfo.getInstance().build.productCode)
    }
}
