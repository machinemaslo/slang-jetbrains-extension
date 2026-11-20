package slanglsp.discovery.buildsystems

import java.nio.file.Path
import slanglsp.discovery.DiscoveryContext

/** Reads saved build metadata without configuring, installing, or building anything. */
internal abstract class DiscoveryAdapter(private val projectMarker: String? = null) {
    fun discover(context: DiscoveryContext) {
        if (projectMarker != null && context.read(context.root.resolve(projectMarker)).isBlank()) {
            return
        }

        discoverMetadata(context)
    }

    protected abstract fun discoverMetadata(context: DiscoveryContext)

    protected fun DiscoveryContext.registerDefaultVcpkgInstallation(directory: Path, hostTriplet: String?) {
        installed(directory, "vcpkg_installed", hostTriplet)
    }
}
