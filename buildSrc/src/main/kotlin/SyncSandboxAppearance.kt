import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
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
    fun sync() {
        val root = Path.of(userConfigRoot.get())
        if (!Files.isDirectory(root)) {
            return
        }

        // Prefer the most recently used IDEA configuration (including Community).
        val source = Files.list(root).use { directories ->
            directories
                .filter { it.fileName.toString().matches(ideaConfigName) }
                .filter { Files.isRegularFile(it.resolve("options/other.xml")) }
                .max(compareBy { Files.getLastModifiedTime(it.resolve("options/other.xml")).toMillis() })
                .orElse(null)
        } ?: return
        val target = sandboxConfigDirectory.get().asFile.toPath()
        val builder = DocumentBuilderFactory.newDefaultInstance().apply {
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }.newDocumentBuilder()

        for (file in appearanceFiles) {
            val input = source.resolve("options/$file")
            if (!Files.isRegularFile(input)) {
                continue
            }

            val output = target.resolve("options/$file")
            val from = readAppearanceXml(input, builder)
            val to = if (Files.exists(output)) readAppearanceXml(output, builder) else builder.newDocument()
            if (to.documentElement == null) {
                to.appendChild(to.createElement("application"))
            }

            if (copyComponents(from.documentElement, to)) {
                Files.createDirectories(output.parent)
                TransformerFactory.newDefaultInstance().newTransformer().transform(DOMSource(to), StreamResult(output.toFile()))
            }
        }
        logger.lifecycle("Sandbox appearance inherited from {}", source)
    }

    private fun copyComponents(source: Element, target: Document): Boolean {
        var changed = false
        val root = target.documentElement
        val components = source.getElementsByTagName("component")
        for (i in 0..<components.length) {
            val component = components.item(i) as Element
            val name = component.getAttribute("name")
            if (name !in appearanceComponents) {
                continue
            }

            val existing = root.getElementsByTagName("component")
            for (j in existing.length - 1 downTo 0) {
                val old = existing.item(j) as Element
                if (old.getAttribute("name") == name) {
                    old.parentNode.removeChild(old)
                }
            }
            root.appendChild(target.importNode(component, true))
            changed = true
        }
        return changed
    }

    private companion object {
        val ideaConfigName = Regex("(IntelliJIdea|IdeaIC)\\d.*")
        val appearanceFiles = listOf("other.xml", "ui.lnf.xml", "laf.xml", "editor-font.xml", "console-font.xml")
        val appearanceComponents = setOf("NotRoamableUiSettings", "UISettings", "LafManager", "DefaultFont", "ConsoleFont")
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
