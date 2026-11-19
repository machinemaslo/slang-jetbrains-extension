package slanglsp.discovery.buildsystems

import java.nio.file.Path
import slanglsp.discovery.DiscoveryContext

/** Reads Xmake's serialized config without loading or executing Lua. */
internal class XmakeDiscoveryAdapter : DiscoveryAdapter {
    override fun discover(context: DiscoveryContext) {
        if (context.read(context.root.resolve("xmake.lua")).isBlank()) {
            return
        }

        val configRoot = context.resolve(context.root, context.environment["XMAKE_CONFIGDIR"]) ?: context.root
        // Only Xmake's host/architecture config locations, not arbitrary project directories.
        val configurations =
            context
                .children(configRoot.resolve(".xmake"), "*")
                .asSequence()
                .flatMap { host -> context.children(host, "*").asSequence() }
                .take(16)
        for (architecture in configurations) {
            readConfig(context, architecture.resolve("xmake.conf"))
        }

        val globalDirectory = context.environment["XMAKE_GLOBALDIR"] ?: System.getProperty("user.home")
        context.resolve(context.root, globalDirectory)?.let { global ->
            readConfig(context, global.resolve(".xmake/xmake.conf"))
        }
    }

    private fun readConfig(context: DiscoveryContext, file: Path) {
        val text = context.read(file)
        for ((key, setting) in settings) {
            val match = setting.find(text) ?: continue
            val value = match.groupValues[1].replace("\\\\", "\\")
            if (key == "vcpkg") {
                context.vcpkgRoot(context.root, value)
            } else {
                context.buildDirectory(context.root, value)
            }
        }
    }

    private companion object {
        val settings =
            listOf("vcpkg", "buildir", "builddir").associateWith { key ->
                Regex("""(?m)^\s*(?:$key|\["$key"])\s*=\s*"((?:\\.|[^"\\])*)"\s*,?\s*$""")
            }
    }
}
