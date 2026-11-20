package slanglsp.discovery

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.isExecutable as hasExecutePermission
import kotlin.io.path.isRegularFile
import slanglsp.settings.project.ProjectSettings

object ExecutableLocator {
    internal val slangdName: String
        get() = if (SystemInfo.isWindows) "slangd.exe" else "slangd"

    internal fun findSlangd(project: Project, settings: ProjectSettings.State): String? {
        return find(
            explicitDirectory = settings.explicitSlangdLocation,
            useVcpkg = settings.useLocalVcpkgSlangd,
            vcpkg = { project.service<VcpkgDiscoveryService>().findExecutable() },
            path = EnvironmentUtil.getValue("PATH"),
            vulkanSdk = EnvironmentUtil.getValue("VULKAN_SDK"),
            executableName = slangdName,
            projectBasePath = project.basePath,
        )
    }

    fun find(
        explicitDirectory: String?,
        useVcpkg: Boolean,
        vcpkg: () -> String?,
        path: String?,
        vulkanSdk: String?,
        executableName: String,
        projectBasePath: String? = null,
    ): String? {
        val explicit =
            explicitDirectory?.takeUnless(String::isBlankPath)?.let {
                resolveAgainstProject(it, projectBasePath)
            }

        inDirectory(explicit, executableName)?.let {
            return it.toString()
        }

        if (useVcpkg) {
            vcpkg()?.let {
                return it
            }
        }

        val windowsExecutable = executableName.endsWith(".exe")
        val sdkBin = if (windowsExecutable) "Bin" else "bin"

        inDirectory(vulkanSdk, "$sdkBin/$executableName")?.let {
            return it.toString()
        }

        val entries = path?.split(File.pathSeparatorChar).orEmpty()
        val direct = entries.firstNotNullOfOrNull { inDirectory(it, executableName) }

        if (direct != null) {
            return direct.toString()
        }

        // A PATH entry can expose only the compiler symlink from a full SDK.
        val compilerName = if (windowsExecutable) "slangc.exe" else "slangc"
        val alongsideCompiler = entries.firstNotNullOfOrNull { entry ->
            val compiler = inDirectory(entry, compilerName) ?: return@firstNotNullOfOrNull null
            inDirectory(realParent(compiler)?.toString(), executableName)
        }

        return alongsideCompiler?.toString()
    }

    private fun resolveAgainstProject(directory: String, projectBasePath: String?): String {
        if (projectBasePath == null) {
            return directory
        }

        return try {
            Path(projectBasePath).resolve(directory).normalize().toString()
        } catch (_: InvalidPathException) {
            directory
        }
    }

    private fun inDirectory(directory: String?, executableName: String): Path? {
        if (directory == null || directory.isBlankPath()) {
            return null
        }

        return try {
            Path(directory).resolve(executableName).takeIf(::isExecutable)?.absolute()
        } catch (_: InvalidPathException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun isExecutable(executable: Path): Boolean {
        return executable.isRegularFile() && executable.hasExecutePermission()
    }
}

private fun String.isBlankPath() = all(Character::isWhitespace)

/** Broken or inaccessible links must not block later discovery candidates. */
internal fun realParent(executable: Path): Path? {
    return try {
        executable.toRealPath().parent
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
