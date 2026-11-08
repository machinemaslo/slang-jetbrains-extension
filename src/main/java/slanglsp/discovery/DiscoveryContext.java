package slanglsp.discovery;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Shared, bounded metadata reads. Adapters never execute project code or walk directory trees. */
final class DiscoveryContext {
    final Path root;
    final Map<String, String> environment;
    final LinkedHashMap<Path, String> installations = new LinkedHashMap<>();
    final Set<Path> buildDirectories = new LinkedHashSet<>();
    private final Map<Path, String> texts = new HashMap<>();
    private int remainingBytes = 8 * 1024 * 1024;

    DiscoveryContext(Path root, Map<String, String> environment) {
        this.root = root.toAbsolutePath().normalize();
        this.environment = Map.copyOf(environment);
        buildDirectories.add(this.root);
        buildDirectories.add(this.root.resolve("build"));
    }

    String read(Path file) {
        file = file.toAbsolutePath().normalize();

        if (texts.containsKey(file)) {
            return texts.get(file);
        }

        if (texts.size() >= 128 || remainingBytes <= 0) {
            return "";
        }

        String text = readBounded(file);
        texts.put(file, text);

        return text;
    }

    private String readBounded(Path file) {
        int limit = Math.min(remainingBytes, 2 * 1024 * 1024);

        try {
            if (!Files.isRegularFile(file) || Files.size(file) > limit) {
                return "";
            }

            try (var stream = Files.newInputStream(file)) {
                byte[] bytes = stream.readNBytes(limit + 1);
                remainingBytes -= bytes.length;

                if (bytes.length <= 2 * 1024 * 1024 && remainingBytes >= 0) {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException | SecurityException ignored) {
            // Missing or inaccessible metadata does not block later discovery sources.
        }

        return "";
    }

    JsonElement json(Path file) {
        try {
            return JsonParser.parseString(read(file));
        } catch (RuntimeException ignored) {
            return com.google.gson.JsonNull.INSTANCE;
        }
    }

    Document xml(Path file) {
        String text = read(file);

        if (text.isBlank()) {
            return null;
        }

        try {
            var factory = DocumentBuilderFactory.newInstance();
            // This is the parser's feature identifier, not a network request.
            //noinspection HttpUrlsUsage
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());

            return builder.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return null;
        }
    }

    List<Path> children(Path directory, String glob) {
        try (var files = Files.newDirectoryStream(directory, glob)) {
            List<Path> result = new ArrayList<>();

            for (Path file : files) {
                if (result.size() == 256) {
                    break;
                }

                result.add(file);
            }

            result.sort(Comparator.comparing(Path::toString));

            return result;
        } catch (IOException | SecurityException ignored) {
            return List.of();
        }
    }

    Path resolve(Path base, String value) {
        if (value == null || value.isBlank() || value.contains("$") || value.contains("%(") || value.contains("%") ||
                value.contains("~") || value.contains("\n")) {
            return null;
        }

        try {
            return base.resolve(value.replace('\\', '/')).toAbsolutePath().normalize();
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    void installed(Path base, String value, String host) {
        Path path = resolve(base, value);

        if (path != null && installations.size() < 128) {
            installations.putIfAbsent(path, host);
        }
    }

    void vcpkgRoot(Path base, String value) {
        Path path = resolve(base, value);

        if (path != null) {
            if (path.getFileName() != null && Set.of("vcpkg", "vcpkg.exe").contains(path.getFileName().toString()) &&
                    Files.isRegularFile(path))
                path = path.getParent();
            installed(path, "installed", null);
        }
    }

    void buildDirectory(Path base, String value) {
        Path path = resolve(base, value);

        if (path != null && buildDirectories.size() < 32) {
            buildDirectories.add(path);
        }
    }

    static String expand(String value, Map<String, String> variables) {
        if (value == null) {
            return null;
        }

        Pattern pattern = Pattern.compile("\\$\\(([^)]+)\\)|\\$\\{([^}]+)}");

        for (int i = 0; i < 8; i++) {
            String next = pattern.matcher(value).replaceAll(m
                    -> java.util.regex.Matcher.quoteReplacement(Objects.requireNonNullElse(
                            variables.get(m.group(1) == null ? m.group(2) : m.group(1)), m.group())));

            if (next.length() > 8192) {
                return null;
            }

            if (next.equals(value)) {
                break;
            }

            value = next;
        }

        return value;
    }

    void variables(Path base, Map<String, String> values) {
        String host = values.get("VCPKG_HOST_TRIPLET");

        for (String key : List.of("VCPKG_INSTALLED_DIR", "_VCPKG_INSTALLED_DIR")) {
            installed(base, expand(values.get(key), values), host);
        }

        for (String key : List.of("VCPKG_ROOT", "VCPKG_INSTALLATION_ROOT")) {
            vcpkgRoot(base, expand(values.get(key), values));
        }

        String toolchain = expand(values.get("CMAKE_TOOLCHAIN_FILE"), values);

        if (toolchain != null) {
            String normalized = toolchain.replace('\\', '/');
            String suffix = "/scripts/buildsystems/vcpkg.cmake";

            if (normalized.endsWith(suffix))
                vcpkgRoot(base, normalized.substring(0, normalized.length() - suffix.length()));
        }
    }

    // Compilation metadata supplies a triplet include/lib path, including custom install roots.
    void dependencyPath(Path base, String value) {
        Path path = resolve(base, value);

        for (int i = 0; path != null && i < 6; i++, path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("vcpkg/status"))) {
                installed(path, ".", null);
                return;
            }
        }
    }
}
