import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Refreshes template-owned files on every launch, retaining IDE-created workspace files. */
abstract class PreparePlayground : DefaultTask() {
    @get:InputDirectory abstract val templateDirectory: DirectoryProperty

    @get:InputDirectory @get:Optional abstract val cppTemplateDirectory: DirectoryProperty

    @get:InputDirectory @get:Optional abstract val reflectionSourcesDirectory: DirectoryProperty

    @get:OutputDirectory abstract val playgroundDirectory: DirectoryProperty

    @get:Input abstract val slangdCandidate: Property<String>

    @get:Input abstract val clangFormatCandidate: Property<String>

    @get:Input abstract val searchPath: Property<String>

    @TaskAction
    @Throws(IOException::class)
    fun prepare() {
        val target = playgroundDirectory.get().asFile.toPath()
        val template = templateDirectory.get().asFile.toPath()
        val cppTemplate = cppTemplateDirectory.orNull
        if (cppTemplate != null) {
            removeLegacyIdeaModule(template, target)
        }
        copyTemplates(template, target, if (cppTemplate == null) emptySet() else ideaWorkspaceFiles)
        if (cppTemplate != null) {
            copyTemplates(cppTemplate.asFile.toPath(), target, keepExisting = setOf(Path.of(".idea", "misc.xml")))
            CMakePlaygroundState.seedProfiles(target)
            CMakePlaygroundState.migrateProjectName(target)
            CMakePlaygroundState.hideBuildOnlyTargets(target)
        }
        reflectionSourcesDirectory.orNull?.let { sources ->
            copyTemplates(sources.asFile.toPath(), target.resolve("reflection"))
        }

        val settings = target.resolve(".idea/SlangPluginSettings.xml")
        val slangd = findExecutable(slangdCandidate.get(), "slangd")
        val clang = findExecutable(clangFormatCandidate.get(), "clang-format")
        check(slangd.isNotEmpty()) {
            "Playground requires installed slangd on PATH or in vcpkg_installed. " +
                "Install/configure it and rerun Run Plugin; no native tools are installed by this task."
        }
        Files.createDirectories(settings.parent)
        Files.writeString(
            settings,
            """
                <project version="4">
                  <component name="SlangPersistentStateComponentConfig">
                    <option name="explicitSlangdLocation" value="${xml(slangdDirectory(target, slangd))}" />
                    <option name="clangFormatLocation" value="${xml(clang)}" />
                  </component>
                </project>
                """.trimIndent() + "\n",
        )
        if (cppTemplate != null) {
            val tools = Path.of(slangd).toAbsolutePath().normalize().parent.toString().replace('\\', '/')
            Files.writeString(target.resolve(".idea/slang-tools.path"), "$tools\n")
        }

        logger.lifecycle("Slang playground: {}", target)
    }

    private fun copyTemplates(
        source: Path,
        target: Path,
        excluded: Set<Path> = emptySet(),
        keepExisting: Set<Path> = emptySet(),
    ) {
        Files.walk(source).use { paths ->
            for (path in paths.filter { Files.isRegularFile(it) }.toList()) {
                val relative = source.relativize(path)
                if (relative in excluded) {
                    continue
                }
                val destination = target.resolve(relative)
                if (relative in keepExisting && !Files.notExists(destination, NOFOLLOW_LINKS)) {
                    continue
                }
                if (Files.isRegularFile(destination, NOFOLLOW_LINKS) && Files.mismatch(path, destination) == -1L) {
                    continue
                }
                Files.createDirectories(destination.parent)
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun removeLegacyIdeaModule(template: Path, target: Path) {
        if (!Files.isDirectory(target.resolve(".idea"), NOFOLLOW_LINKS)) {
            return
        }
        val modules = target.resolve(ideaModules)
        val module = target.resolve(ideaModule)
        // Previous launches copied these verbatim. Don't remove a CLion or user-edited module model.
        if (isTemplateCopy(template.resolve(ideaModules), modules) &&
            (Files.notExists(module, NOFOLLOW_LINKS) || isTemplateCopy(template.resolve(ideaModule), module))) {
            Files.delete(modules)
            Files.deleteIfExists(module)
        }
    }

    private fun isTemplateCopy(source: Path, copy: Path): Boolean =
        Files.isRegularFile(source) && Files.isRegularFile(copy, NOFOLLOW_LINKS) && Files.mismatch(source, copy) == -1L

    private fun slangdDirectory(project: Path, executable: String): String {
        val executablePath = Path.of(executable)
        val directory = executablePath.toAbsolutePath().normalize().parent
        if (executablePath == Path.of(slangdCandidate.get())) {
            return project.toAbsolutePath().normalize().relativize(directory).toString().replace('\\', '/')
        }
        return directory.toString()
    }

    private fun findExecutable(candidate: String, name: String): String {
        if (Files.isExecutable(Path.of(candidate))) {
            return candidate
        }
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "$name.exe" else name
        for (entry in searchPath.get().split(File.pathSeparatorChar)) {
            if (entry.isBlank()) {
                continue
            }
            val path = Path.of(entry).resolve(executable)
            if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                return path.toAbsolutePath().toString()
            }
        }
        return ""
    }

    private fun xml(text: String): String {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    }

    private companion object {
        val ideaModules: Path = Path.of(".idea", "modules.xml")
        val ideaModule: Path = Path.of(".idea", "playground.iml")
        val ideaWorkspaceFiles = setOf(ideaModules, ideaModule, Path.of(".idea", "workspace.xml"))
    }
}
