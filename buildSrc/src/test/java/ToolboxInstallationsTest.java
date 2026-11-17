import groovy.json.JsonOutput;
import org.gradle.api.GradleException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ToolboxInstallationsTest {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void selectsNewestMatchingInstalledBuildFromInventory() throws IOException {
        Path oldIdea = install("Older IDEA", "IU", "263.99");
        Path newIdea = install("New IDEA in a custom directory", "IC", "263.100");
        Path clion = install("CLion", "CL", "264.1");
        Path stale = directory.getRoot().toPath().resolve("Removed IDEA");
        String inventory = inventory(entry("IU", oldIdea), entry("IC", newIdea),
                entry("IU", stale), entry("IU", clion), entry("CL", clion));

        assertEquals(newIdea.toFile(), ToolboxInstallations.find(inventory, "IntelliJ IDEA", List.of("IU", "IC")));
        assertEquals(clion.toFile(), ToolboxInstallations.find(inventory, "CLion", List.of("CL")));
    }

    @Test
    public void reportsMissingInventoryAndMissingProduct() {
        var missingInventory = assertThrows(GradleException.class,
                () -> ToolboxInstallations.find("", "CLion", List.of("CL")));
        assertTrue(missingInventory.getMessage().contains("inventory not found"));

        var missingProduct = assertThrows(GradleException.class,
                () -> ToolboxInstallations.find(inventory(), "CLion", List.of("CL")));
        assertTrue(missingProduct.getMessage().contains("No usable CLion installation"));
    }

    private Path install(String name, String code, String build) throws IOException {
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        Path home = directory.newFolder(mac ? name + ".app" : name).toPath();
        Path resources = mac ? Files.createDirectories(home.resolve("Contents/Resources")) : home;
        Files.writeString(resources.resolve("product-info.json"),
                JsonOutput.toJson(Map.of("productCode", code, "buildNumber", build)));
        return home;
    }

    private static Map<String, String> entry(String code, Path home) {
        return Map.of("productCode", code, "installLocation", home.toString());
    }

    @SafeVarargs
    private static String inventory(Map<String, String>... entries) {
        return JsonOutput.toJson(Map.of("version", 1, "tools", List.of(entries)));
    }
}
