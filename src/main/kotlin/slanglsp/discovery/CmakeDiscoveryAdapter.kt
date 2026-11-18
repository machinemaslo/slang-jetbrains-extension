package slanglsp.discovery

import com.google.gson.JsonObject
import java.nio.file.Path

internal class CmakeDiscoveryAdapter : DiscoveryAdapter {
    override fun discover(context: DiscoveryContext) {
        val presets = PresetCatalog(context).visiblePresets()
        findBuildDirectories(context, presets)
        readCaches(context)
        // Resolved caches take precedence over initial preset values.
        for (preset in presets) {
            readPresetInstallation(context, preset)
        }
    }

    private fun findBuildDirectories(context: DiscoveryContext, presets: List<Preset>) {
        for (preset in presets) {
            context.buildDirectory(context.root, preset.binaryDir)
        }

        val directories = readIdeProfiles(context)
        directories.addAll(context.buildDirectories)
        context.buildDirectories.clear()
        context.buildDirectories.addAll(directories.take(32))

        // Top-level build directories only; never a recursive project scan.
        for (directory in context.children(context.root, "cmake-build-*")) {
            context.buildDirectory(context.root, directory.toString())
        }
    }

    private fun readIdeProfiles(context: DiscoveryContext): MutableSet<Path> = linkedSetOf<Path>().apply {
        for (file in listOf(".idea/workspace.xml", ".idea/cmake.xml")) {
            val document = context.xml(context.root.resolve(file)) ?: continue
            for (profile in document.getElementsByTagName("configuration").elements().take(32)) {
                if (profile.getAttribute("ENABLED").equals("false", ignoreCase = true)) {
                    continue
                }

                val directory = profile.getAttribute("GENERATION_DIR").ifBlank {
                    profile.getAttribute("generationDir")
                }
                val path = directory.replace("\$PROJECT_DIR\$", context.root.toString())
                context.resolve(context.root, path)?.let(::add)
            }
        }
    }

    private fun readCaches(context: DiscoveryContext) {
        for (directory in context.buildDirectories.toList()) {
            val cache = buildMap {
                for (line in cacheLineBreak.splitToSequence(context.read(directory.resolve("CMakeCache.txt")))) {
                    val colon = line.indexOf(':')
                    val equals = line.indexOf('=')
                    if (colon > 0 && equals > colon) {
                        put(line.substring(0, colon), line.substring(equals + 1))
                    }
                }
            }

            context.variables(directory, cache)
            if (cache.isNotEmpty()) {
                context.installed(directory, "vcpkg_installed", cache["VCPKG_HOST_TRIPLET"])
            }
        }
    }

    private fun readPresetInstallation(context: DiscoveryContext, preset: Preset) {
        val values = preset.installationVariables()
        val build = context.resolve(context.root, preset.binaryDir)
        context.variables(build ?: context.root, values)
        if (build != null) {
            context.installed(build, "vcpkg_installed", values["VCPKG_HOST_TRIPLET"])
        }
    }

    private class PresetCatalog(private val context: DiscoveryContext) {
        private val files = mutableSetOf<Path>()
        private val definitions = linkedMapOf<String, JsonObject>()

        fun visiblePresets(): List<Preset> {
            load(context.root.resolve("CMakePresets.json"))
            load(context.root.resolve("CMakeUserPresets.json"))
            return definitions.keys.mapNotNull { name ->
                val merged = merged(name, mutableSetOf())
                if (merged["hidden"].literal() == "true") null else Preset(context, name, merged)
            }
        }

        private fun load(path: Path) {
            val file = path.toAbsolutePath().normalize()
            if (files.size >= 16 || !files.add(file)) {
                return
            }

            val document = context.json(file) as? JsonObject ?: return
            val expansion = PresetExpansion(context, "", null)
            for (entry in document["include"].items()) {
                val include = context.resolve(file.parent, expansion.expand(entry.literal())) ?: continue
                load(include)
            }

            for (element in document["configurePresets"].items()) {
                if (definitions.size >= 32) {
                    break
                }
                val definition = element as? JsonObject ?: continue
                val name = definition["name"].literal() ?: continue
                definitions[name] = definition
            }
        }

        private fun merged(name: String, visiting: MutableSet<String>): JsonObject {
            val own = definitions[name]
            if (own == null || visiting.size >= 16 || !visiting.add(name)) {
                return JsonObject()
            }

            val result = JsonObject()
            // The first parent wins, so merge parents from lowest to highest precedence.
            for (parent in own["inherits"].items().asReversed()) {
                parent.literal()?.let { result.overlay(merged(it, visiting)) }
            }
            result.overlay(own)
            // Hidden presets can be used as bases, but hidden is not inherited.
            if (!own.has("hidden")) {
                result.remove("hidden")
            }
            visiting.remove(name)
            return result
        }
    }

    private class Preset(context: DiscoveryContext, name: String, private val definition: JsonObject) {
        private val expansion = PresetExpansion(context, name, definition["environment"] as? JsonObject)
        val binaryDir = expansion.expand(definition["binaryDir"].literal())

        fun installationVariables(): Map<String, String> = buildMap {
            val variables = definition["cacheVariables"] as? JsonObject
            for ((key, entry) in variables?.entrySet().orEmpty()) {
                val value = if (entry is JsonObject) entry["value"] else entry
                expansion.expand(value.literal())?.let { put(key, it) }
            }
            expansion.expand(definition["toolchainFile"].literal())?.let { put("CMAKE_TOOLCHAIN_FILE", it) }
        }
    }

    private class PresetExpansion(context: DiscoveryContext, name: String, overrides: JsonObject?) {
        private val parentEnvironment = context.environment
        private val environment = buildMap {
            putAll(parentEnvironment)
            for ((key, entry) in overrides?.entrySet().orEmpty()) {
                entry.literal()?.let { put(key, it) }
            }
        }
        private val macros = buildMap {
            put("sourceDir", context.root.toString())
            context.root.parent?.let { put("sourceParentDir", it.toString()) }
            context.root.fileName?.let { put("sourceDirName", it.toString()) }
            put("presetName", name)
        }

        fun expand(value: String?): String? {
            var expanded = value ?: return null
            repeat(8) {
                val next = environmentMacro.replace(expanded) { match ->
                    val (kind, name) = match.destructured
                    val source = if (kind == "penv") parentEnvironment else environment
                    source[name] ?: match.value
                }
                if (next.length > 8192) {
                    return null
                }
                if (next == expanded) {
                    return DiscoveryContext.expand(expanded, macros)
                }
                expanded = next
            }
            return DiscoveryContext.expand(expanded, macros)
        }
    }

    private companion object {
        val cacheLineBreak = Regex("\\R")
        val environmentMacro = Regex("""\$(p?env)\{([^}]+)}""")

        fun JsonObject.overlay(source: JsonObject) {
            for ((key, value) in source.entrySet()) {
                val previous = this[key]
                if (previous is JsonObject && value is JsonObject) {
                    previous.overlay(value)
                } else {
                    add(key, value.deepCopy())
                }
            }
        }
    }
}
