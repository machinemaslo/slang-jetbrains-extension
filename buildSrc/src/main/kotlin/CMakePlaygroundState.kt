import groovy.json.JsonSlurper
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

internal object CMakePlaygroundState {
    fun migrateProjectName(project: Path) {
        val idea = project.resolve(".idea")
        val name = idea.resolve(".name")
        if (Files.notExists(name) || Files.isRegularFile(name) && Files.readString(name).trim() == OLD_PROJECT) {
            Files.createDirectories(idea)
            Files.writeString(name, "Playground\n")
        }

        val modules = idea.resolve("modules.xml")
        val oldModule = idea.resolve("$OLD_PROJECT.iml")
        val newModule = idea.resolve("Playground.iml")
        if (Files.isRegularFile(oldModule) && Files.notExists(newModule) &&
            Files.isRegularFile(modules) && OLD_PROJECT in Files.readString(modules)) {
            updateXml(modules, "project") { root ->
                val path = "\$PROJECT_DIR\$/.idea/$OLD_PROJECT.iml"
                val module = root.child("component", "name", "ProjectModuleManager")?.child("modules")
                    ?.child("module", "filepath", path)
                if (module == null || module.getAttribute("fileurl") != "file://$path") {
                    false
                } else {
                    Files.move(oldModule, newModule)
                    module.setAttribute("filepath", "\$PROJECT_DIR\$/.idea/Playground.iml")
                    module.setAttribute("fileurl", "file://\$PROJECT_DIR\$/.idea/Playground.iml")
                    true
                }
            }
        }

        val workspace = idea.resolve("workspace.xml")
        if (Files.isRegularFile(workspace) && "Reflection Playground" in Files.readString(workspace)) {
            updateXml(workspace, "project") { root ->
                val configs = root.child("component", "name", "CMakeSettings")?.child("configurations")
                var changed = false
                for ((preset, label) in listOf("playground" to "Playground", "playground-release" to "Playground Release")) {
                    val profile = configs?.child("configuration", "PROFILE_NAME", preset) ?: continue
                    if (profile.getAttribute("FROM_PRESET") == "true" && profile.getAttribute("PROFILE_DISPLAY_NAME") == "Reflection $label") {
                        profile.setAttribute("PROFILE_DISPLAY_NAME", label)
                        changed = true
                    }
                }
                changed
            }
        }
    }

    fun seedProfiles(project: Path) {
        val idea = project.resolve(".idea")
        val workspace = idea.resolve("workspace.xml")
        val marker = idea.resolve("slang-playground-cmake-v1")
        if (Files.isRegularFile(marker) && Files.isRegularFile(workspace)) {
            return
        }
        val presets = project.resolve("CMakePresets.json")
        if (!Files.isRegularFile(presets)) {
            return
        }
        val catalog = JsonSlurper().parse(presets.toFile()) as Map<*, *>
        val profiles = (catalog["configurePresets"] as List<*>)
            .filterIsInstance<Map<*, *>>()
            .filter { it["hidden"] != true }
        if (profiles.isEmpty()) {
            return
        }

        updateXml(workspace, "project") { root ->
            root.setAttribute("version", "4")
            if (root.child("component", "name", "CMakePresetLoader") == null) {
                val loader = root.ensure("component", "name", "CMakePresetLoader")
                loader.appendChild(loader.ownerDocument.createCDATASection("""{"useNewFormat":true}"""))
            }
            val configurations = root.ensure("component", "name", "CMakeSettings").ensure("configurations")
            for (preset in profiles) {
                val name = preset["name"] as String
                val saved = configurations.child("configuration", "PROFILE_NAME", name)
                val profile = saved ?: configurations.ensure("configuration", "PROFILE_NAME", name).apply {
                    setAttribute("FROM_PRESET", "true")
                    (preset["displayName"] as? String)?.let { setAttribute("PROFILE_DISPLAY_NAME", it) }
                    (preset["binaryDir"] as? String)?.let {
                        setAttribute("GENERATION_DIR", it.replace("\${sourceDir}", "\$PROJECT_DIR\$"))
                    }
                }
                if (profile.getAttribute("FROM_PRESET") == "true") {
                    profile.setAttribute("ENABLED", "true")
                }
            }
            val debug = configurations.child("configuration", "PROFILE_NAME", "Debug")
            // Disable CLion's unmodified fallback, but not a customized or additional profile.
            if (debug != null && debug.attributes.length == 3 && debug.getAttribute("CONFIG_NAME") == "Debug" &&
                debug.getAttribute("ENABLED") == "true" && !debug.hasChildNodes()) {
                debug.setAttribute("ENABLED", "false")
            }
            true
        }
        Files.writeString(marker, "CMake playground profiles initialized.\n")
    }

