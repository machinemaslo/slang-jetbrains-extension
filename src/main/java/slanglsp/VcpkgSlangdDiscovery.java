package slanglsp;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Bounded, read-only discovery. Never invokes vcpkg or descends into installed packages. */
final class VcpkgSlangdDiscovery {
    private static final int MAX_DEPTH = 4;
    private static final int MAX_ENTRIES = 8192;
    private static final Set<String> SKIP = Set.of(
            "vcpkg_installed", "installed", "vcpkg", "node_modules", "CMakeFiles",
            "_deps", "buildtrees", "packages", "downloads", "target");
    private final String executableName;
    private final String hostTriplet;

    VcpkgSlangdDiscovery(String executableName, String os, String arch) {
        this.executableName = executableName;
        String cpu = switch (arch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            case "x86", "i386", "i686" -> "x86";
            default -> arch.toLowerCase(Locale.ROOT);
        };
        String system = os.toLowerCase(Locale.ROOT);
        hostTriplet = cpu + (system.contains("win") && !system.contains("darwin") ? "-windows"
                : system.contains("mac") || system.contains("darwin") ? "-osx" : "-linux");
    }

    Optional<String> find(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        List<Path> manifests = new ArrayList<>();
        List<Path> caches = new ArrayList<>();
        ArrayDeque<Path> pending = new ArrayDeque<>();
        pending.add(root);
        int entries = 0;
        while (!pending.isEmpty() && entries < MAX_ENTRIES) {
            Path directory = pending.removeFirst();
            if (Files.isRegularFile(directory.resolve("vcpkg.json"))) manifests.add(directory);
            if (Files.isRegularFile(directory.resolve("CMakeCache.txt"))) caches.add(directory);
            if (root.relativize(directory).getNameCount() >= MAX_DEPTH && !directory.equals(root)) continue;
            try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
                List<Path> subdirectories = new ArrayList<>();
                for (Path child : children) {
                    if (++entries >= MAX_ENTRIES) break;
                    String name = child.getFileName().toString();
                    if (!name.startsWith(".") && !SKIP.contains(name)
                            && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) subdirectories.add(child);
                }
                subdirectories.sort(Comparator.comparing(Path::toString));
                pending.addAll(subdirectories);
            } catch (IOException | SecurityException ignored) {
                // Missing/unreadable build directories must not prevent PATH fallback.
            }
        }

        LinkedHashMap<Path, String> installations = new LinkedHashMap<>();
        for (Path directory : caches) {
            Map<String, String> cache = readCache(directory.resolve("CMakeCache.txt"));
            String install = cache.getOrDefault("VCPKG_INSTALLED_DIR", cache.get("_VCPKG_INSTALLED_DIR"));
            if (install == null || install.isBlank()) continue;
            Path manifest = resolve(directory, cache.get("VCPKG_MANIFEST_DIR"));
            // An installed Slang tool may be a transitive dependency; no direct manifest entry is required.
            if (manifests.isEmpty() && (manifest == null || !manifest.startsWith(root)
                    || !Files.isRegularFile(manifest.resolve("vcpkg.json")))) continue;
            Path installed = resolve(directory, install);
            if (installed != null) installations.putIfAbsent(installed, cache.get("VCPKG_HOST_TRIPLET"));
        }
        for (Path manifest : manifests) installations.putIfAbsent(manifest.resolve("vcpkg_installed"), null);
        if (!manifests.isEmpty()) {
            for (Path directory : caches) installations.putIfAbsent(directory.resolve("vcpkg_installed"), null);
        }
        for (var installation : installations.entrySet()) {
            Optional<String> found = findInstalledTool(installation.getKey(), installation.getValue());
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    private Optional<String> findInstalledTool(Path installed, String configuredHost) {
        // Host tools must not come from the target triplet when cross-compiling.
        List<String> triplets = new ArrayList<>();
        if (configuredHost != null && configuredHost.matches("[A-Za-z0-9_-]+")) triplets.add(configuredHost);
        triplets.add(hostTriplet);
        try (var children = Files.list(installed)) {
            children.limit(256).map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith(hostTriplet + "-"))
                    .sorted().forEach(triplets::add);
        } catch (IOException | SecurityException ignored) { }
        for (String triplet : new LinkedHashSet<>(triplets)) {
            // shader-slang is the official port. slang supports existing overlay ports.
            for (String port : List.of("shader-slang", "slang")) {
                Path tool = installed.resolve(triplet).resolve("tools").resolve(port).resolve(executableName);
                if (SlangExecutableLocator.isExecutable(tool)) return Optional.of(tool.toAbsolutePath().toString());
            }
        }
        return Optional.empty();
    }

    private static Path resolve(Path directory, String value) {
        if (value == null || value.isBlank() || value.contains("${")) return null;
        try {
            return directory.resolve(value).toAbsolutePath().normalize();
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private static Map<String, String> readCache(Path path) {
        Map<String, String> result = new HashMap<>();
        try (var reader = Files.newBufferedReader(path)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && ++count <= 20000) {
                int colon = line.indexOf(':');
                int equals = line.indexOf('=');
                if (colon > 0 && equals > colon) {
                    String key = line.substring(0, colon);
                    if (key.startsWith("VCPKG_") || key.equals("_VCPKG_INSTALLED_DIR"))
                        result.put(key, line.substring(equals + 1));
                }
            }
        } catch (IOException | SecurityException ignored) { }
        return result;
    }
}
