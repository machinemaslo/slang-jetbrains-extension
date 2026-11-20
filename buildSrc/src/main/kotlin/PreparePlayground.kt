import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Refreshes template-owned files on every launch, retaining IDE-created workspace files. */
abstract class PreparePlayground : DefaultTask() {
    @get:InputDirectory abstract val templateDirectory: DirectoryProperty

    @get:OutputDirectory abstract val playgroundDirectory: DirectoryProperty

    @get:Input abstract val slangdCandidate: Property<String>

    @get:Input abstract val clangFormatCandidate: Property<String>

    @get:Input abstract val searchPath: Property<String>

    @TaskAction
    @Throws(IOException::class)
    fun prepare() {
        val source = templateDirectory.get().asFile.toPath()
        val target = playgroundDirectory.get().asFile.toPath()
        Files.walk(source).use { paths ->
            for (path in paths.filter { Files.isRegularFile(it) }.toList()) {
                val destination = target.resolve(source.relativize(path))
                Files.createDirectories(destination.parent)
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        val settings = target.resolve(".idea/SlangPluginSettings.xml")
        val slangd = findExecutable(slangdCandidate.get(), "slangd")
        val clang = findExecutable(clangFormatCandidate.get(), "clang-format")
        check(slangd.isNotEmpty()) {
            "Playground requires installed slangd on PATH or in vcpkg_installed. " +
                "Install/configure it and rerun Run Plugin; no native tools are installed by this task."
        }
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
        logger.lifecycle("Slang playground: {}", target)
    }

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
}
