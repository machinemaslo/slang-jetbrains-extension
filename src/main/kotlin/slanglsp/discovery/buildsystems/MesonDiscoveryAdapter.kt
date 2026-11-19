package slanglsp.discovery.buildsystems

import com.google.gson.JsonObject
import java.nio.file.Path
import slanglsp.discovery.DiscoveryContext

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

        val arguments =
            listOf("compile_args", "link_args").flatMap { key ->
                dependency[key].items().mapNotNull { it.literal() }
            }
        DependencyPathDiscovery.paths(context, directory, arguments, mutableSetOf())
    }
}

/** Extracts installed dependency roots from Meson's compiler and linker arguments. */
private object DependencyPathDiscovery {
    private val SEPARATE_PATH_FLAGS = setOf("-I", "-isystem", "-L", "/I", "-imsvc", "/external:I")

    private val PATH_PREFIXES = listOf("-isystem", "/external:I", "-imsvc", "-I", "-L", "/I", "/LIBPATH:")

    fun paths(context: DiscoveryContext, base: Path?, arguments: List<String>, seen: MutableSet<String?>) {
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            var value: String? = null

            if (SEPARATE_PATH_FLAGS.contains(argument)) {
                if (index + 1 < arguments.size) {
                    value = arguments[++index]
                }
            } else {
                value = attachedPath(argument)
            }

            if (value != null && seen.size < 256 && seen.add(base.toString() + "\n" + value)) {
                context.dependencyPath(base, value)
            }
            index++
        }
    }

    private fun attachedPath(argument: String): String? {
        for (prefix in PATH_PREFIXES) {
            if (argument.startsWith(prefix) && argument.length > prefix.length) {
                return argument.substring(prefix.length)
            }
        }
        return null
    }
}
