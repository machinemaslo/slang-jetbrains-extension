package slanglsp.formatting.clang

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/** Finds the binary without depending on CLion/Rider's proprietary plugin APIs. */
object BundledClangFormat {
    fun executable(): String? {
        return Holder.EXECUTABLE
    }

    fun locate(home: Path, productCode: String): String? {
        val roots =
            when (productCode) {
                "CL" -> listOf("plugins/clion-radler/DotFiles", "bin")
                "RD" -> listOf("lib/ReSharperHost", "plugins/rider/lib/ReSharperHost", "bin")
                else -> return null
            }

        val name = if (SystemInfo.isWindows) "clang-format.exe" else "clang-format"
        val os = when {
            SystemInfo.isWindows -> "windows"
            SystemInfo.isMac -> "macos"
            else -> "linux"
        }
        val arch = when (SystemInfo.OS_ARCH) {
            "aarch64", "arm64" -> "arm64"
            else -> "x64"
        }
        val suffixes = listOf("$os-$arch/$name", name)

        return roots.firstNotNullOfOrNull { root ->
            suffixes.firstNotNullOfOrNull { suffix ->
                val candidate = home.resolve(root).resolve(suffix)
                candidate.takeIf { it.isRegularFile() && it.isExecutable() }?.toString()
            }
        }
    }

    private object Holder {
        val EXECUTABLE: String? =
            locate(Path(PathManager.getHomePath()), ApplicationInfo.getInstance().build.productCode)
    }
}
