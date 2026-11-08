package slanglsp.discovery;

import com.google.gson.*;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

final class CmakeDiscoveryAdapter implements DiscoveryAdapter {
    private final Map<String, JsonObject> presets = new LinkedHashMap<>();
    private final Set<Path> files = new HashSet<>();

    @Override
    public void discover(DiscoveryContext c) {
        loadPresets(c);
        findBuildDirectories(c);
        readCaches(c);
        readPresetInstallations(c);
    }

    private void loadPresets(DiscoveryContext c) {
        load(c, c.root.resolve("CMakePresets.json"));
        load(c, c.root.resolve("CMakeUserPresets.json"));
    }

    private void findBuildDirectories(DiscoveryContext c) {
        for (String name : presets.keySet()) {
            JsonObject preset = merged(name, new HashSet<>());
            if ("true".equals(string(preset.get("hidden")))) {
                continue;
            }

            c.buildDirectory(c.root, expand(c, name, preset, string(preset.get("binaryDir"))));
        }

        Set<Path> profileDirectories = readIdeProfiles(c);
        profileDirectories.addAll(c.buildDirectories);
        c.buildDirectories.clear();
        profileDirectories.stream().limit(32).forEach(c.buildDirectories::add);

        // Top-level build directories only; never a recursive project scan.
        for (Path directory : c.children(c.root, "cmake-build-*")) {
            c.buildDirectory(c.root, directory.toString());
        }
    }

    private Set<Path> readIdeProfiles(DiscoveryContext c) {
        Set<Path> profileDirectories = new LinkedHashSet<>();
        for (String file : List.of(".idea/workspace.xml", ".idea/cmake.xml")) {
            var document = c.xml(c.root.resolve(file));

            if (document == null) {
                continue;
            }

            var profiles = document.getElementsByTagName("configuration");

            for (int i = 0; i < profiles.getLength() && i < 32; i++) {
                var profile = (Element)profiles.item(i);
                if (profile.getAttribute("ENABLED").equalsIgnoreCase("false")) {
                    continue;
                }

                String directory = profile.getAttribute("GENERATION_DIR");

                if (directory.isBlank()) {
                    directory = profile.getAttribute("generationDir");
                }

                Path build = c.resolve(c.root, directory.replace("$PROJECT_DIR$", c.root.toString()));

                if (build != null) {
                    profileDirectories.add(build);
                }
            }
        }

        return profileDirectories;
    }

    private void readCaches(DiscoveryContext c) {
        for (Path directory : List.copyOf(c.buildDirectories)) {
            Map<String, String> cache = new HashMap<>();
            for (String line : c.read(directory.resolve("CMakeCache.txt")).split("\\R")) {
                int colon = line.indexOf(':');
                int equals = line.indexOf('=');

                if (colon > 0 && equals > colon) {
                    cache.put(line.substring(0, colon), line.substring(equals + 1));
                }
            }

            c.variables(directory, cache);

            if (!cache.isEmpty()) {
                c.installed(directory, "vcpkg_installed", cache.get("VCPKG_HOST_TRIPLET"));
            }
        }
    }

    // Resolved caches take precedence over initial preset values.
    private void readPresetInstallations(DiscoveryContext c) {
        for (String name : presets.keySet()) {
            JsonObject preset = merged(name, new HashSet<>());
            if ("true".equals(string(preset.get("hidden")))) {
                continue;
            }

            Map<String, String> values = new HashMap<>();
            if (preset.get("cacheVariables") instanceof JsonObject variables) {
                variables.entrySet().forEach(e -> {
                    JsonElement value = e.getValue();

                    if (value instanceof JsonObject object)
                        value = object.get("value");
                    String expanded = expand(c, name, preset, string(value));

                    if (expanded != null)
                        values.put(e.getKey(), expanded);
                });
            }
            String toolchain = expand(c, name, preset, string(preset.get("toolchainFile")));

            if (toolchain != null)
                values.put("CMAKE_TOOLCHAIN_FILE", toolchain);
            Path build = c.resolve(c.root, expand(c, name, preset, string(preset.get("binaryDir"))));
            c.variables(build == null ? c.root : build, values);

            if (build != null)
                c.installed(build, "vcpkg_installed", values.get("VCPKG_HOST_TRIPLET"));
        }
    }

    private void load(DiscoveryContext c, Path file) {
        file = file.toAbsolutePath().normalize();

        if (files.size() >= 16 || !files.add(file))
            return;

        if (!(c.json(file) instanceof JsonObject object))
            return;
        for (JsonElement include : array(object.get("include"))) {
            String value = expand(c, "", new JsonObject(), string(include));
            Path path = c.resolve(file.getParent(), value);

            if (path != null)
                load(c, path);
        }
        for (JsonElement element : array(object.get("configurePresets"))) {
            if (element instanceof JsonObject preset && string(preset.get("name")) != null && presets.size() < 32)
                presets.put(string(preset.get("name")), preset);
        }
    }

    private JsonObject merged(String name, Set<String> visiting) {
        JsonObject result = new JsonObject();
        JsonObject own = presets.get(name);

        if (own == null || !visiting.add(name) || visiting.size() > 16)
            return result;
        List<JsonElement> parents = array(own.get("inherits"));

        for (int i = parents.size() - 1; i >= 0; i--)
            merge(result, merged(string(parents.get(i)), visiting));
        merge(result, own);
        // hidden is not inherited by CMake.
        if (!own.has("hidden"))
            result.remove("hidden");
        visiting.remove(name);

        return result;
    }

    private static void merge(JsonObject target, JsonObject source) {
        source.entrySet().forEach(e -> {
            if (e.getValue() instanceof JsonObject nested && target.get(e.getKey()) instanceof JsonObject previous)
                merge(previous, nested);
            else
                target.add(e.getKey(), e.getValue().deepCopy());
        });
    }

    private static String expand(DiscoveryContext c, String name, JsonObject preset, String value) {
        if (value == null)
            return null;
        Map<String, String> environment = new HashMap<>(c.environment);
        if (preset.get("environment") instanceof JsonObject overrides)
            overrides.entrySet().forEach(e -> {
                if (string(e.getValue()) != null)
                    environment.put(e.getKey(), string(e.getValue()));
            });
        Pattern env = Pattern.compile("\\$(p?env)\\{([^}]+)}");

        for (int i = 0; i < 8; i++) {
            String next = env.matcher(value).replaceAll(m
                    -> java.util.regex.Matcher.quoteReplacement(
                            (m.group(1).equals("penv") ? c.environment : environment)
                                    .getOrDefault(m.group(2), m.group())));

            if (next.length() > 8192)
                return null;

            if (next.equals(value))
                break;
            value = next;
        }

        Map<String, String> macros = new HashMap<>();
        macros.put("sourceDir", c.root.toString());

        if (c.root.getParent() != null)
            macros.put("sourceParentDir", c.root.getParent().toString());

        if (c.root.getFileName() != null)
            macros.put("sourceDirName", c.root.getFileName().toString());
        macros.put("presetName", name);

        return DiscoveryContext.expand(value, macros);
    }

    static String string(JsonElement value) {
        return value instanceof JsonPrimitive primitive ? primitive.getAsString() : null;
    }

    static List<JsonElement> array(JsonElement value) {
        if (value instanceof JsonArray array) {
            List<JsonElement> values = new ArrayList<>();
            array.forEach(values::add);

            return values;
        }

        return value instanceof JsonPrimitive ? List.of(value) : List.of();
    }
}
