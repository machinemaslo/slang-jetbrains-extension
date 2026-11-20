package slanglsp.discovery.buildsystems

import slanglsp.discovery.DiscoveryContext

/** Collects candidates from saved build metadata in priority order. */
internal object BuildSystemDiscovery {
    fun discover(context: DiscoveryContext) {
        val adapters =
            listOf(
                CmakeDiscoveryAdapter(),
                MsbuildDiscoveryAdapter(),
                XmakeDiscoveryAdapter(),
                MesonDiscoveryAdapter(),
            )

        adapters.forEach { it.discover(context) }
    }
}
