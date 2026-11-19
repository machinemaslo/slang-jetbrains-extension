package slanglsp.discovery

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.math.min
import org.w3c.dom.Document
import org.xml.sax.helpers.DefaultHandler

/** Shared, bounded metadata reads. Adapters never execute project code or walk directory trees. */
internal class DiscoveryContext(root: Path, environment: Map<String, String>) {
    val root: Path = root.toAbsolutePath().normalize()
    val environment = environment.toMap()
    val installations = LinkedHashMap<Path, String?>()
    val buildDirectories = LinkedHashSet<Path>()
    private val texts = mutableMapOf<Path, String>()
    private var remainingBytes = 8 * 1024 * 1024

    init {
        buildDirectories.add(this.root)
        buildDirectories.add(this.root.resolve("build"))
    }

    fun read(file: Path): String {
        val normalized = file.toAbsolutePath().normalize()
        texts[normalized]?.let {
            return it
        }

        if (texts.size >= 128 || remainingBytes <= 0) {
            return ""
        }

        return readBounded(normalized).also { texts[normalized] = it }
    }

    private fun readBounded(file: Path): String {
        val limit = min(remainingBytes, 2 * 1024 * 1024)

        try {
            if (!Files.isRegularFile(file) || Files.size(file) > limit) {
                return ""
            }

            Files.newInputStream(file).use { stream ->
                val bytes = stream.readNBytes(limit + 1)
                remainingBytes -= bytes.size
                if (bytes.size <= 2 * 1024 * 1024 && remainingBytes >= 0) {
                    return String(bytes, StandardCharsets.UTF_8)
                }
            }
        } catch (_: IOException) {
            // Missing or inaccessible metadata does not block later discovery sources.
        } catch (_: SecurityException) {}

        return ""
    }

    fun json(file: Path): JsonElement? {
        return try {
            JsonParser.parseString(read(file))
        } catch (_: RuntimeException) {
            JsonNull.INSTANCE
        }
    }

    fun xml(file: Path): Document? {
        val text = read(file)

        if (text.isBlank()) {
            return null
        }

        try {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            if (hasDoctype(bytes)) {
                return null
            }
            val factory = DocumentBuilderFactory.newDefaultInstance()
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            val builder = factory.newDocumentBuilder()
            builder.setErrorHandler(DefaultHandler())

            return builder.parse(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            return null
        }
    }

    private fun hasDoctype(bytes: ByteArray): Boolean {
        val factory = XMLInputFactory.newDefaultFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
        try {
            // StAX reports DOCTYPE without resolving it. Scan only the prolog of the same bytes the DOM reads.
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.DTD -> return true
                    XMLStreamConstants.START_ELEMENT -> return false
                }
            }
            return false
        } finally {
            reader.close()
        }
    }

    fun children(directory: Path, glob: String): List<Path> {
        try {
            Files.newDirectoryStream(directory, glob).use { files ->
                val result = ArrayList<Path>()
                for (file in files) {
                    if (result.size == 256) {
                        break
                    }

                    result.add(file)
                }

                result.sortBy(Path::toString)
                return result
            }
        } catch (_: IOException) {
            return emptyList()
        } catch (_: SecurityException) {
            return emptyList()
        }
    }

    fun resolve(base: Path?, value: String?): Path? {
        if (value.isNullOrBlank() ||
            value.contains("$") ||
            value.contains("%(") ||
            value.contains("%") ||
            value.startsWith("~") ||
            value.contains("\n")) {
            return null
        }

        return try {
            base!!.resolve(value.replace('\\', '/')).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            null
        }
    }

    fun installed(base: Path?, value: String?, host: String?) {
        val path = resolve(base, value)

        if (path != null && installations.size < 128) {
            installations.putIfAbsent(path, host)
        }
    }

    fun vcpkgRoot(base: Path?, value: String?) {
        val path = resolve(base, value) ?: return
        val filename = path.fileName?.toString()
        val installationRoot =
            if ((filename == "vcpkg" || filename == "vcpkg.exe") && Files.isRegularFile(path)) {
                path.parent
            } else {
                path
            }
        installed(installationRoot, "installed", null)
    }

    fun buildDirectory(base: Path?, value: String?) {
        val path = resolve(base, value)

        if (path != null && buildDirectories.size < 32) {
            buildDirectories.add(path)
        }
    }

    fun variables(base: Path?, values: Map<String, String?>) {
        val host = values["VCPKG_HOST_TRIPLET"]

        for (key in mutableListOf("VCPKG_INSTALLED_DIR", "_VCPKG_INSTALLED_DIR")) {
            installed(base, expand(values[key], values), host)
        }

        for (key in mutableListOf("VCPKG_ROOT", "VCPKG_INSTALLATION_ROOT")) {
            vcpkgRoot(base, expand(values[key], values))
        }

        val toolchain = expand(values["CMAKE_TOOLCHAIN_FILE"], values) ?: return
        val normalized = toolchain.replace('\\', '/')
        val suffix = "/scripts/buildsystems/vcpkg.cmake"
        if (normalized.endsWith(suffix)) {
            vcpkgRoot(base, normalized.removeSuffix(suffix))
        }
    }

    // Compilation metadata supplies a triplet include/lib path, including custom installation roots.
    fun dependencyPath(base: Path?, value: String) {
        var path = resolve(base, value)

        var i = 0
        while (path != null && i < 6) {
            if (Files.isRegularFile(path.resolve("vcpkg/status"))) {
                installed(path, ".", null)
                return
            }
            i++
            path = path.parent
        }
    }

    companion object {
        private val VARIABLE = Regex("""\$\(([^)]+)\)|\$\{([^}]+)}""")

        fun expand(value: String?, variables: Map<String, String?>): String? {
            var expanded = value ?: return null
            repeat(8) {
                val next =
                    VARIABLE.replace(expanded) { match ->
                        val (parenthesized, braced) = match.destructured
                        variables[parenthesized.ifEmpty { braced }] ?: match.value
                    }

                if (next.length > 8192) {
                    return null
                }

                if (next == expanded) {
                    return expanded
                }

                expanded = next
            }

            return expanded
        }
    }
}
