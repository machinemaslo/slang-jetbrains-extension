package slanglsp.editor.navigation.cpp

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import slanglsp.SlangBundle

/** Only replay a direct executable with identifiable input/output arguments. Never run the command through a shell. */
internal class ReflectionGeneratorReplay private constructor(
    val executable: Path,
    private val source: Path,
    private val cwd: Path,
    private val output: Path,
    private val arguments: List<Arg>,
) {
    private data class Arg(val text: String, val path: Path? = null)

    fun run(scratch: Path, text: String, dependencies: List<Path>): Map<Path, String> {
        // Preserve relative paths between the shader, its declared imports, the working directory and output.
        fun mapped(path: Path): Path = scratch.resolve(path.root.relativize(path))
        val inputs = dependencies.filter { it != source && it.fileName.toString().substringAfterLast('.').lowercase() in shaderExtensions }
        var bytes = 0L
        for (file in inputs.distinct()) {
            ProgressManager.checkCanceled()
            if (file.root != source.root || !Files.isRegularFile(file) || Files.size(file) > MAX_INPUT_BYTES - bytes) {
                throw ReflectionProbeRunner.Failure(SlangBundle.message("cpp.probe.dependencies"))
            }
            bytes += Files.size(file)
            val copy = mapped(file)
            Files.createDirectories(copy.parent)
            Files.copy(file, copy)
        }
        val shader = mapped(source)
        Files.createDirectories(shader.parent)
        Files.writeString(shader, text)
        val target = mapped(output)
        Files.createDirectories(target.parent)
        val directory = Files.createDirectories(mapped(cwd))
        val argv = listOf(executable.toString()) + arguments.map { arg ->
            arg.path?.let { arg.text + mapped(it) } ?: arg.text
        }
        val process = ProcessBuilder(argv).directory(directory.toFile()).redirectErrorStream(true)
            .redirectOutput(scratch.resolve("generator.log").toFile()).start()
        process.outputStream.close()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        val log = scratch.resolve("generator.log")
        try {
            while (!process.waitFor(75, TimeUnit.MILLISECONDS)) {
                ProgressManager.checkCanceled()
                if (System.nanoTime() > deadline || Files.size(log) > MAX_LOG_BYTES) {
                    throw ReflectionProbeRunner.Failure(SlangBundle.message("cpp.probe.command.limit"))
                }
            }
            if (process.exitValue() != 0) {
                val reason = Files.newInputStream(log).use { String(it.readNBytes(512), Charsets.UTF_8) }
                throw ReflectionProbeRunner.GeneratorFailure(SlangBundle.message("cpp.probe.generator.failed", reason))
            }
        } finally {
            if (process.isAlive) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
        }
        if (!Files.isRegularFile(target, NOFOLLOW_LINKS) || !target.toRealPath().startsWith(scratch.toRealPath()) ||
            Files.size(target) > MAX_OUTPUT_BYTES) {
            throw ReflectionProbeRunner.Failure(SlangBundle.message("cpp.probe.missing.output"))
        }
        return mapOf(output to Files.readString(target))
    }

    companion object {
        private const val MAX_INPUT_BYTES = 8 * 1024 * 1024L
        private const val MAX_OUTPUT_BYTES = 8 * 1024 * 1024L
        private const val MAX_LOG_BYTES = 512 * 1024L
        private val shaderExtensions = setOf("slang", "slangh", "h", "hlsl", "hlsli", "inc")
        private val inputOptions = setOf("-i", "--input", "--source", "--shader")
        private val outputOptions = setOf("-o", "--output", "--out", "--output-file")

        fun bind(build: Path, file: Path, target: ReflectionProbeRunner.Target): ReflectionGeneratorReplay? {
            var command = target.command.trim()
            // CMake's Windows Ninja generator encloses the entire cd-and-execute sequence in cmd /C quotes.
            if (SystemInfo.isWindows) {
                val wrapper = Regex("""(?i)^(?:"[^"]*\\cmd\.exe"|[^\s"]*cmd(?:\.exe)?)\s+/C\s+"(.*)"$""")
                wrapper.matchEntire(command)?.let { command = it.groupValues[1] }
            }
            val cd = Regex("""(?i)^cd\s+(?:/D\s+)?(.+?)\s+&&\s+(.+)$""").matchEntire(command)
            var cwd = target.entry?.directory ?: build
            if (cd != null && target.entry?.arguments == null) {
                val dir = words(cd.groupValues[1])?.singleOrNull() ?: return null
                cwd = resolve(build, dir) ?: return null
                command = cd.groupValues[2]
            }
            val tokens = target.entry?.arguments ?: words(command) ?: return null
            val program = tokens.firstOrNull()?.let { resolve(cwd, it) } ?: return null
            if (!Files.isRegularFile(program) || !Files.isExecutable(program) || !nativeBinary(program)) {
                return null
            }
            val declared = resolve(build, target.output) ?: return null
            val args = mutableListOf<Arg>()
            var hasSource = false
            var output: Path? = null
            var i = 1
            while (i < tokens.size) {
                val value = tokens[i++]
                val key = value.substringBefore('=')
                val named = key in inputOptions || key in outputOptions || key == "-I"
                val raw = when {
                    !named -> value
                    '=' in value -> value.substringAfter('=')
                    i < tokens.size -> tokens[i++]
                    else -> return null
                }
                val path = resolve(cwd, raw) ?: return null
                when {
                    (!named || key in inputOptions) && path == file && !hasSource -> hasSource = true
                    (key in outputOptions || (!named && path == declared)) && output == null &&
                        path != file && Files.isRegularFile(path, NOFOLLOW_LINKS) -> output = path
                    key == "-I" && Files.isDirectory(path) -> Unit
                    else -> return null
                }
                if (path.root != file.root || cwd.root != file.root) {
                    return null
                }
                if (named && '=' !in value) {
                    args.add(Arg(key))
                }
                args.add(Arg(if (named && '=' in value) "$key=" else "", path))
            }
            return if (hasSource && output != null) ReflectionGeneratorReplay(program, file, cwd, output, args) else null
        }

        private fun resolve(cwd: Path, text: String): Path? = try {
            cwd.resolve(text).normalize()
        } catch (_: java.nio.file.InvalidPathException) {
            null
        }

        private fun nativeBinary(path: Path): Boolean {
            val bytes = Files.newInputStream(path).use { it.readNBytes(4) }
            if (bytes.size != 4) return false
            val magic = java.nio.ByteBuffer.wrap(bytes).int
            return when {
                SystemInfo.isWindows -> bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()
                SystemInfo.isMac -> magic.toUInt() in setOf(0xfeedfaceu, 0xfeedfacfu, 0xcefaedfeu, 0xcffaedfeu, 0xcafebabeu, 0xcafebabfu)
                else -> magic == 0x7f454c46
            }
        }

        /** Accept simple literal argv and quoted paths; reject expansions, redirects, pipelines and command lists. */
        private fun words(text: String): List<String>? {
            if (text.any { it in "\r\n;&|<>`$%!^*?" }) return null
            val result = mutableListOf<String>()
            val word = StringBuilder()
            var quote: Char? = null
            var i = 0
            while (i < text.length) {
                val c = text[i++]
                when {
                    c == quote -> quote = null
                    quote == null && (c == '"' || (c == '\'' && !SystemInfo.isWindows)) -> quote = c
                    quote == null && c.isWhitespace() -> if (word.isNotEmpty()) {
                        result.add(word.toString())
                        word.clear()
                    }
                    c == '\\' && !SystemInfo.isWindows && quote != '\'' -> {
                        if (i == text.length) return null
                        word.append(text[i++])
                    }
                    else -> word.append(c)
                }
            }
            if (quote != null) return null
            if (word.isNotEmpty()) result.add(word.toString())
            return result
        }
    }
}
