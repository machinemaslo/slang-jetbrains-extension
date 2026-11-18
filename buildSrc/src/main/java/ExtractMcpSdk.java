import org.gradle.api.GradleException;
import org.gradle.api.artifacts.transform.CacheableTransform;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipFile;

/** Exposes JetBrains' MCP API and descriptor at dependency resolution time, including IDE import. */
@CacheableTransform
public abstract class ExtractMcpSdk implements TransformAction<TransformParameters.None> {
    @InputArtifact
    @PathSensitive(PathSensitivity.NONE)
    public abstract Provider<FileSystemLocation> getArchive();

    @Override
    public void transform(@NonNull TransformOutputs outputs) {
        var archive = getArchive().get().getAsFile();
        try (var zip = new ZipFile(archive)) {
            for (String name : List.of("intellij.mcpserver.jar", "mcpserver-plugin.jar")) {
                String path = "plugins/mcpserver-plugin/lib/" + name;
                var entry = zip.stream().filter(item -> item.getName().equals(path)
                        || item.getName().endsWith("/" + path)).findFirst()
                        .orElseThrow(() -> new GradleException("Missing " + path + " in " + archive));
                try (var input = zip.getInputStream(entry)) {
                    Files.copy(input, outputs.file(name).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException failure) {
            throw new GradleException("Could not extract the JetBrains MCP SDK from " + archive, failure);
        }
    }
}
