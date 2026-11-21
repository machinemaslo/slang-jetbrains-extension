package slanglsp.editor.navigation.cpp

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.HexFormat
import slanglsp.editor.navigation.cpp.ReflectionMetadataLookup.Result.Unavailable

/** Uses explicit solution mappings and build-published IntDir associations, never build recency. */
internal object MsBuildReflectionConfiguration {
    data class Choice(
        val solution: String,
        val solutionHash: String,
        val identity: ReflectionBuildIdentity,
        val solutionConfigurations: List<String>,
    )

    sealed interface Association {
        data class Found(val configuration: ActiveCppConfiguration, val producer: Producer? = null) : Association
        data class Rejected(val reason: Unavailable) : Association
    }

    data class Producer(val target: String, val executable: Path)

    fun choices(workspace: Path, indicator: ProgressIndicator): List<Choice> {
        return Files.list(workspace).use { paths ->
            paths.filter {
                it.fileName.toString().endsWith(".sln", ignoreCase = true) && Files.isRegularFile(it, NOFOLLOW_LINKS)
            }.sorted().limit(MAX_SOLUTIONS + 1L).toList()
        }.also { requireMetadata(it.size <= MAX_SOLUTIONS) }.flatMap { solution ->
            indicator.checkCanceled()
            parse(solution.fileName.toString(), read(solution, MAX_SOLUTION_BYTES), indicator)
        }
    }

    fun resolve(project: Project, workspace: Path, choice: Choice, selection: String? = null): Association = try {
        val solution = workspace.resolve(choice.solution)
        val projectFile = workspace.resolve(choice.identity.target)
        val solutionKey = hexDigest(choice.solution.encodeToByteArray())
        val variantKey = choice.identity.packName().removeSuffix(".pack")
        val file = workspace.resolve(".jb-slanglsp/reflection/msbuild/v1/$solutionKey/$variantKey.assoc")
        val root = workspace.toRealPath()
        requireMetadata(file.toRealPath().startsWith(root) && projectFile.toRealPath().startsWith(root) &&
            solution.toRealPath().startsWith(root))
        val parts = decodeMetadataText(read(file, MAX_ASSOCIATION_BYTES)).lineSequence().toList()
        requireMetadata(parts.lastOrNull() == "" &&
            ((parts.size == 5 && parts[0] == MAGIC_V1) || (parts.size == 7 && parts[0] == MAGIC_V2)))
        val solutionBytes = read(solution, MAX_SOLUTION_BYTES)
        val projectBytes = read(projectFile, MAX_SOLUTION_BYTES)
        if (choice.solutionHash != parts[1] || hexDigest(solutionBytes) != choice.solutionHash ||
            hexDigest(projectBytes) != parts[2] || hasUnsavedDocuments(solution, projectFile)) {
            Association.Rejected(Unavailable.STALE)
        } else {
            val directory = Path.of(parts[3])
            requireMetadata(directory.isAbsolute && directory == directory.normalize())
            // Invalid or unavailable producer information need not prevent read-only navigation.
            val producer = if (parts[0] == MAGIC_V2) producer(parts[4], parts[5]) else null
            val identity = choice.identity
            val configuration = if (selection == null) {
                ActiveCppConfiguration.snapshot(project, directory, identity.configuration, identity.target, "msbuild", identity.platform)
            } else {
                ActiveCppConfiguration.snapshot(project, directory, identity.configuration, identity.target, "msbuild", identity.platform, selection)
            }
            Association.Found(configuration, producer)
        }
    } catch (_: NoSuchFileException) {
        Association.Rejected(Unavailable.MISSING_PACK)
    } catch (_: InvalidPathException) {
        Association.Rejected(Unavailable.CONFLICTING)
    } catch (_: InvalidReflectionMetadata) {
        Association.Rejected(Unavailable.CONFLICTING)
    } catch (_: IOException) {
        Association.Rejected(Unavailable.CONFLICTING)
    }

    private fun producer(target: String, tool: String): Producer? {
        if (!GENERATION_TARGET.matches(target)) {
            return null
        }
        val executable = try {
            Path.of(tool)
        } catch (_: InvalidPathException) {
            return null
        }
        if (!executable.isAbsolute || executable != executable.normalize() ||
            !executable.fileName.toString().equals("MSBuild.exe", ignoreCase = true)) {
            return null
        }
        return Producer(target, executable)
    }

