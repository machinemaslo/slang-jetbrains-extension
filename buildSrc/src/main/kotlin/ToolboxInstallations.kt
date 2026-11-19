import groovy.json.JsonException
import groovy.json.JsonSlurper
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.*
import org.gradle.api.GradleException

/** Resolves installed IDEs from Toolbox's local inventory without scanning install directories. */
object ToolboxInstallations {
    fun find(inventory: String, ideName: String, productCodes: List<String>): File {
        if (inventory.isBlank()) {
            throw GradleException("JetBrains Toolbox inventory not found. Open Toolbox and install $ideName.")
        }

        val parser = JsonSlurper()
        val state: Any?
        try {
            state = parser.parseText(inventory)
        } catch (failure: JsonException) {
            throw GradleException("Could not read JetBrains Toolbox state.json. Reopen Toolbox and try again.", failure)
        }
        val tools =
            (state as? Map<*, *>)?.get("tools") as? List<*>
                ?: throw GradleException(
                    "JetBrains Toolbox state.json has an unrecognized format. Reopen Toolbox and try again.")

        var selected: Path? = null
        var selectedBuild = intArrayOf()
        for (item in tools) {
            if (item !is Map<*, *>) {
                continue
            }
            val code = item["productCode"] as? String ?: continue
            if (code !in productCodes) {
                continue
            }
            val location = item["installLocation"] as? String ?: continue
            try {
                val home: Path = Path.of(location).normalize()
                if (!home.isAbsolute) {
                    continue
                }
                var metadata = home.resolve("product-info.json")
                if (home.fileName != null && home.fileName.toString().endsWith(".app")) {
                    metadata = home.resolve("Contents/Resources/product-info.json")
                }
                if (!Files.isRegularFile(metadata)) {
                    continue
                }

                val product = parser.parseText(Files.readString(metadata)) as? Map<*, *> ?: continue
                if (product["productCode"] != code) {
                    continue
                }
                val version = product["buildNumber"] as? String ?: continue
                val build = version.split('.').dropLastWhile { it.isEmpty() }.map(String::toInt).toIntArray()
                if (selected == null || Arrays.compare(build, selectedBuild) > 0) {
                    selected = home
                    selectedBuild = build
                }
            } catch (_: IOException) {
                // A stale entry or an installation being updated must not hide another installed copy.
            } catch (_: InvalidPathException) {} catch (_: NumberFormatException) {} catch (_: JsonException) {}
        }
        if (selected == null) {
            throw GradleException(
                "No usable $ideName installation in JetBrains Toolbox. Open Toolbox and install or repair it.")
        }
        return selected.toFile()
    }
}
