package slanglsp.discovery;

import org.w3c.dom.Element;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Reads literal MSBuild properties/imports. Conditions and property functions are not evaluated. */
final class MsbuildDiscoveryAdapter implements DiscoveryAdapter {
    @Override
    public void discover(DiscoveryContext c) {
        Set<Path> projects = findProjects(c);

        if (projects.isEmpty()) {
            projects.add(c.root.resolve("Directory.Build.props"));
        }

        for (Path project : projects.stream().limit(32).toList()) {
            Map<String, String> properties = readProjectProperties(c, project);
            findInstallations(c, project, properties);
        }
    }

    private Set<Path> findProjects(DiscoveryContext c) {
        Set<Path> projects = new LinkedHashSet<>(c.children(c.root, "*.vcxproj"));
        projects.addAll(c.children(c.root, "*.csproj"));
        for (Path solution : c.children(c.root, "*.slnx")) {
            var xml = c.xml(solution);

            if (xml == null) {
                continue;
            }

            var nodes = xml.getElementsByTagName("Project");

            for (int i = 0; i < nodes.getLength() && projects.size() < 32; i++) {
                Path path = c.resolve(solution.getParent(), ((Element)nodes.item(i)).getAttribute("Path"));

                if (path != null) {
                    projects.add(path);
                }
            }
        }
        for (Path solution : c.children(c.root, "*.sln")) {
            var matches =
                    Pattern.compile("(?m)^Project\\([^\\r\\n]+?=\\s*\"[^\"]*\",\\s*\"([^\"]+\\.(?:vcxproj|csproj))\"")
                            .matcher(c.read(solution));

            while (matches.find() && projects.size() < 32) {
                Path path = c.resolve(solution.getParent(), matches.group(1));

                if (path != null) {
                    projects.add(path);
                }
            }
        }

        return projects;
    }

    private Map<String, String> readProjectProperties(DiscoveryContext c, Path project) {
        Map<String, String> properties = new HashMap<>(c.environment);
        properties.put("MSBuildProjectDirectory", project.getParent().toString());
        properties.put("SolutionDir", c.root + "/");
        Set<Path> visited = new HashSet<>();
        // MSBuild uses the nearest Directory.Build.props/targets for a project.
        for (String file : List.of("Directory.Build.props", "Directory.Build.targets")) {
            if (file.endsWith("targets")) {
                read(c, project, properties, visited);
            }

            Path directory = project.getParent();

            for (int i = 0; directory != null && i < 8; i++, directory = directory.getParent()) {
                Path candidate = directory.resolve(file);

                if (!c.read(candidate).isBlank()) {
                    read(c, candidate, properties, visited);
                    break;
                }
            }
        }

        return properties;
    }

    private void findInstallations(DiscoveryContext c, Path project, Map<String, String> properties) {
        String host = properties.get("VcpkgHostTriplet");
        String install = expand(properties.get("VcpkgInstalledDir"), properties);
        Path installed = c.resolve(project.getParent(), install);

        if (installed != null) {
            // In manifest mode this property includes the target triplet, unlike CMake's root.

            if (installed.getFileName() != null &&
                    installed.getFileName().toString().equals(properties.get("VcpkgTriplet")))
                installed = installed.getParent();
            c.installed(installed, ".", host);
        }
        c.installed(project.getParent(), expand(properties.get("VcpkgManifestInstalledBaseDir"), properties), host);
        Path manifest = c.resolve(project.getParent(), expand(properties.get("VcpkgManifestRoot"), properties));

        if (manifest != null) {
            c.installed(manifest, "vcpkg_installed", host);
        }

        c.installed(project.getParent(), "vcpkg_installed", host);
        c.vcpkgRoot(project.getParent(), expand(properties.get("VcpkgRoot"), properties));
    }

    private static String expand(String value, Map<String, String> properties) {
        return DiscoveryContext.expand(value, properties);
    }

    private void read(DiscoveryContext c, Path file, Map<String, String> properties, Set<Path> visited) {
        file = file.toAbsolutePath().normalize();

        if (visited.size() >= 24 || !visited.add(file)) {
            return;
        }

        var document = c.xml(file);

        if (document == null) {
            return;
        }

        String previous = properties.put("MSBuildThisFileDirectory", file.getParent() + "/");

        for (var node = document.getDocumentElement().getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element) || !element.getAttribute("Condition").isBlank())
                continue;
            if (element.getTagName().equals("PropertyGroup")) {
                for (var property = element.getFirstChild(); property != null; property = property.getNextSibling()) {
                    if (property instanceof Element e && e.getAttribute("Condition").isBlank()) {
                        String value = expand(e.getTextContent().trim(), properties);
                        properties.put(e.getTagName(), value);
                    }
                }
            } else if (element.getTagName().equals("Import")) {
                Path imported = c.resolve(file.getParent(), expand(element.getAttribute("Project"), properties));

                if (imported == null) {
                    continue;
                }

                String normalized = imported.toString().replace('\\', '/');
                String suffix = "/scripts/buildsystems/msbuild/vcpkg.props";

                if (normalized.endsWith(suffix))
                    properties.put("VcpkgRoot", normalized.substring(0, normalized.length() - suffix.length()));
                else
                    read(c, imported, properties, visited);
            }
        }

        if (previous == null) {
            properties.remove("MSBuildThisFileDirectory");
        } else {
            properties.put("MSBuildThisFileDirectory", previous);
        }
    }
}