    private fun parse(solution: String, bytes: ByteArray, indicator: ProgressIndicator): List<Choice> {
        requireMetadata(isReflectionPath(solution))
        val text = decodeMetadataText(bytes).removePrefix("\uFEFF")
        val projects = mutableMapOf<String, String>()
        val configurations = linkedSetOf<String>()
        val mappings = mutableMapOf<Pair<String, String>, Pair<String, String>>()
        var section = ""
        for (line in text.lineSequence()) {
            indicator.checkCanceled()
            val trimmed = line.trim()
            val entry = PROJECT.matchEntire(trimmed)
            if (entry != null) {
                val path = entry.groupValues[1].replace('\\', '/')
                if (path.endsWith(".vcxproj", ignoreCase = true)) {
                    requireMetadata(isReflectionPath(path))
                    requireMetadata(projects.put(entry.groupValues[2].lowercase(), path) == null)
                }
                continue
            }
            when {
                trimmed.startsWith("GlobalSection(") -> section = trimmed.substringAfter('(').substringBefore(')')
                trimmed == "EndGlobalSection" -> section = ""
                section == "SolutionConfigurationPlatforms" && '=' in trimmed -> {
                    val label = trimmed.substringBefore('=').trim()
                    requireMetadata(configuration(label) != null && configurations.add(label))
                }
                section == "ProjectConfigurationPlatforms" -> {
                    val assignment = MAPPING.matchEntire(trimmed) ?: continue
                    val project = assignment.groupValues[1].lowercase()
                    val label = assignment.groupValues[2]
                    val mapped = configuration(assignment.groupValues[3]) ?: throw InvalidReflectionMetadata()
                    requireMetadata(mappings.put(project to label, mapped) == null)
                }
            }
        }
        val choices = linkedMapOf<ReflectionBuildIdentity, MutableList<String>>()
        for (label in configurations) {
            for ((guid, path) in projects) {
                val (configuration, platform) = mappings[guid to label] ?: continue
                val identity = ReflectionBuildIdentity("msbuild", path, configuration, platform)
                choices.getOrPut(identity) { mutableListOf() }.add(label)
                requireMetadata(choices.size <= MAX_VARIANTS)
            }
        }
        val hash = hexDigest(bytes)
        return choices.map { (identity, labels) -> Choice(solution, hash, identity, labels) }
    }

    private fun configuration(text: String): Pair<String, String>? {
        val parts = text.split('|', limit = 3)
        if (parts.size != 2 || parts.any { part -> part.isBlank() || part.any { it < ' ' || it == '\u007f' } }) {
            return null
        }
        return parts[0] to parts[1]
    }

    private fun read(path: Path, limit: Int): ByteArray =
        Files.newInputStream(path).use { stream -> stream.readNBytes(limit + 1).also { requireMetadata(it.size <= limit) } }

    private fun hasUnsavedDocuments(vararg files: Path): Boolean {
        val vfs = LocalFileSystem.getInstance()
        val virtualFiles = files.mapNotNull(vfs::findFileByNioFile)
        return runReadActionBlocking {
            val manager = FileDocumentManager.getInstance()
            virtualFiles.any { manager.getCachedDocument(it)?.let(manager::isDocumentUnsaved) == true }
        }
    }

    private fun hexDigest(bytes: ByteArray): String = HexFormat.of().formatHex(reflectionDigest(bytes))

    private val PROJECT = Regex("""Project\("\{[^}]+}"\)\s*=\s*"[^"]*",\s*"([^"]+)",\s*"(\{[^}]+})["]""")
    private val MAPPING = Regex("""(\{[^}]+})\.(.+)\.ActiveCfg\s*=\s*(.+)""")
    private val GENERATION_TARGET = Regex("jb_slanglsp_reflection_[A-Za-z0-9_]{1,96}")
    private const val MAGIC_V1 = "JB-SLANGLSP-MSBUILD-1"
    private const val MAGIC_V2 = "JB-SLANGLSP-MSBUILD-2"
    private const val MAX_SOLUTIONS = 16
    private const val MAX_VARIANTS = 512
    private const val MAX_SOLUTION_BYTES = 4 * 1024 * 1024
    private const val MAX_ASSOCIATION_BYTES = 64 * 1024
}
