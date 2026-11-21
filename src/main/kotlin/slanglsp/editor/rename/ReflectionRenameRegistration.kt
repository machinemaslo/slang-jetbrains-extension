package slanglsp.editor.rename

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.util.SystemInfo
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import slanglsp.editor.navigation.cpp.decodeMetadataText
import slanglsp.editor.navigation.cpp.isReflectionPath

/** Explicit project-authored generation requests. Reflection packs remain passive data. */
internal class ReflectionRenameRegistration private constructor(tasks: List<GenerationTask>) {
    private val tasksByInput = buildMap<Path, MutableList<GenerationTask>> {
        for (task in tasks) {
            for (input in task.inputs) {
                getOrPut(input) { mutableListOf() }.add(task)
            }
        }
    }

    fun affected(paths: Collection<Path>): Map<GenerationTask, Set<Path>> =
        buildMap<GenerationTask, MutableSet<Path>> {
            for (path in paths) {
                for (task in tasksByInput[path].orEmpty()) {
                    getOrPut(task) { linkedSetOf() }.add(path)
                }
            }
        }

    class GenerationTask(
        val entry: Entry,
        val configuration: String,
        val target: String,
        val tool: Path?,
        val inputs: Set<Path>,
    ) {
        val executableName: String
            get() = when (entry) {
                is Entry.CMake -> if (SystemInfo.isWindows) "cmake.exe" else "cmake"
                is Entry.MsBuild -> "MSBuild.exe"
            }

        fun arguments(): List<String> = when (entry) {
            is Entry.CMake -> buildList {
                addAll(listOf("--build", entry.directory.toString(), "--target", target))
                if (configuration.isNotEmpty()) {
                    addAll(listOf("--config", configuration))
                }
            }
            is Entry.MsBuild -> msBuildArguments(entry) + "/target:$target"
        }

        fun evaluationArguments(): List<String> {
            val msBuild = entry as? Entry.MsBuild ?: error("Expected an MSBuild project")
            return msBuildArguments(msBuild) + "/getProperty:Configuration,Platform,IntDir,JbSlangLspReflectionTarget"
        }

        private fun msBuildArguments(entry: Entry.MsBuild): List<String> = buildList {
            addAll(listOf(
                entry.project.toString(), "/nologo", "/noautoresponse", "/nodeReuse:false", "/verbosity:minimal",
                "/property:Configuration=$configuration", "/property:Platform=${entry.platform}",
            ))
            entry.solution?.let { solution ->
                val name = solution.fileName.toString()
                addAll(listOf(
                    msBuildProperty("SolutionPath", solution.toString()),
                    msBuildProperty("SolutionDir", solution.parent.toString().trimEnd('\\') + "\\"),
                    msBuildProperty("SolutionFileName", name),
                    msBuildProperty("SolutionName", name.substringBeforeLast('.')),
                    "/property:SolutionExt=.sln",
                ))
            }
        }

        /** `--config` is ignored by single-config generators, so check the cache rather than trusting the flag. */
        fun verifyBuild(realWorkspace: Path) {
            when (entry) {
                is Entry.CMake -> {
                    if (entry.directory.toRealPath() == realWorkspace || inputs.any { it.startsWith(entry.directory) }) {
                        throw IOException("Reflection rename requires an out-of-source CMake build")
                    }
                    val cache = entry.directory.resolve("CMakeCache.txt")
                    if (!Files.isRegularFile(cache) || Files.size(cache) > MAX_CMAKE_CACHE_BYTES) {
                        throw IOException("CMake cache is missing or too large: $cache")
                    }
                    val values = mutableMapOf<String, String>()
                    Files.newBufferedReader(cache).useLines { lines ->
                        for (line in lines) {
                            val key = line.substringBefore(':')
                            if (key in cacheKeys) {
                                require('=' in line && values.put(key, line.substringAfter('=')) == null)
                            }
                        }
                    }
                    val source = values["CMAKE_HOME_DIRECTORY"]?.let(Path::of)?.takeIf { it.isAbsolute }
                    if (source == null || !source.toRealPath().startsWith(realWorkspace)) {
                        throw IOException("CMake build is not configured for this workspace: ${entry.directory}")
                    }
                    val configurations = values["CMAKE_CONFIGURATION_TYPES"].orEmpty().split(';').filter(String::isNotEmpty)
                    val matches = if (configurations.isEmpty()) {
                        configuration == values["CMAKE_BUILD_TYPE"].orEmpty()
                    } else {
                        configuration in configurations
                    }
                    if (!matches) {
                        throw IOException("CMake directory is not configured for '$configuration': ${entry.directory}")
                    }
                }
                is Entry.MsBuild -> {
                    if (!Files.isRegularFile(entry.project) || !entry.project.toRealPath().startsWith(realWorkspace)) {
                        throw IOException("MSBuild project is outside the workspace or missing: ${entry.project}")
                    }
                }
            }
        }
    }

