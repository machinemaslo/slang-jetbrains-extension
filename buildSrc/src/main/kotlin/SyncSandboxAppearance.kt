import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.ToLongFunction
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Copies appearance components into the sandbox before startup. */
abstract class SyncSandboxAppearance : DefaultTask() {
    @get:Input abstract val userConfigRoot: Property<String>

    @get:Internal abstract val sandboxConfigDirectory: DirectoryProperty

    @TaskAction
    @Throws(Exception::class)
    fun sync() {
        val root: Path = Path.of(this.userConfigRoot.get())
        if (!Files.isDirectory(root)) {
            return
        }
        // Prefer the most recently used IDEA configuration (including Community).
        val source =
            Files.list(root).use { directories ->
                directories
                    .filter { path: Path ->
                        path.fileName.toString().matches("(IntelliJIdea|IdeaIC)\\d.*".toRegex())
                    }
                    .filter { path: Path -> Files.isRegularFile(path.resolve("options/other.xml")) }
                    .max(
                        Comparator.comparingLong(
                            ToLongFunction { path: Path ->
                                path.resolve("options/other.xml").toFile().lastModified()
                            }))
                    .orElse(null)
            } ?: return
        val target: Path = this.sandboxConfigDirectory.get().asFile.toPath()
        val builder =
            DocumentBuilderFactory.newDefaultInstance()
                .apply {
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                }
                .newDocumentBuilder()
        for (file in listOf("other.xml", "ui.lnf.xml", "laf.xml", "editor-font.xml", "console-font.xml")) {
            val input = source.resolve("options").resolve(file)
            if (!Files.isRegularFile(input)) {
                continue
            }
            val output = target.resolve("options").resolve(file)
            val from = readAppearanceXml(input, builder)
            val to = if (Files.exists(output)) readAppearanceXml(output, builder) else builder.newDocument()
            if (to.documentElement == null) {
                to.appendChild(to.createElement("application"))
            }
            var changed = false
            val components = from.documentElement.getElementsByTagName("component")
            for (i in 0..<components.length) {
                val component = components.item(i) as Element
                val name = component.getAttribute("name")
                if (!mutableListOf<String?>(
                        "NotRoamableUiSettings", "UISettings", "LafManager", "DefaultFont", "ConsoleFont")
                    .contains(name)) {
                    continue
                }
                val existing = to.documentElement.getElementsByTagName("component")
                for (j in existing.length - 1 downTo 0) {
                    val old = existing.item(j) as Element
                    if (old.getAttribute("name") == name) {
                        old.parentNode.removeChild(old)
                    }
                }
                to.documentElement.appendChild(to.importNode(component, true))
                changed = true
            }
            if (changed) {
                Files.createDirectories(output.parent)
                val transformer: Transformer = TransformerFactory.newInstance().newTransformer()
                transformer.transform(DOMSource(to), StreamResult(output.toFile()))
            }
        }
        logger.lifecycle("Sandbox appearance inherited from {}", source)
    }
}

internal fun readAppearanceXml(file: Path, builder: DocumentBuilder): Document {
    val bytes = Files.readAllBytes(file)
    val factory = XMLInputFactory.newDefaultFactory()
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
    try {
        // Reject DOCTYPE in the prolog without resolving it, then parse the same bytes into a DOM.
        prolog@ while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.DTD ->
                    throw XMLStreamException("DOCTYPE is not allowed in sandbox appearance XML: $file")
                XMLStreamConstants.START_ELEMENT -> break@prolog
            }
        }
    } finally {
        reader.close()
    }
    return builder.parse(ByteArrayInputStream(bytes))
}
