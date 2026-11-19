package slanglsp.discovery

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.streams.asSequence
import slanglsp.discovery.buildsystems.BuildSystemDiscovery

/** Bounded, read-only discovery. Never invokes vcpkg or descends into installed packages. */
internal class VcpkgDiscovery(private val executableName: String, os: String, arch: String) {
    private val hostTriplet = "${hostCpu(arch)}-${hostSystem(os)}"

    @JvmOverloads
    fun find(projectRoot: Path, environment: Map<String, String> = emptyMap()): String? =
        findTool(DiscoveryContext(projectRoot, environment))?.toString()

    private fun findTool(context: DiscoveryContext): Path? {
        // Prefer the project's conventional installations before reading build metadata.
        val configuredHost = context.environment["VCPKG_HOST_TRIPLET"]
        conventionalInstallations
            .firstNotNullOfOrNull { directory ->
                findInstalledTool(context.root.resolve(directory), configuredHost)
            }
            ?.let {
                return it
            }

        BuildSystemDiscovery.discover(context)
        findFallbackInstallations(context)

        return context.installations.entries.firstNotNullOfOrNull { (root, host) ->
            findInstalledTool(root, host)
        }
    }

    private fun findFallbackInstallations(context: DiscoveryContext) {
        findSubmodules(context)
        context.variables(context.root, context.environment)
        findOnPath(context)
        val localAppData = context.resolve(context.root, context.environment["LOCALAPPDATA"])
        if (localAppData != null) {
            val root = context.read(localAppData.resolve("vcpkg/vcpkg.path.txt")).trim { it <= ' ' }
            context.vcpkgRoot(context.root, root)
        }
    }

    private fun findSubmodules(context: DiscoveryContext) {
        // Explicit submodules and environment are fallbacks after build-system metadata.
        for (match in submodulePath.findAll(context.read(context.root.resolve(".gitmodules")))) {
            val path = context.resolve(context.root, match.groupValues[1]) ?: continue
            if (Files.isRegularFile(path.resolve(".vcpkg-root"))) {
                context.vcpkgRoot(context.root, path.toString())
            }
        }
    }

    private fun findOnPath(context: DiscoveryContext) {
        val path = context.environment["PATH"] ?: return
        val vcpkgExecutable = if (executableName.endsWith(".exe")) "vcpkg.exe" else "vcpkg"
        for (entry in path.splitToSequence(File.pathSeparatorChar).take(128)) {
            val directory = context.resolve(context.root, entry) ?: continue
            val executable = directory.resolve(vcpkgExecutable)
            if (ExecutableLocator.isExecutable(executable)) {
                realParent(executable)?.let { context.vcpkgRoot(context.root, it.toString()) }
            }
        }
    }

    private fun findInstalledTool(installed: Path, configuredHost: String?): Path? {
        // Host tools must not come from the target triplet when cross-compiling.
        val triplets = buildSet {
            configuredHost?.takeIf(validTriplet::matches)?.let(::add)
            add(hostTriplet)
            addAll(hostVariants(installed))
        }

        return triplets.firstNotNullOfOrNull { triplet ->
            ports.firstNotNullOfOrNull { port ->
                installed
                    .resolve(triplet)
                    .resolve("tools")
                    .resolve(port)
                    .resolve(executableName)
                    .takeIf(ExecutableLocator::isExecutable)
                    ?.toAbsolutePath()
            }
        }
    }

    private fun hostVariants(installed: Path): List<String> =
        try {
            Files.list(installed).use { children ->
                children
                    .asSequence()
                    .take(256)
                    .map { it.fileName.toString() }
                    .filter { it.startsWith("$hostTriplet-") }
                    .sorted()
                    .toList()
            }
        } catch (_: IOException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

    private companion object {
        val conventionalInstallations = listOf("vcpkg_installed", "vcpkg/installed", "extern/vcpkg/installed")
        // shader-slang is the official port; slang supports existing overlay ports.
        val ports = listOf("shader-slang", "slang")
        val validTriplet = Regex("[A-Za-z0-9_-]+")
        val submodulePath = Regex("""(?m)^\s*path\s*=\s*(.+?)\s*$""")

        fun hostCpu(arch: String): String =
            when (val cpu = arch.lowercase(Locale.ROOT)) {
                "amd64",
                "x86_64" -> "x64"
                "aarch64",
                "arm64" -> "arm64"
                "x86",
                "i386",
                "i686" -> "x86"
                else -> cpu
            }

        fun hostSystem(os: String): String {
            val system = os.lowercase(Locale.ROOT)
            return when {
                "win" in system && "darwin" !in system -> "windows"
                "mac" in system || "darwin" in system -> "osx"
                else -> "linux"
            }
        }
    }
}
