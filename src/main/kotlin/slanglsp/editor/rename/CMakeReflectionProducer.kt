package slanglsp.editor.rename

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.execution.RunManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import slanglsp.discovery.buildsystems.CmakeDiscoveryAdapter
import slanglsp.editor.navigation.cpp.ReflectionBuildIdentity
import slanglsp.editor.navigation.cpp.ReflectionMetadataLookup
import slanglsp.editor.navigation.cpp.decodeMetadataText
import slanglsp.editor.rename.ReflectionRenameRegistration.Entry
import slanglsp.editor.rename.ReflectionRenameRegistration.GenerationTask

/** Uses CLion's existing CMake File API reply. Never substitutes the host or default target. */
internal object CMakeReflectionProducer {
    class Request(val task: GenerationTask, val affected: Set<Path>, val isCurrent: () -> Boolean)

    fun buildDirectories(project: Project, workspace: Path): Set<Path> =
        loadedDirectories(project) + CmakeDiscoveryAdapter.configuredBuildDirectories(workspace)

    private fun loadedDirectories(project: Project): Set<Path> = runReadActionBlocking {
        try {
            // Use a CMake run configuration only to reach the optional CMake module's class loader.
            // The workspace model supplies all loaded profiles; the selected run config is irrelevant.
            val loader = RunManager.getInstance(project).allConfigurationsList.asSequence()
                .map { it.javaClass }
                .firstOrNull { it.name in cmakeRunConfigurations }
                ?.classLoader ?: return@runReadActionBlocking emptySet()
            val type = loader.loadClass("com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace")
            val cmake = type.getMethod("getInstance", Project::class.java).invoke(null, project)
            val profiles = type.getMethod("getModelConfigurationData").invoke(cmake) as? Collection<*>
            profiles.orEmpty().mapNotNullTo(linkedSetOf()) { profile ->
                val directory = profile?.javaClass?.getMethod("getGenerationDir")?.invoke(profile) as? File
                directory?.toPath()
            }
        } catch (failure: InvocationTargetException) {
            val cause = failure.targetException
            if (cause is ProcessCanceledException) {
                throw cause
            }
            log.debug("Cannot read loaded CMake build directories", failure)
            emptySet()
        } catch (failure: ReflectiveOperationException) {
            log.debug("CMake workspace API is unavailable", failure)
            emptySet()
        } catch (failure: LinkageError) {
            log.debug("CMake workspace API is unavailable", failure)
            emptySet()
        }
    }

    fun plan(
        workspace: Path,
        directories: Collection<Path>,
        changed: Set<Path>,
        lookup: ReflectionMetadataLookup,
        indicator: ProgressIndicator,
    ): List<Request> = buildList {
        val seen = hashSetOf<Path>()
        for (directory in directories) {
            indicator.checkCanceled()
            try {
                if (!Files.isDirectory(directory.resolve(PACK_DIRECTORY))) {
                    continue
                }
                val build = directory.toRealPath()
                if (!seen.add(build)) {
                    continue
                }
                val model = model(workspace, build) ?: continue
                addAll(model.plan(workspace, changed, lookup, indicator))
            } catch (failure: IOException) {
                log.debug("Cannot read CMake reflection model at $directory", failure)
            } catch (failure: InvalidPathException) {
                log.debug("Invalid path in CMake reflection model at $directory", failure)
            } catch (failure: IllegalStateException) {
                log.debug("Invalid CMake reflection model at $directory", failure)
            } catch (failure: JsonParseException) {
                log.debug("Invalid CMake reflection model at $directory", failure)
            }
        }
    }

