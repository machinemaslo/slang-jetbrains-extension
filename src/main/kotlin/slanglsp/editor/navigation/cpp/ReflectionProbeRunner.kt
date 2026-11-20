package slanglsp.editor.navigation.cpp

import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.io.FileUtil
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.io.File
import java.util.concurrent.TimeUnit
import slanglsp.SlangBundle

@Service(Service.Level.PROJECT)
internal class ReflectionProbeRunner {
    data class Target(val output: String, val command: String, val entry: ReflectionInvocationDiscovery.Entry? = null) {
        override fun toString(): String = output.ifEmpty { command }
    }

    data class Source(
        val root: Path,
        val file: Path,
        val offset: Int,
        val text: String,
        val configuration: ActiveCppConfiguration? = null,
    )
    data class Match(val declaration: ReflectionProbeMatcher.Candidate, val artifact: String)

    private data class Stamp(val path: Path, val size: Long, val modified: FileTime, val key: Any?)
    private data class CacheKey(
        val file: Path,
        val offset: Int,
        val build: Path,
        val command: String,
        val configuration: ActiveCppConfiguration?,
    )
    private data class Cached(val stamps: List<Stamp>, val match: Match)

    private val cache = LinkedHashMap<CacheKey, Cached>()

    /** Called before discovery. No build-tool or generator processes on a valid cache hit. */
    @Synchronized
    fun cached(file: Path, offset: Int, text: CharSequence, configuration: ActiveCppConfiguration? = null): Match? {
        val entries = cache.filterKeys { it.file == file && it.offset == offset && it.configuration == configuration }
        if (entries.size != 1 || readText(file)?.let(::editorText) != text.toString()) return null
        val value = entries.values.single()
        return value.match.takeIf {
            stamps(value.stamps.map { stamp -> stamp.path }) == value.stamps &&
                readArtifact(it.declaration.file) == it.artifact
        }
    }

    fun discoverBuilds(root: Path): List<Path> {
        val candidates = linkedSetOf(root)
        val children = childDirectories(root).take(64)
        for (child in children) {
            candidates.add(child)
            if (child.fileName.toString().contains("build", ignoreCase = true) ||
                child.fileName.toString() in setOf("out", ".build")) {
                candidates.addAll(childDirectories(child).take(24))
            }
        }
        return candidates.flatMap { dir -> listOf(dir.resolve("build.ninja"), dir.resolve("compile_commands.json")) }
            .filter { Files.isRegularFile(it) }.sorted()
    }

    fun targets(build: Path, source: Path): List<Target> = inScratch { scratch ->
        val ninja = ninja(build)
        val log = scratch.resolve("ninja.log")
        val outputs = linkedSetOf<String>()
        val relative = if (build.root == source.root) build.relativize(source).toString() else source.toString()
        for (path in setOf(relative, source.toString())) {
            val direct = query(ninja, build, path, log) ?: continue
            outputs.addAll(direct.outputs.take(MAX_TARGETS_TO_INSPECT + 1))
        }
        if (outputs.size > MAX_TARGETS_TO_INSPECT) {
            throw Failure(SlangBundle.message("cpp.probe.too.many.targets"))
        }
        outputs.mapNotNull { target(ninja, build, it, log) }.distinctBy { it.command }
    }

    fun availableTargets(build: Path): List<String> = inScratch { scratch ->
        val output = command(
            listOf(ninja(build), "-C", build.toString(), "-t", "targets", "rule", "CUSTOM_COMMAND"),
            scratch.resolve("targets.log"),
        )
        if (output.code != 0) {
            throw Failure(SlangBundle.message("cpp.probe.manifest.failed", output.text.trim().take(400)))
        }
        val targets = output.text.lineSequence().filter { it.isNotBlank() && !it.startsWith("-") }
            .take(MAX_PICKER_TARGETS + 1).toList()
        if (targets.size > MAX_PICKER_TARGETS) {
            throw Failure(SlangBundle.message("cpp.probe.too.many.targets"))
        }
        targets.distinct().sorted()
    }

    fun target(build: Path, output: String): Target? = inScratch { scratch ->
        target(ninja(build), build, output, scratch.resolve("ninja.log"))
    }

    private fun target(ninja: String, build: Path, output: String, log: Path): Target? {
        if (output.startsWith("-") || query(ninja, build, output, log)?.rule != "CUSTOM_COMMAND") {
            return null
        }
        val expanded = command(listOf(ninja, "-C", build.toString(), "-t", "commands", "-s", output), log)
        val shell = expanded.text.trim()
        return if (expanded.code == 0 && shell.isNotEmpty() && '\n' !in shell) Target(output, shell) else null
    }

