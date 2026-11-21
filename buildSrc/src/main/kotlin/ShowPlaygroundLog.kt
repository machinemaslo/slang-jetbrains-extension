import java.nio.file.Files
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/** Shows plugin-owned log entries without attaching Gradle to the running IDE. */
abstract class ShowPlaygroundLog : DefaultTask() {
    @get:Internal abstract val logDirectory: DirectoryProperty

    @TaskAction
    fun show() {
        val path = logDirectory.get().asFile.toPath().resolve("idea.log")
        if (!Files.isRegularFile(path)) {
            logger.lifecycle("No playground IDE log yet: {}", path)
            return
        }
        val entries = Files.newBufferedReader(path).use { pluginLogEntries(it.lineSequence()) }
        if (entries.isEmpty()) {
            logger.lifecycle("No Slang plugin entries in {}", path)
        } else {
            for (entry in entries) {
                logger.lifecycle(entry)
            }
        }
    }
}

private val logHeader = Regex("""^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d[,.]\d{3}\s+\[\s*\d+]\s+\S+\s+-\s+(\S+)\s+-\s+""")
private const val MAX_ENTRIES = 200
private const val MAX_ENTRY_CHARS = 128 * 1024

internal fun pluginLogEntries(lines: Sequence<String>): List<String> {
    val entries = ArrayDeque<String>()
    var header: String? = null
    var tagged = false
    val body = StringBuilder()

    fun finish() {
        if (tagged && header != null) {
            if (entries.size == MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(header + body.toString().trimEnd())
        }
    }

    for (line in lines) {
        val category = logHeader.find(line)?.groupValues?.get(1)?.removePrefix("#")
        if (category != null) {
            finish()
            header = line
            body.clear()
            tagged = category == "slanglsp" || category.startsWith("slanglsp.")
        } else if (header != null && body.length < MAX_ENTRY_CHARS) {
            body.append('\n').append(line.take(MAX_ENTRY_CHARS - body.length))
        }
        // The IDE logs some plugin failures under its own category but attributes the exception.
        tagged = tagged || "[Plugin: slanglsp_r]" in line
    }
    finish()
    return entries.toList()
}
