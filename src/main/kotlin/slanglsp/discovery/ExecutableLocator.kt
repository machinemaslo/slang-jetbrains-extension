package slanglsp.discovery

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Optional
import java.util.function.Supplier

object ExecutableLocator {
    @JvmStatic
    @JvmOverloads
    fun find(
        explicitDirectory: String?,
        useVcpkg: Boolean,
        vcpkg: Supplier<Optional<String>>,
        path: String?,
        vulkanSdk: String?,
        executableName: String,
        projectBasePath: String? = null,
    ): Optional<String> {
        val explicit = explicitDirectory?.takeUnless(String::isBlankPath)?.let {
            resolveAgainstProject(it, projectBasePath)
        }
        inDirectory(explicit, executableName)?.let { return Optional.of(it.toString()) }

        if (useVcpkg) {
            val local = vcpkg.get()
            if (local.isPresent) {
                return local
            }
        }

        val windowsExecutable = executableName.endsWith(".exe")
        val sdkBin = if (windowsExecutable) "Bin" else "bin"
        inDirectory(vulkanSdk, "$sdkBin/$executableName")?.let { return Optional.of(it.toString()) }

        val entries = path?.split(File.pathSeparatorChar).orEmpty()
        val direct = entries.firstNotNullOfOrNull { inDirectory(it, executableName) }
        if (direct != null) {
            return Optional.of(direct.toString())
        }

        // A PATH entry can expose only the compiler symlink from a full SDK.
        val compilerName = if (windowsExecutable) "slangc.exe" else "slangc"
        val alongsideCompiler = entries.firstNotNullOfOrNull { entry ->
            val compiler = inDirectory(entry, compilerName) ?: return@firstNotNullOfOrNull null
            inDirectory(realParent(compiler)?.toString(), executableName)
        }
        return Optional.ofNullable(alongsideCompiler?.toString())
    }

    private fun resolveAgainstProject(directory: String, projectBasePath: String?): String {
        if (projectBasePath == null) {
            return directory
        }

        return try {
            Path.of(projectBasePath).resolve(directory).normalize().toString()
        } catch (_: InvalidPathException) {
            directory
        }
    }

    private fun inDirectory(directory: String?, executableName: String): Path? {
        if (directory == null || directory.isBlankPath()) {
            return null
        }

        return try {
            Path.of(directory).resolve(executableName).takeIf(::isExecutable)?.toAbsolutePath()
        } catch (_: InvalidPathException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    @JvmStatic
    fun isExecutable(executable: Path): Boolean =
        Files.isRegularFile(executable) && Files.isExecutable(executable)
}

private fun String.isBlankPath() = all(Character::isWhitespace)

/** Broken or inaccessible links must not block later discovery candidates. */
internal fun realParent(executable: Path): Path? = try {
    executable.toRealPath().parent
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}