    private fun query(ninja: String, build: Path, path: String, log: Path): Query? {
        val output = command(listOf(ninja, "-C", build.toString(), "-t", "query", path), log)
        return if (output.code == 0) parseQuery(output.text) else null
    }

    /** A successful cached result still needs a live artifact and unchanged declared inputs. */
    @Synchronized
    fun probe(build: Path, source: Source, target: Target): Match? = inScratch { scratch ->
        if (readText(source.file)?.let(::editorText) != source.text) {
            throw Failure(SlangBundle.message("cpp.probe.save.source"))
        }
        val entry = target.entry
        val current = if (entry == null) {
            target(ninja(build), build, target.output, scratch.resolve("ninja.log")) == target
        } else {
            target in ReflectionInvocationDiscovery.databaseTargets(entry.manifest, source.file)
        }
        if (!current) {
            throw Failure(SlangBundle.message("cpp.probe.inputs.changed"))
        }

        val replay = ReflectionGeneratorReplay.bind(build, source.file, target)
            ?: throw Failure(SlangBundle.message("cpp.probe.no.binding"))
        val key = CacheKey(source.file, source.offset, build, target.command, source.configuration)
        val inputs = if (entry == null) inputFiles(build, target, source.file, scratch)?.plus(listOf(replay.executable))?.distinct()
            else null // Standard compilation database entries don't list transitive inputs.
        val before = inputs?.let(::stamps)
        val known = cache[key]
        if (known != null && inputs != null && before != null &&
            known.stamps == stamps(inputs + listOf(known.match.declaration.file)) &&
            readArtifact(known.match.declaration.file) == known.match.artifact) {
            return@inScratch known.match
        }

        fun probeSource(shape: ReflectionProbeMatcher.Shape, count: Int): String =
            ReflectionProbeMatcher.inject(source.text, source.offset, shape, count)
                ?: throw Failure(SlangBundle.message("cpp.probe.unsupported.struct"))

        val array = ReflectionProbeMatcher.Shape.ARRAY
        val firstSource = probeSource(array, array.first)
        val secondSource = probeSource(array, array.second)
        val dependencies = inputs.orEmpty()
        val baseline = replay.run(scratch.resolve("base"), source.text, dependencies)
        if (baseline.isEmpty()) {
            return@inScratch null
        }
        fun trace(shape: ReflectionProbeMatcher.Shape, first: String, second: String): ReflectionProbeMatcher.Candidate? {
            val prefix = shape.name.lowercase()
            val firstArtifacts = replay.run(scratch.resolve("$prefix-first"), first, dependencies)
            val secondArtifacts = replay.run(scratch.resolve("$prefix-second"), second, dependencies)
            return ReflectionProbeMatcher.match(baseline, firstArtifacts, secondArtifacts, shape)
        }
        val arrayMatch = try {
            trace(array, firstSource, secondSource)
        } catch (_: GeneratorFailure) {
            // Some generators support scalar fields but reject Slang array reflection.
            null
        }
        val scalars = ReflectionProbeMatcher.Shape.SCALARS
        val declaration = arrayMatch ?: trace(scalars, probeSource(scalars, scalars.first), probeSource(scalars, scalars.second))
            ?: return@inScratch null
        if (readText(source.file)?.let(::editorText) != source.text ||
            (inputs != null && before != null && before != stamps(inputs))) {
            throw Failure(SlangBundle.message("cpp.probe.inputs.changed"))
        }
        val artifact = baseline.getValue(declaration.file)
        if (readArtifact(declaration.file) != artifact) {
            throw Failure(SlangBundle.message("cpp.probe.refresh.output", declaration.file.toString()))
        }
        val match = Match(declaration, artifact)
        if (inputs != null && before != null) {
            stamps(inputs + listOf(declaration.file))?.let {
                if (cache.size >= MAX_CACHE) {
                    cache.remove(cache.keys.first())
                }
                cache[key] = Cached(it, match)
            }
        }
        match
    }

