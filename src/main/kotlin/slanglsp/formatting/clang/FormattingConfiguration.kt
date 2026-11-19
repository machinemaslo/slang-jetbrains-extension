package slanglsp.formatting.clang

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import kotlin.Any
import kotlin.Exception
import kotlin.IllegalArgumentException
import kotlin.IllegalStateException
import kotlin.Long
import kotlin.String
import kotlin.Throws
import kotlin.arrayOf
import kotlin.synchronized

/** Compatibility for slangd 2026.22: config discovery and discarded clang-format errors. */
internal object FormattingConfiguration {
    private const val STYLE = "slang.format.clangFormatStyle"
    private const val FALLBACK = "slang.format.clangFormatFallbackStyle"
    private const val MAX_CACHED = 64
    private const val MAX_CONFIG_BYTES = 1024 * 1024
    private val VALIDATED = LinkedHashMap<Validation?, Boolean?>(16, 0.75f, true)

    @Throws(Exception::class)
    fun validate(
        source: Path,
        executable: String,
        settings: MutableMap<String?, Any?>,
        started: (CapturingProcessHandler) -> Unit
    ) {
        var style = settings[STYLE] as String
        if ("file" == style) {
            // slangd checks "_clang_format" instead of clang-format's "_clang-format".
            // An explicit path also makes discovery independent of the server working directory.
            val configuration = findConfiguration(source.parent)
            style = (if (configuration == null) settings[FALLBACK] as String? else "file:$configuration")!!
        }
        settings[STYLE] = style
        ProgressManager.checkCanceled()
        val revision = revision(source.parent, executable, style)
        synchronized(VALIDATED) {
            if (revision != null && VALIDATED[revision] != null) {
                return
            }
        }
        validateUncached(source, executable, style, started)
        if (revision != null && revision == revision(source.parent, executable, style)) {
            synchronized(VALIDATED) {
                VALIDATED[revision] = true
                if (VALIDATED.size > MAX_CACHED) {
                    VALIDATED.pollFirstEntry()
                }
            }
        }
    }

    private fun revision(directory: Path, executable: String, style: String?): Validation? {
        if (style == null || !style.startsWith("file:") && style.contains("InheritParentConfig")) {
            return null
        }
        try {
            val binary = Path.of(executable).toRealPath()
            val attributes = Files.readAttributes(binary, BasicFileAttributes::class.java)
            val configs = mutableListOf<ConfigRevision>()
            if (style.startsWith("file:")) {
                val requested = directory.resolve(style.substring(5))
                val config = requested.toRealPath()
                configs.add(fingerprint(config))
                // InheritParentConfig can depend on both the explicit config location and the source tree.
                fingerprintAncestors(requested.parent, configs)
                if (config.parent != requested.parent) {
                    fingerprintAncestors(config.parent, configs)
                }
                if (directory != requested.parent) {
                    fingerprintAncestors(directory, configs)
                }
            }
            return Validation(
                directory,
                binary,
                attributes.lastModifiedTime(),
                attributes.creationTime(),
                attributes.size(),
                attributes.fileKey(),
                style,
                configs.toList())
        } catch (_: IOException) {
            // Let clang-format report missing or invalid files; don't cache ambiguous inputs.
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    }

    @Throws(IOException::class)
    private fun fingerprintAncestors(directory: Path?, configs: MutableList<ConfigRevision>) {
        var parent = directory
        while (parent != null) {
            for (name in mutableListOf(".clang-format", "_clang-format")) {
                val file = parent.resolve(name)
                if (Files.isRegularFile(file)) {
                    configs.add(fingerprint(file))
                }
            }
            parent = parent.parent
        }
    }

    @Throws(IOException::class)
    private fun fingerprint(file: Path): ConfigRevision {
        try {
            Files.newInputStream(file).use { input ->
                val bytes = input.readNBytes(MAX_CONFIG_BYTES + 1)
                if (bytes.size > MAX_CONFIG_BYTES) {
                    throw IOException("clang-format config exceeds validation cache limit")
                }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                return ConfigRevision(file, HexFormat.of().formatHex(digest))
            }
        } catch (impossible: NoSuchAlgorithmException) {
            throw IllegalStateException(impossible)
        }
    }

    @Throws(Exception::class)
    private fun validateUncached(
        source: Path,
        executable: String?,
        style: String?,
        started: (CapturingProcessHandler) -> Unit
    ) {
        // Only validate configuration here. Slangd owns the formatting invocation and Slang-specific edits.
        // Its formatSource currently discards both the exit code and stderr, returning an empty edit list.
        val command =
            GeneralCommandLine(executable)
                .withCharset(StandardCharsets.UTF_8)
                .withWorkDirectory(source.parent.toFile())
                .withParameters("--assume-filename=$source.cs", "--style=$style", "--dump-config")
        val process = CapturingProcessHandler(command)
        started(process)
        try {
            val indicator = ProgressManager.getInstance().progressIndicator
            val output = process.runProcessWithProgressIndicator(indicator ?: EmptyProgressIndicator(), 10000, true)
            ProgressManager.checkCanceled()
            if (output.isCancelled) {
                throw ProcessCanceledException()
            }
            if (output.isTimeout) {
                throw IOException("clang-format configuration validation timed out")
            }
            if (output.getExitCode() != 0) {
                throw IOException(output.stderr)
            }
        } finally {
            process.destroyProcess()
        }
    }

    private fun findConfiguration(directory: Path?): Path? {
        var parent = directory
        while (parent != null) {
            for (name in arrayOf(".clang-format", "_clang-format")) {
                val candidate = parent.resolve(name)
                if (Files.isRegularFile(candidate)) {
                    return candidate
                }
            }
            parent = parent.parent
        }
        return null
    }

    @JvmRecord private data class ConfigRevision(val path: Path?, val digest: String?)

    @JvmRecord
    private data class Validation(
        val directory: Path?,
        val executable: Path?,
        val modified: FileTime?,
        val created: FileTime?,
        val size: Long,
        val fileKey: Any?,
        val style: String?,
        val configs: List<ConfigRevision>
    )
}
