import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Copies appearance components into the sandbox before startup. */
public abstract class SyncSandboxAppearance extends DefaultTask {
    @Input public abstract Property<String> getUserConfigRoot();
    @Internal public abstract DirectoryProperty getSandboxConfigDirectory();

    @TaskAction
    public void sync() throws Exception {
        Path root = Path.of(getUserConfigRoot().get());
        if (!Files.isDirectory(root)) return;
        Path source;
        // Prefer the most recently used IDEA configuration (including Community).
        try (var directories = Files.list(root)) {
            source = directories.filter(path -> path.getFileName().toString().matches("(IntelliJIdea|IdeaIC)\\d.*"))
                    .filter(path -> Files.isRegularFile(path.resolve("options/other.xml")))
                    .max(Comparator.comparingLong(path -> path.resolve("options/other.xml").toFile().lastModified()))
                    .orElse(null);
        }
        if (source == null) return;
        Path target = getSandboxConfigDirectory().get().getAsFile().toPath();
        for (String file : List.of("other.xml", "ui.lnf.xml", "laf.xml", "editor-font.xml", "console-font.xml")) {
            Path input = source.resolve("options").resolve(file);
            if (!Files.isRegularFile(input)) continue;
            Path output = target.resolve("options").resolve(file);
            var factory = DocumentBuilderFactory.newInstance();
            // This is the parser's exact feature identifier, not a fetched URL; HTTPS is not an alias.
            //noinspection HttpUrlsUsage
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var builder = factory.newDocumentBuilder();
            var from = builder.parse(input.toFile());
            var to = Files.exists(output) ? builder.parse(output.toFile()) : builder.newDocument();
            if (to.getDocumentElement() == null) to.appendChild(to.createElement("application"));
            boolean changed = false;
            var components = from.getDocumentElement().getElementsByTagName("component");
            for (int i = 0; i < components.getLength(); i++) {
                var component = (Element) components.item(i);
                String name = component.getAttribute("name");
                if (!List.of("NotRoamableUiSettings", "UISettings", "LafManager",
                        "DefaultFont", "ConsoleFont").contains(name)) continue;
                var existing = to.getDocumentElement().getElementsByTagName("component");
                for (int j = existing.getLength() - 1; j >= 0; j--) {
                    var old = (Element) existing.item(j);
                    if (old.getAttribute("name").equals(name)) old.getParentNode().removeChild(old);
                }
                to.getDocumentElement().appendChild(to.importNode(component, true));
                changed = true;
            }
            if (changed) {
                Files.createDirectories(output.getParent());
                var transformer = TransformerFactory.newInstance().newTransformer();
                transformer.transform(new DOMSource(to), new StreamResult(output.toFile()));
            }
        }
        getLogger().lifecycle("Sandbox appearance inherited from {}", source);
    }
}
