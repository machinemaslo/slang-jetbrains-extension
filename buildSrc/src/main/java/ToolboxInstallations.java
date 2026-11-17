import groovy.json.JsonException;
import groovy.json.JsonSlurper;
import org.gradle.api.GradleException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Resolves installed IDEs from Toolbox's local inventory without scanning install directories. */
public final class ToolboxInstallations {
    private ToolboxInstallations() {
    }

    public static File find(String inventory, String ideName, List<String> productCodes) {
        if (inventory.isBlank()) {
            throw new GradleException("JetBrains Toolbox inventory not found. Open Toolbox and install " + ideName + ".");
        }

        var parser = new JsonSlurper();
        Object state;
        try {
            state = parser.parseText(inventory);
        } catch (JsonException failure) {
            throw new GradleException("Could not read JetBrains Toolbox state.json. Reopen Toolbox and try again.", failure);
        }
        if (!(state instanceof Map<?, ?> root) || !(root.get("tools") instanceof List<?> tools)) {
            throw new GradleException("JetBrains Toolbox state.json has an unrecognized format. Reopen Toolbox and try again.");
        }

        Path selected = null;
        int[] selectedBuild = {};
        for (Object item : tools) {
            if (!(item instanceof Map<?, ?> tool) || !(tool.get("productCode") instanceof String code)
                    || !productCodes.contains(code) || !(tool.get("installLocation") instanceof String location)) {
                continue;
            }
            try {
                Path home = Path.of(location).normalize();
                if (!home.isAbsolute()) {
                    continue;
                }
                Path metadata = home.resolve("product-info.json");
                if (home.getFileName() != null && home.getFileName().toString().endsWith(".app")) {
                    metadata = home.resolve("Contents/Resources/product-info.json");
                }
                if (!Files.isRegularFile(metadata)) {
                    continue;
                }

                Object product = parser.parseText(Files.readString(metadata));
                if (!(product instanceof Map<?, ?> info) || !code.equals(info.get("productCode"))
                        || !(info.get("buildNumber") instanceof String version)) {
                    continue;
                }
                int[] build = Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
                if (selected == null || Arrays.compare(build, selectedBuild) > 0) {
                    selected = home;
                    selectedBuild = build;
                }
            } catch (IOException | InvalidPathException | NumberFormatException | JsonException ignored) {
                // A stale entry or an installation being updated must not hide another installed copy.
            }
        }
        if (selected == null) {
            throw new GradleException("No usable " + ideName + " installation in JetBrains Toolbox. Open Toolbox and install or repair it.");
        }
        return selected.toFile();
    }
}
