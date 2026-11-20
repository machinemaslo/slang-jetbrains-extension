package slanglsp.discovery.buildsystems

import com.google.gson.JsonObject
import java.nio.file.Path
import slanglsp.discovery.DiscoveryContext

/** Meson's saved introspection replies are usable without invoking build scripts. */
internal class MesonDiscoveryAdapter : DiscoveryAdapter(projectMarker = "meson.build") {
    override fun discoverMetadata(context: DiscoveryContext) {
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

        val arguments =
            listOf("compile_args", "link_args").flatMap { key ->
                dependency[key].items().mapNotNull { it.literal() }
            }
        DependencyPathDiscovery.registerPaths(context, directory, arguments)
    }
}

/** Extracts installed dependency roots from Meson's compiler and linker arguments. */
private object DependencyPathDiscovery {
    private val SEPARATE_PATH_FLAGS = setOf("-I", "-isystem", "-L", "/I", "-imsvc", "/external:I")

    private val PATH_PREFIXES = listOf("-isystem", "/external:I", "-imsvc", "-I", "-L", "/I", "/LIBPATH:")

    fun registerPaths(context: DiscoveryContext, base: Path, arguments: List<String>) {
        val seen = mutableSetOf<String>()
        var index = 0

        while (index < arguments.size) {
            val argument = arguments[index++]
            val value =
                if (argument in SEPARATE_PATH_FLAGS) {
                    arguments.getOrNull(index++)
                } else {
                    attachedPath(argument)
                }

            if (value != null && seen.size < 256 && seen.add(value)) {
                context.dependencyPath(base, value)
            }
        }
    }

    private fun attachedPath(argument: String): String? {
        return PATH_PREFIXES
            .firstOrNull { argument.startsWith(it) && argument.length > it.length }
            ?.let { argument.removePrefix(it) }
    }
}