    private fun model(workspace: Path, build: Path): BuildModel? {
        val reply = build.resolve(".cmake/api/v1/reply").takeIf { Files.isDirectory(it) }?.toRealPath() ?: return null
        val packDirectory = build.resolve(PACK_DIRECTORY).toRealPath()
        if (!reply.startsWith(build) || !packDirectory.startsWith(build)) {
            return null
        }
        val indexName = currentIndex(reply) ?: return null
        val index = read(reply, indexName) ?: return null
        val modelRef = index.objects("objects").singleOrNull {
            it.text("kind") == "codemodel" && it.getAsJsonObject("version")?.get("major")?.toString() == "2"
        } ?: return null
        val model = read(reply, modelRef.text("jsonFile")) ?: return null
        val paths = model.getAsJsonObject("paths") ?: return null
        val source = Path.of(paths.text("source") ?: return null).takeIf { it.isAbsolute } ?: return null
        val realWorkspace = workspace.toRealPath()
        if (source.toRealPath() == build || !source.toRealPath().startsWith(realWorkspace) ||
            Path.of(paths.text("build") ?: return null).toRealPath() != build) {
            return null
        }
        val executable = index.getAsJsonObject("cmake")?.getAsJsonObject("paths")?.text("cmake")
            ?.let(Path::of)?.takeIf { it.isAbsolute && Files.isRegularFile(it) && Files.isExecutable(it) } ?: return null
        val executableName = if (SystemInfo.isWindows) "cmake.exe" else "cmake"
        if (!executable.fileName.toString().equals(executableName, ignoreCase = SystemInfo.isWindows)) {
            return null
        }
        val packs = Files.newDirectoryStream(packDirectory, "*.pack").use { paths ->
            paths.asSequence().take(MAX_PACKS + 1)
                .map { it.fileName.toString() }.toHashSet()
        }
        if (packs.size > MAX_PACKS) {
            return null
        }
        return BuildModel(build, source, reply, indexName, executable, model.objects("configurations"), packs)
    }

    private class BuildModel(
        val build: Path,
        val source: Path,
        val reply: Path,
        val indexName: String,
        val executable: Path,
        val configurations: List<JsonObject>,
        val packs: Set<String>,
    ) {
        private val targetDetails = mutableMapOf<String, JsonObject>()

        fun plan(workspace: Path, changed: Set<Path>, lookup: ReflectionMetadataLookup, indicator: ProgressIndicator): List<Request> =
            buildList {
                for ((configuration, entries) in configurations.groupBy { it.text("name") }) {
                    if (configuration == null || entries.size != 1) {
                        continue
                    }
                    val refs = entries.single().objects("targets")
                    val byId = refs.groupBy { it.text("id") }
                    val matches = linkedMapOf<String, MutableList<Pair<Path, ReflectionMetadataLookup.RenameInputs>>>()
                    for ((name, hosts) in refs.groupBy { it.text("name") }) {
                        indicator.checkCanceled()
                        if (name.isNullOrBlank() || hosts.size != 1) {
                            continue
                        }
                        val identity = ReflectionBuildIdentity("cmake", name, configuration, "")
                        val packName = identity.packName()
                        if (packName !in packs) {
                            continue
                        }
                        val pack = build.resolve(PACK_DIRECTORY).resolve(packName)
                        if (!Files.isRegularFile(pack, NOFOLLOW_LINKS)) {
                            continue
                        }
                        val inputs = lookup.inputsForRename(workspace, build, identity, changed, indicator) ?: continue
                        val producer = try {
                            producer(hosts.single(), byId, pack)
                        } catch (failure: IOException) {
                            log.debug("Cannot resolve CMake producer for $pack", failure)
                            null
                        } catch (failure: IllegalStateException) {
                            log.debug("Invalid CMake producer for $pack", failure)
                            null
                        } catch (failure: IllegalArgumentException) {
                            log.debug("Invalid CMake producer for $pack", failure)
                            null
                        } catch (failure: JsonParseException) {
                            log.debug("Invalid CMake producer for $pack", failure)
                            null
                        } ?: continue
                        matches.getOrPut(producer) { mutableListOf() }.add(pack to inputs)
                    }
                    for ((target, outputs) in matches) {
                        val inputs = outputs.flatMapTo(linkedSetOf()) { it.second.recorded }
                        val affected = outputs.flatMapTo(linkedSetOf()) { it.second.affected }
                        val task = GenerationTask(Entry.CMake(build), configuration, target, executable, inputs)
                        add(Request(task, affected) {
                            currentIndex(reply) == indexName && outputs.all { Files.isRegularFile(it.first, NOFOLLOW_LINKS) }
                        })
                    }
                }
            }

        private fun detail(ref: JsonObject): JsonObject? {
            val file = ref.text("jsonFile") ?: return null
            return targetDetails[file] ?: read(reply, file)?.also { targetDetails[file] = it }
        }

        private fun producer(host: JsonObject, byId: Map<String?, List<JsonObject>>, pack: Path): String? {
            val hostDetails = detail(host) ?: return null
            if (hostDetails.text("name") != host.text("name") || hostDetails.text("id") != host.text("id")) {
                return null
            }
            val dependencies = hostDetails.objects("dependencies").mapNotNullTo(hashSetOf()) { it.text("id") }
            if (dependencies.size > MAX_DEPENDENCIES) {
                return null
            }
            val rule = pack.resolveSibling("${pack.fileName}.rule")
            var producer: String? = null
            for (id in dependencies) {
                val ref = byId[id]?.singleOrNull() ?: return null
                val name = ref.text("name")?.takeIf { it != host.text("name") && it !in reserved && targetName.matches(it) }
                    ?: continue
                val details = detail(ref) ?: return null
                if (details.text("type") != "UTILITY" || details.text("name") != name || details.text("id") != id) {
                    continue
                }
                // Generator-expression outputs may have opaque .rule names. A generated pack
                // exposed via SOURCES is the evaluated output; an exact .pack.rule works too.
                val ownsPack = details.objects("sources").any { entry ->
                    val path = entry.text("path")?.let { source.resolve(it).normalize() }
                    entry.get("isGenerated")?.toString() == "true" && (path == pack || path == rule)
                }
                if (ownsPack) {
                    if (producer != null) {
                        return null
                    }
                    producer = name
                }
            }
            return producer
        }
    }

