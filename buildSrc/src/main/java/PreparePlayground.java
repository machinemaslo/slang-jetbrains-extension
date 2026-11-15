import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Seeds once so edits, dictionary additions and formatting survive subsequent launches. */
public abstract class PreparePlayground extends DefaultTask {
    @InputDirectory public abstract DirectoryProperty getTemplateDirectory();
    @OutputDirectory public abstract DirectoryProperty getPlaygroundDirectory();
    @Input public abstract Property<String> getSlangdCandidate();
    @Input public abstract Property<String> getClangFormatCandidate();
    @Input public abstract Property<String> getSearchPath();

    @TaskAction
    public void prepare() throws IOException {
        Path source = getTemplateDirectory().get().getAsFile().toPath();
        Path target = getPlaygroundDirectory().get().getAsFile().toPath();
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path destination = target.resolve(source.relativize(path));
                Files.createDirectories(destination.getParent());
                if (!Files.exists(destination)) Files.copy(path, destination);
            }
        }
        // Migrate the original generated module without resetting playground edits.
        Path module = target.resolve(".idea/playground.iml");
        if (Files.exists(module)) {
            String content = Files.readString(module);
            String corrected = content.replace("file://$MODULE_DIR$/..", "file://$MODULE_DIR$")
                    .replace("file://$PROJECT_DIR$", "file://$MODULE_DIR$");
            if (!content.equals(corrected)) Files.writeString(module, corrected);
        }
        Path settings = target.resolve(".idea/SlangPluginSettings.xml");
        // Remove the obsolete formatting inspection from previously seeded playgrounds.
        Path profile = target.resolve(".idea/inspectionProfiles/Project_Default.xml");
        if (Files.exists(profile)) {
            String content = Files.readString(profile);
            String corrected = content.replaceAll("(?m)^\\s*<inspection_tool class=\"SlangFormatting\"[^>]*/>\\s*\\R?", "");
            if (!content.equals(corrected)) Files.writeString(profile, corrected);
        }
        if (!Files.exists(settings)) {
            String slangd = findExecutable(getSlangdCandidate().get(), "slangd");
            String clang = findExecutable(getClangFormatCandidate().get(), "clang-format");
            if (slangd.isEmpty()) {
                throw new IllegalStateException("Playground requires installed slangd on PATH or in vcpkg_installed. "
                        + "Install/configure it and rerun Run Plugin; no native tools are installed by this task.");
            }
            Files.writeString(settings, """
                    <project version="4">
                      <component name="SlangPersistentStateComponentConfig">
                        <option name="explicitSlangdLocation" value="%s" />
                        <option name="clangFormatLocation" value="%s" />
                      </component>
                    </project>
                    """.formatted(xml(slangdDirectory(target, slangd)), xml(clang)));
        } else {
            // Repair the original seed, which incorrectly supplied a file to a directory setting.
            String content = Files.readString(settings);
            String candidate = getSlangdCandidate().get();
            String corrected = content.replace(
                    "name=\"explicitSlangdLocation\" value=\"" + xml(candidate) + "\"",
                    "name=\"explicitSlangdLocation\" value=\"" + xml(slangdDirectory(target, candidate)) + "\"");
            if (!content.equals(corrected)) Files.writeString(settings, corrected);
        }
        getLogger().lifecycle("Slang playground: {}", target);
    }

    private String slangdDirectory(Path project, String executable) {
        Path directory = Path.of(executable).toAbsolutePath().normalize().getParent();
        if (Path.of(executable).equals(Path.of(getSlangdCandidate().get()))) {
            return project.toAbsolutePath().normalize().relativize(directory).toString().replace('\\', '/');
        }
        return directory.toString();
    }

    private String findExecutable(String candidate, String name) {
        if (Files.isExecutable(Path.of(candidate))) return candidate;
        String executable = System.getProperty("os.name").startsWith("Windows") ? name + ".exe" : name;
        for (String entry : getSearchPath().get().split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) continue;
            Path path = Path.of(entry).resolve(executable);
            if (Files.isRegularFile(path) && Files.isExecutable(path)) return path.toAbsolutePath().toString();
        }
        return "";
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