    fun hideBuildOnlyTargets(project: Path) {
        val workspace = project.resolve(".idea/workspace.xml")
        if (!Files.isRegularFile(project.resolve("CMakePresets.json")) || !Files.isRegularFile(workspace)) {
            return
        }
        updateXml(workspace, "project") { root ->
            // CLion remembers generated configurations even after they are removed from the menu.
            // Seed that history for the helpers so a CMake reload does not recreate them.
            val generated = root.ensure("component", "name", "CMakeRunConfigurationManager").ensure("generated")
            var changed = false
            for (target in buildOnlyTargets) {
                if (generated.elements("config").none {
                        it.getAttribute("projectName") == "Playground" && it.getAttribute("targetName") == target
                    }) {
                    generated.appendChild(generated.ownerDocument.createElement("config").apply {
                        setAttribute("projectName", "Playground")
                        setAttribute("targetName", target)
                    })
                    changed = true
                }
            }
            val runs = root.child("component", "name", "RunManager") ?: return@updateXml changed
            val removedNames = runs.elements("configuration").filter {
                val target = it.getAttribute("TARGET_NAME")
                it.getAttribute("type") == "CMakeRunConfiguration" &&
                    it.getAttribute("PROJECT_NAME") in setOf("Playground", OLD_PROJECT) &&
                    it.getAttribute("name") == target &&
                    (target in buildOnlyTargets || target == "reflection_playground_bindings")
            }.map {
                runs.removeChild(it)
                "CMake Application.${it.getAttribute("name")}"
            }.toSet()
            if (removedNames.isEmpty()) {
                return@updateXml changed
            }
            runs.child("list")?.let { list ->
                list.elements("item").filter { it.getAttribute("itemvalue") in removedNames }.forEach(list::removeChild)
            }
            if (runs.getAttribute("selected") in removedNames) {
                if (runs.elements("configuration").any {
                        it.getAttribute("name") == "playground" && it.getAttribute("type") == "CMakeRunConfiguration"
                    }) {
                    runs.setAttribute("selected", "CMake Application.playground")
                } else {
                    runs.removeAttribute("selected")
                }
            }
            true
        }
    }

    fun disableWizard(sandboxConfig: Path) {
        updateXml(sandboxConfig.resolve("options/advancedSettings.xml"), "application") { root ->
            val settings = root.ensure("component", "name", "AdvancedSettings").ensure("option", "name", "settings").ensure("map")
            val wizard = settings.ensure("entry", "key", "cmake.initial.project.configuration")
            if (wizard.getAttribute("value") == "false") {
                false
            } else {
                wizard.setAttribute("value", "false")
                true
            }
        }
    }

    private fun updateXml(path: Path, rootName: String, edit: (Element) -> Boolean) {
        val builder = DocumentBuilderFactory.newDefaultInstance().apply {
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }.newDocumentBuilder()
        val document = if (Files.exists(path)) readAppearanceXml(path, builder) else builder.newDocument()
        val root = document.documentElement ?: document.createElement(rootName).also { document.appendChild(it) }
        check(root.tagName == rootName) { "Expected <$rootName> in $path" }
        if (!edit(root)) {
            return
        }
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".slang-playground-", ".xml")
        try {
            TransformerFactory.newDefaultInstance().newTransformer().transform(DOMSource(document), StreamResult(temporary.toFile()))
            try {
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun Element.elements(tag: String): Sequence<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.asSequence().filter { it.tagName == tag }

    private fun Element.child(tag: String, key: String = "", value: String = ""): Element? =
        elements(tag).firstOrNull { key.isEmpty() || it.getAttribute(key) == value }

    private fun Element.ensure(tag: String, key: String = "", value: String = ""): Element =
        child(tag, key, value) ?: ownerDocument.createElement(tag).also {
            if (key.isNotEmpty()) {
                it.setAttribute(key, value)
            }
            appendChild(it)
        }

    private const val OLD_PROJECT = "SlangReflectionPlayground"
    private val buildOnlyTargets = setOf("playground_generator", "playground_bindings", "reflection_preview_bindings")
}