    /** CMake specifies the lexicographically largest reply index as current. */
    private fun currentIndex(reply: Path): String? =
        Files.newDirectoryStream(reply, "index-*.json").use { files ->
            files.map { it.fileName.toString() }.maxOrNull()
        }

    private fun read(reply: Path, name: String?): JsonObject? {
        if (name == null || !replyName.matches(name)) {
            return null
        }
        val path = reply.resolve(name).toRealPath().takeIf { it.parent == reply && Files.isRegularFile(it) } ?: return null
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_REPLY_BYTES + 1) }
        return if (bytes.size <= MAX_REPLY_BYTES) JsonParser.parseString(decodeMetadataText(bytes)).asJsonObject else null
    }

    private fun JsonObject.objects(key: String): List<JsonObject> =
        getAsJsonArray(key)?.map { it.asJsonObject }.orEmpty()

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private val replyName = Regex("[A-Za-z0-9_.+-]+\\.json")
    private val targetName = Regex("[A-Za-z0-9_][A-Za-z0-9_.+-]{0,127}")
    private val reserved = setOf("all", "ALL_BUILD", "clean", "install", "rebuild", "test", "package", "ZERO_CHECK")
    private val cmakeRunConfigurations = setOf(
        "com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration",
        "com.jetbrains.cidr.cpp.execution.testing.CMakeTestRunConfiguration",
    )
    private val log = Logger.getInstance(CMakeReflectionProducer::class.java)
    private const val PACK_DIRECTORY = ".jb-slanglsp/reflection/v1"
    private const val MAX_REPLY_BYTES = 16 * 1024 * 1024
    private const val MAX_DEPENDENCIES = 512
    private const val MAX_PACKS = 65_536
}
