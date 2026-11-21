import groovy.json.JsonSlurper
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

/** Starts the installed native launcher after sandbox preparation, without a Gradle-owned IDE process. */
abstract class LaunchPlayground : DefaultTask() {
    @get:Internal abstract val installation: DirectoryProperty
    @get:Internal abstract val playgroundDirectory: DirectoryProperty
    @get:Internal abstract val configDirectory: DirectoryProperty
    @get:Internal abstract val pluginsDirectory: DirectoryProperty
    @get:Internal abstract val systemDirectory: DirectoryProperty
    @get:Internal abstract val logDirectory: DirectoryProperty

    @get:Input abstract val ideName: Property<String>
    @get:Input abstract val configureCMake: Property<Boolean>
    @get:Input @get:Optional abstract val solutionName: Property<String>
    @get:Input abstract val vmOptionsVariable: Property<String>
    @get:Input abstract val hostOs: Property<String>
    @get:Input abstract val hostArchitecture: Property<String>
    @get:Input abstract val logTask: Property<String>

    @TaskAction
    fun launch() {
        val home = installation.get().asFile.toPath()
        val launcher = nativeIdeLauncher(home, hostOs.get(), hostArchitecture.get())
        val config = configDirectory.get().asFile.toPath()
        val plugins = pluginsDirectory.get().asFile.toPath()
        val system = systemDirectory.get().asFile.toPath()
        val logs = logDirectory.get().asFile.toPath()
        val playground = playgroundDirectory.get().asFile.toPath()
        val project = solutionName.orNull?.let(playground::resolve) ?: playground
        if (configureCMake.get()) {
            CMakePlaygroundState.disableWizard(config)
        }
        val vmOptions = config.resolve("slang-playground.vmoptions")
        val launchId = if (configureCMake.get()) UUID.randomUUID().toString() else null
        val arguments = linkedMapOf(
            "idea.config.path" to config.toString(),
            "idea.plugins.path" to plugins.toString(),
            "idea.system.path" to system.toString(),
            "idea.log.path" to logs.toString(),
            "platform.make.plugin.update.source.visible.in.ui" to "false",
        )
        if (launchId != null) {
            arguments["idea.auto.reload.plugins"] = "true"
            arguments["slang.playground.project"] = playground.toString()
            arguments["slang.playground.launch.id"] = launchId
        }
        check(arguments.values.none { '\r' in it || '\n' in it }) { "Playground paths must not contain newlines" }
        Files.write(vmOptions, arguments.map { (key, value) -> "-D$key=$value" })

        launchId?.let { requestPlaygroundReload(system, it) }
        val console = logs.resolve("launcher.log")
        ProcessBuilder(launcher.toString(), project.toString())
            .directory(playground.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(console.toFile()))
            .also { it.environment()[vmOptionsVariable.get()] = vmOptions.toString() }
            .start()
            .outputStream.close()
        logger.lifecycle("Launched the {} playground. Slang log task: {}. Full IDE logs: {}", ideName.get(), logTask.get(), logs)
    }
}

private fun requestPlaygroundReload(system: Path, launchId: String) {
    // Publish only after staging has succeeded. A cold IDE gets the same token in its VM options.
    val request = system.resolve("slang-playground.reload")
    val temporary = Files.createTempFile(system, ".slang-playground-", ".reload")
    try {
        Files.writeString(temporary, launchId)
        try {
            Files.move(temporary, request, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, request, REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal fun nativeIdeLauncher(home: Path, os: String, architecture: String): Path {
    val productOs = when (os) {
        "osx" -> "macOS"
        else -> os
    }
    val metadata = home.resolve("product-info.json").takeIf { Files.isRegularFile(it) }
        ?: home.resolve("Contents/Resources/product-info.json")
    val product = JsonSlurper().parse(metadata.toFile()) as? Map<*, *>
    val launches = product?.get("launch") as? List<*>
    val entry = launches.orEmpty().filterIsInstance<Map<*, *>>().singleOrNull {
        (it["os"] as? String).equals(productOs, ignoreCase = true) &&
            (it["arch"] as? String)?.let(::normalizeIdeArchitecture) == normalizeIdeArchitecture(architecture)
    }
    val relative = entry?.get("launcherPath") as? String
        ?: throw GradleException("No Toolbox IDE launcher for $productOs/$architecture in $metadata")
    val launcher = metadata.parent.resolve(relative).normalize()
    if (!launcher.startsWith(home) || !Files.isRegularFile(launcher) || !Files.isExecutable(launcher)) {
        throw GradleException("Toolbox IDE launcher is missing or not executable: $launcher")
    }
    return launcher
}

private fun normalizeIdeArchitecture(architecture: String): String = when (architecture.lowercase()) {
    "x64", "x86_64", "amd64" -> "x64"
    "arm64", "aarch64" -> "arm64"
    else -> architecture
}
