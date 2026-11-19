import groovy.json.JsonOutput
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolboxInstallationsTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun selectsNewestMatchingInstalledBuildFromInventory() {
        val oldIdea = install("Older IDEA", "IU", "263.99")
        val newIdea = install("New IDEA in a custom directory", "IC", "263.100")
        val clion = install("CLion", "CL", "264.1")
        val stale = directory.root.toPath().resolve("Removed IDEA")
        val inventory =
            inventory(
                entry("IU", oldIdea), entry("IC", newIdea), entry("IU", stale), entry("IU", clion), entry("CL", clion))

        assertEquals(newIdea.toFile(), ToolboxInstallations.find(inventory, "IntelliJ IDEA", listOf("IU", "IC")))
        assertEquals(clion.toFile(), ToolboxInstallations.find(inventory, "CLion", listOf("CL")))
    }

    @Test
    fun reportsMissingInventoryAndMissingProduct() {
        val missingInventory =
            assertThrows(GradleException::class.java) {
                ToolboxInstallations.find("", "CLion", listOf("CL"))
            }
        assertTrue(missingInventory.message.orEmpty().contains("inventory not found"))

        val missingProduct =
            assertThrows(GradleException::class.java) {
                ToolboxInstallations.find(inventory(), "CLion", listOf("CL"))
            }
        assertTrue(missingProduct.message.orEmpty().contains("No usable CLion installation"))
    }

    private fun install(name: String, code: String, build: String): Path {
        val mac = System.getProperty("os.name").startsWith("Mac")
        val home = directory.newFolder(if (mac) "$name.app" else name).toPath()
        val resources = if (mac) Files.createDirectories(home.resolve("Contents/Resources")) else home

        Files.writeString(
            resources.resolve("product-info.json"),
            JsonOutput.toJson(mapOf("productCode" to code, "buildNumber" to build)))
        return home
    }

    private fun entry(code: String, home: Path): Map<String, String> =
        mapOf("productCode" to code, "installLocation" to home.toString())

    private fun inventory(vararg entries: Map<String, String>): String =
        JsonOutput.toJson(mapOf("version" to 1, "tools" to entries.toList()))
}
