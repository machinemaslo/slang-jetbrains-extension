import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element

class CMakePlaygroundStateTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun seedsThePresetsForAFreshProject() {
        val project = projectWithPresets()
        CMakePlaygroundState.seedProfiles(project)
        val profiles = elements(project.resolve(".idea/workspace.xml"), "configuration").associateBy { it.getAttribute("PROFILE_NAME") }
        assertEquals(setOf("playground", "playground-release"), profiles.keys)
        assertTrue(profiles.values.all { it.getAttribute("FROM_PRESET") == "true" && it.getAttribute("ENABLED") == "true" })
        assertEquals("\$PROJECT_DIR\$/cmake-build-playground-release", profiles.getValue("playground-release").getAttribute("GENERATION_DIR"))
    }

    @Test
    fun repairsDisabledPresetsOnceWithoutResettingTheWorkspace() {
        val project = projectWithPresets()
        val workspace = project.resolve(".idea/workspace.xml")
        Files.createDirectories(workspace.parent)
        Files.writeString(workspace, """
            <project version="4">
              <component name="RunManager" selected="CMake Application.reflection_preview"/>
              <component name="CMakeSettings"><configurations>
                <configuration PROFILE_NAME="Debug" CONFIG_NAME="Debug" ENABLED="true"/>
                <configuration PROFILE_NAME="playground" FROM_PRESET="true" ENABLED="false" />
                <configuration PROFILE_NAME="playground-release" FROM_PRESET="true" ENABLED="false" />
                <configuration PROFILE_NAME="Custom" CONFIG_NAME="Debug" ENABLED="true"/>
              </configurations></component>
            </project>
        """.trimIndent())
        CMakePlaygroundState.seedProfiles(project)
        val profiles = elements(workspace, "configuration").associate { it.getAttribute("PROFILE_NAME") to it.getAttribute("ENABLED") }
        assertEquals(mapOf("Debug" to "false", "playground" to "true", "playground-release" to "true", "Custom" to "true"), profiles)
        assertEquals("CMake Application.reflection_preview",
            elements(workspace, "component").single { it.getAttribute("name") == "RunManager" }.getAttribute("selected"))

        val saved = Files.readString(workspace).replace("ENABLED=\"true\"", "ENABLED=\"false\"")
        Files.writeString(workspace, saved)
        CMakePlaygroundState.seedProfiles(project)
        assertEquals(saved, Files.readString(workspace))
    }

    @Test
    fun changesOnlyTheSandboxCmakeWizardSetting() {
        val sandbox = directory.newFolder("sandbox").toPath()
        val advanced = sandbox.resolve("options/advancedSettings.xml")
        Files.createDirectories(advanced.parent)
        Files.writeString(advanced, """
            <application><component name="AdvancedSettings"><option name="settings"><map>
              <entry key="unrelated" value="true"/>
              <entry key="cmake.initial.project.configuration" value="true"/>
            </map></option></component></application>
        """.trimIndent())
        CMakePlaygroundState.disableWizard(sandbox)
        assertEquals(mapOf("unrelated" to "true", "cmake.initial.project.configuration" to "false"),
            elements(advanced, "entry").associate { it.getAttribute("key") to it.getAttribute("value") })
    }

    @Test
    fun renamesTheSavedProjectWithoutResettingProfiles() {
        val idea = directory.newFolder("existing-playground").toPath().resolve(".idea")
        Files.createDirectories(idea)
        val oldName = "SlangReflectionPlayground"
        Files.writeString(idea.resolve(".name"), oldName)
        Files.writeString(idea.resolve("$oldName.iml"), "existing module settings")
        val modulePath = "\$PROJECT_DIR\$/.idea/$oldName.iml"
        val modules = idea.resolve("modules.xml")
        Files.writeString(modules, """
            <project><component name="ProjectModuleManager"><modules>
              <module filepath="$modulePath" fileurl="file://$modulePath"/>
            </modules></component></project>
        """.trimIndent())
        val workspace = idea.resolve("workspace.xml")
        Files.writeString(workspace, """
            <project><component name="CMakeSettings"><configurations>
              <configuration PROFILE_NAME="playground" FROM_PRESET="true" PROFILE_DISPLAY_NAME="Reflection Playground" ENABLED="false"/>
            </configurations></component></project>
        """.trimIndent())

        CMakePlaygroundState.migrateProjectName(idea.parent)

        assertEquals("Playground", Files.readString(idea.resolve(".name")).trim())
        assertEquals("existing module settings", Files.readString(idea.resolve("Playground.iml")))
        assertFalse(Files.exists(idea.resolve("$oldName.iml")))
        assertEquals("\$PROJECT_DIR\$/.idea/Playground.iml", elements(modules, "module").single().getAttribute("filepath"))
        val profile = elements(workspace, "configuration").single()
        assertEquals("Playground", profile.getAttribute("PROFILE_DISPLAY_NAME"))
        assertEquals("false", profile.getAttribute("ENABLED"))
    }

    @Test
    fun hidesBuildOnlyTargetsWithoutRemovingRunnableOrCustomEntries() {
        val project = projectWithPresets()
        val workspace = project.resolve(".idea/workspace.xml")
        Files.createDirectories(workspace.parent)
        Files.writeString(workspace, """
            <project version="4">
              <component name="RunManager" selected="CMake Application.playground_generator">
                <configuration name="playground" type="CMakeRunConfiguration" PROJECT_NAME="Playground" TARGET_NAME="playground"/>
                <configuration name="reflection_preview" type="CMakeRunConfiguration" PROJECT_NAME="Playground" TARGET_NAME="reflection_preview"/>
                <configuration name="playground_generator" type="CMakeRunConfiguration" PROJECT_NAME="Playground" TARGET_NAME="playground_generator"/>
                <configuration name="My generator" type="CMakeRunConfiguration" PROJECT_NAME="Playground" TARGET_NAME="playground_generator"/>
                <list><item itemvalue="CMake Application.playground_generator"/></list>
              </component>
            </project>
        """.trimIndent())

        CMakePlaygroundState.hideBuildOnlyTargets(project)

        assertEquals(listOf("playground", "reflection_preview", "My generator"),
            elements(workspace, "configuration").map { it.getAttribute("name") })
        assertEquals(setOf("playground_generator", "playground_bindings", "reflection_preview_bindings"),
            elements(workspace, "config").map { it.getAttribute("targetName") }.toSet())
        val runs = elements(workspace, "component").single { it.getAttribute("name") == "RunManager" }
        assertEquals("CMake Application.playground", runs.getAttribute("selected"))
        assertTrue(elements(workspace, "item").isEmpty())
        val saved = Files.readString(workspace)
        CMakePlaygroundState.hideBuildOnlyTargets(project)
        assertEquals(saved, Files.readString(workspace))
    }

    private fun projectWithPresets(): Path {
        val project = directory.newFolder("playground").toPath()
        Files.writeString(project.resolve("CMakePresets.json"), """
            {
              "version": 2,
              "configurePresets": [
                {"name": "playground", "binaryDir": "${'$'}{sourceDir}/cmake-build-playground"},
                {"name": "playground-release", "binaryDir": "${'$'}{sourceDir}/cmake-build-playground-release"}
              ]
            }
        """.trimIndent())
        return project
    }

    private fun elements(path: Path, tag: String): List<Element> {
        val document = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().parse(path.toFile())
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
}
