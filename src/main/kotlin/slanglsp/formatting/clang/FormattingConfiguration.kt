package slanglsp.formatting.clang

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import kotlin.io.path.Path
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.readAttributes
import kotlin.time.Duration.Companion.seconds

/** Compatibility for slangd 2026.22: config discovery and discarded clang-format errors. */
internal object FormattingConfiguration {
    private const val STYLE = "slang.format.clangFormatStyle"
    private const val FALLBACK = "slang.format.clangFormatFallbackStyle"
    private const val MAX_CACHED = 64
    private const val MAX_CONFIG_BYTES = 1024 * 1024
    private val CONFIG_NAMES = listOf(".clang-format", "_clang-format")
    private val VALIDATION_TIMEOUT = 10.seconds
    private val VALIDATED = LinkedHashMap<Validation, Unit>(16, 0.75f, true)

    fun validate(
        source: Path,
        executable: String,
        settings: MutableMap<String?, Any?>,
        started: (CapturingProcessHandler) -> Unit,
    ) {
        var style = settings[STYLE] as String

        if (style == "file") {
            // slangd checks "_clang_format" instead of clang-format's "_clang-format".
            // An explicit path also makes discovery independent of the server working directory.
            val configuration = findConfiguration(source.parent)
            style = configuration?.let { "file:$it" } ?: (settings[FALLBACK] as String)
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
                VALIDATED[revision] = Unit

                if (VALIDATED.size > MAX_CACHED) {
                    VALIDATED.pollFirstEntry()
                }
            }
        }
    }

    private fun revision(directory: Path, executable: String, style: String): Validation? {
        if (!style.startsWith("file:") && style.contains("InheritParentConfig")) {
            return null
        }

        return try {
            val binary = Path(executable).toRealPath()
            val attributes = binary.readAttributes<BasicFileAttributes>()
            val configs = mutableListOf<ConfigRevision>()

            if (style.startsWith("file:")) {
                val requested = directory.resolve(style.removePrefix("file:"))
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

            Validation(
                directory,
                binary,
                attributes.lastModifiedTime(),
                attributes.creationTime(),
                attributes.size(),
                attributes.fileKey(),
                style,
                configs.toList(),
            )
        } catch (_: IOException) {
            // Let clang-format report missing or invalid files; don't cache ambiguous inputs.
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun fingerprintAncestors(directory: Path?, configs: MutableList<ConfigRevision>) {
        configFiles(directory).map(::fingerprint).toCollection(configs)
    }

    private fun fingerprint(file: Path): ConfigRevision {
        return try {
            file.inputStream().use { input ->
                val bytes = input.readNBytes(MAX_CONFIG_BYTES + 1)

                if (bytes.size > MAX_CONFIG_BYTES) {
                    throw IOException("clang-format config exceeds validation cache limit")
                }

                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                ConfigRevision(file, digest.toHexString())
            }
        } catch (impossible: NoSuchAlgorithmException) {
            throw IllegalStateException(impossible)
        }
    }

    private fun validateUncached(
        source: Path,
        executable: String,
        style: String,
        started: (CapturingProcessHandler) -> Unit,
    ) {
        // Only validate configuration here. Slangd owns the formatting invocation and Slang-specific edits.
        // Its formatSource currently discards both the exit code and stderr, returning an empty edit list.
        val command =
            GeneralCommandLine(executable)
                .withCharset(Charsets.UTF_8)
                .withWorkDirectory(source.parent.toFile())
                .withParameters("--assume-filename=$source.cs", "--style=$style", "--dump-config")
        val process = CapturingProcessHandler(command)
        started(process)

        try {
            val indicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
            val output = process.runProcessWithProgressIndicator(
                indicator,
                VALIDATION_TIMEOUT.inWholeMilliseconds.toInt(),
                true,
            )
            ProgressManager.checkCanceled()

            if (output.isCancelled) {
                throw ProcessCanceledException()
            }

            if (output.isTimeout) {
                throw IOException("clang-format configuration validation timed out")
            }

            if (output.exitCode != 0) {
                throw IOException(output.stderr)
            }
        } finally {
            process.destroyProcess()
        }
    }

    private fun findConfiguration(directory: Path?): Path? {
        return configFiles(directory).firstOrNull()
    }

    private fun configFiles(directory: Path?): Sequence<Path> {
        return generateSequence(directory) { it.parent }
            .flatMap { parent -> CONFIG_NAMES.asSequence().map(parent::resolve) }
            .filter { it.isRegularFile() }
    }

    private data class ConfigRevision(val path: Path, val digest: String)

    private data class Validation(
        val directory: Path,
        val executable: Path,
        val modified: FileTime?,
        val created: FileTime?,
        val size: Long,
        val fileKey: Any?,
        val style: String,
        val configs: List<ConfigRevision>,
    )
}