    private fun inputFiles(build: Path, target: Target, source: Path, scratch: Path): List<Path>? {
        // This subtool may not be available on older Ninja builds; probing still works without caching.
        val output = command(
            listOf(ninja(build), "-C", build.toString(), "-t", "inputs", target.output),
            scratch.resolve("inputs.log"),
        )
        if (output.code != 0) {
            return null
        }
        val paths = output.text.lineSequence().filter { it.isNotBlank() }.take(MAX_INPUTS + 1)
            .map { build.resolve(it).normalize() }.toList()
        if (paths.size > MAX_INPUTS || source !in paths) {
            return null
        }
        // CMake writes the toolchain rules in a separate Ninja manifest.
        val manifests = listOf(build.resolve("build.ninja"), build.resolve("CMakeFiles/rules.ninja"))
            .filter { Files.isRegularFile(it) }
        return buildList {
            addAll(paths)
            addAll(listOf(source))
            addAll(manifests)
        }.distinct()
    }

    private fun stamps(paths: List<Path>): List<Stamp>? {
        return paths.distinct().map { path ->
            ProgressManager.checkCanceled()
            val attributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } catch (_: IOException) {
                return null
            }
            if (!attributes.isRegularFile) {
                return null
            }
            Stamp(path, attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
        }
    }

    private fun <T> inScratch(action: (Path) -> T): T {
        val scratch = Files.createTempDirectory("slang-reflection-probe-")
        try {
            return action(scratch)
        } finally {
            FileUtil.delete(scratch.toFile())
        }
    }

    private fun command(args: List<String>, log: Path): Output {
        val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        process.outputStream.close()
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                ProgressManager.checkCanceled()
                if (System.nanoTime() >= end || Files.size(log) > MAX_LOG_BYTES) {
                    throw Failure(SlangBundle.message("cpp.probe.command.limit"))
                }
            }

            if (Files.size(log) > MAX_LOG_BYTES) {
                throw Failure(SlangBundle.message("cpp.probe.command.limit"))
            }

            return Output(process.exitValue(), String(Files.readAllBytes(log), Charsets.UTF_8))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
        }
    }

    private fun childDirectories(directory: Path): List<Path> {
        return Files.list(directory).use { children ->
            children.limit(256).filter { !it.fileName.toString().startsWith(".git") && Files.isDirectory(it, NOFOLLOW_LINKS) }
                .sorted().toList()
        }
    }

    private fun readArtifact(path: Path): String? {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            return null
        }
        return readText(path)
    }

    private data class Output(val code: Int, val text: String)
    internal data class Query(val rule: String?, val outputs: List<String>)
    internal open class Failure(message: String) : IOException(message)
    internal class GeneratorFailure(message: String) : Failure(message)

    companion object {
        private const val MAX_TARGETS_TO_INSPECT = 24
        private const val MAX_PICKER_TARGETS = 192
        private const val MAX_INPUTS = 512
        private const val MAX_LOG_BYTES = 512 * 1024L
        private const val MAX_ARTIFACT_BYTES = 8 * 1024 * 1024L
        private const val MAX_CACHE = 8

        internal fun executable(name: String): String? {
            val binary = if (File.separatorChar == '\\') "$name.exe" else name
            return System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).asSequence()
                .mapNotNull { runCatching { Path.of(it.trim('"')) }.getOrNull() }.filter { it.isAbsolute }
                .map { it.resolve(binary) }.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }?.toString()
        }

        private fun ninja(build: Path): String {
            val cache = build.resolve("CMakeCache.txt")
            if (Files.isRegularFile(cache) && Files.size(cache) <= MAX_LOG_BYTES) {
                val configured = Files.newBufferedReader(cache).useLines { lines ->
                    lines.firstOrNull { it.startsWith("CMAKE_MAKE_PROGRAM:FILEPATH=") }?.substringAfter('=')
                }?.let { runCatching { Path.of(it) }.getOrNull() }
                if (configured != null && configured.isAbsolute && configured.fileName.toString().lowercase() in
                    setOf("ninja", "ninja.exe", "ninja-build") && Files.isExecutable(configured)) return configured.toString()
            }
            return executable("ninja") ?: throw Failure(SlangBundle.message("cpp.probe.tool.missing", "Ninja"))
        }

        private fun readText(path: Path): String? {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_ARTIFACT_BYTES) {
                return null
            }
            return try {
                Files.readString(path)
            } catch (_: CharacterCodingException) {
                null
            }
        }

        internal fun parseQuery(text: String): Query {
            val lines = text.lineSequence().drop(1).toList()
            val rule = lines.firstOrNull { it.startsWith("  input: ") }?.removePrefix("  input: ")
            val outputs = lines.dropWhile { it != "  outputs:" }.drop(1)
                .takeWhile { it.startsWith("    ") }.map { it.removePrefix("    ") }
            return Query(rule, outputs)
        }
    }
}

internal fun editorText(text: String): String = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