    sealed interface Entry {
        data class CMake(val directory: Path) : Entry
        data class MsBuild(val project: Path, val platform: String, val solution: Path? = null) : Entry
    }

    companion object {
        const val FILE_NAME = ".jb-slanglsp-rename.json"
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_TASKS = 4096
        private const val MAX_INPUTS = 65_536
        private const val MAX_CMAKE_CACHE_BYTES = 16L * 1024 * 1024
        private val cacheKeys = setOf("CMAKE_HOME_DIRECTORY", "CMAKE_BUILD_TYPE", "CMAKE_CONFIGURATION_TYPES")
        private val targetName = Regex("jb_slanglsp_reflection_[A-Za-z0-9_]{1,96}")
        internal val configurationName = Regex("[A-Za-z0-9_][A-Za-z0-9_ .+-]{0,95}")

        fun read(workspace: Path): ReflectionRenameRegistration? {
            val file = workspace.resolve(FILE_NAME)
            if (!Files.isRegularFile(file)) {
                return null
            }
            require(file.toRealPath().startsWith(workspace.toRealPath())) { "Registration is outside the workspace" }
            val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_BYTES + 1) }
            require(bytes.size <= MAX_BYTES) { "Reflection rename registration is too large" }
            return parse(workspace, decodeMetadataText(bytes).removePrefix("\uFEFF"))
        }

        internal fun parse(workspace: Path, text: String): ReflectionRenameRegistration {
            val root = JsonParser.parseString(text).asJsonObject
            require(root.keySet() == setOf("version", "tasks") && root.get("version").toString() == "1")
            val tasks = root.getAsJsonArray("tasks")
            require(tasks.size() <= MAX_TASKS)
            val seen = hashSetOf<Triple<Entry, String, String>>()
            var inputCount = 0
            val registered = buildList {
                for (item in tasks) {
                    val data = item.asJsonObject
                    val system = data.string("system")
                    require(system == "cmake" || system == "msbuild")
                    if (system == "msbuild" && !SystemInfo.isWindows) {
                        continue
                    }
                    val entry = when (system) {
                        "cmake" -> {
                            require(data.keySet().all { it in commonFields || it == "buildDirectory" })
                            val name = data.string("buildDirectory")
                            val path = Path.of(name)
                            require(path == path.normalize())
                            val directory = if (path.isAbsolute) path else relative(workspace, name)
                            require(directory != workspace)
                            Entry.CMake(directory)
                        }
                        else -> {
                            require(data.keySet().all { it in commonFields || it == "project" || it == "platform" })
                            val project = relative(workspace, data.string("project"))
                            val platform = data.string("platform")
                            require(project.fileName.toString().endsWith(".vcxproj", ignoreCase = true))
                            require(configurationName.matches(platform))
                            Entry.MsBuild(project, platform)
                        }
                    }
                    val configuration = data.string("configuration")
                    require(configurationName.matches(configuration) || (entry is Entry.CMake && configuration.isEmpty()))
                    val target = data.string("generationTarget")
                    require(targetName.matches(target)) { "A dedicated jb_slanglsp_reflection_ target is required" }
                    require(seen.add(Triple(entry, configuration, target))) { "Duplicate reflection rename task" }
                    val inputs = data.getAsJsonArray("inputs")
                    require(inputs != null && inputs.size() in 1..(MAX_INPUTS - inputCount))
                    inputCount += inputs.size()
                    val paths = inputs.mapTo(linkedSetOf()) {
                        require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                        relative(workspace, it.asString).also { path ->
                            require(entry !is Entry.CMake || !path.startsWith(entry.directory))
                        }
                    }
                    val tool = data.get("tool")?.let {
                        val path = Path.of(data.string("tool"))
                        require(path.isAbsolute && path == path.normalize())
                        val expected = if (entry is Entry.MsBuild) {
                            "MSBuild.exe"
                        } else if (SystemInfo.isWindows) {
                            "cmake.exe"
                        } else {
                            "cmake"
                        }
                        require(path.fileName.toString().equals(expected, ignoreCase = SystemInfo.isWindows))
                        path
                    }
                    add(GenerationTask(entry, configuration, target, tool, paths))
                }
            }
            return ReflectionRenameRegistration(registered)
        }

        private val commonFields = setOf("system", "configuration", "generationTarget", "inputs", "tool")

        private fun msBuildProperty(name: String, value: String): String {
            val escaped = value.replace("%", "%25").replace(";", "%3B").replace(",", "%2C")
            return "/property:$name=$escaped"
        }

        private fun relative(workspace: Path, name: String): Path {
            require(isReflectionPath(name))
            val path = workspace.resolve(name)
            require(path.startsWith(workspace) && path != workspace)
            return path
        }

        private fun JsonObject.string(key: String): String {
            val value = get(key)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Missing or invalid $key" }
            return value.asString
        }
    }
}
