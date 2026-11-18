package slanglsp.discovery

import com.google.gson.JsonObject
import java.nio.file.Path

/** Meson's saved introspection replies are usable without invoking build scripts. */
internal class MesonDiscoveryAdapter : DiscoveryAdapter {
    override fun discover(context: DiscoveryContext) {
        if (context.read(context.root.resolve("meson.build")).isBlank()) {
            return
        }

        context.buildDirectory(context.root, "builddir")
        for (directory in context.buildDirectories) {
            val data = context.json(directory.resolve("meson-info/intro-dependencies.json"))
            for (dependency in data.items().filterIsInstance<JsonObject>()) {
                readDependency(context, directory, dependency)
            }
        }
    }

    private fun readDependency(context: DiscoveryContext, directory: Path, dependency: JsonObject) {
        for (path in dependency["include_directories"].items()) {
            path.literal()?.let { context.dependencyPath(directory, it) }
        }

        val arguments = listOf("compile_args", "link_args").flatMap { key ->
            dependency[key].items().mapNotNull { it.literal() }
        }
        DependencyPathDiscovery.paths(context, directory, arguments, mutableSetOf())
    }
}
