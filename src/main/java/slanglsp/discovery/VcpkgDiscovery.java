package slanglsp.discovery;

import java.io.IOException;
import java.io.File;
import java.nio.file.*;
import java.util.*;

/** Bounded, read-only discovery. Never invokes vcpkg or descends into installed packages. */
final class VcpkgDiscovery {
    private final String executableName;
    private final String hostTriplet;

    VcpkgDiscovery(String executableName, String os, String arch) {
        this.executableName = executableName;
        String cpu = switch (arch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            case "x86", "i386", "i686" -> "x86";
            default -> arch.toLowerCase(Locale.ROOT);
        };
        String system = os.toLowerCase(Locale.ROOT);
        hostTriplet = cpu + (system.contains("win") && !system.contains("darwin")         ? "-windows"
                                    : system.contains("mac") || system.contains("darwin") ? "-osx"
                                                                                          : "-linux");
    }

    Optional<String> find(Path projectRoot) {
        return find(projectRoot, Map.of());
    }

    Optional<String> find(Path projectRoot, Map<String, String> environment) {
        DiscoveryContext context = new DiscoveryContext(projectRoot, environment);
        // Prefer the project's conventional installations before reading build metadata.
        for (String directory : List.of("vcpkg_installed", "vcpkg/installed", "extern/vcpkg/installed")) {
            Optional<String> found =
                    findInstalledTool(context.root.resolve(directory), environment.get("VCPKG_HOST_TRIPLET"));

            if (found.isPresent()) {
                return found;
            }
        }

        List<DiscoveryAdapter> adapters = List.of(new CmakeDiscoveryAdapter(), new MsbuildDiscoveryAdapter(),
                new XmakeDiscoveryAdapter(), new MesonDiscoveryAdapter());

        for (DiscoveryAdapter adapter : adapters) {
            adapter.discover(context);
        }

        findFallbackInstallations(context);

        for (var installation : context.installations.entrySet()) {
            Optional<String> found = findInstalledTool(installation.getKey(), installation.getValue());

            if (found.isPresent()) {
                return found;
            }
        }

        return Optional.empty();
    }

    private void findFallbackInstallations(DiscoveryContext c) {
        findSubmodules(c);
        c.variables(c.root, c.environment);
        findOnPath(c);
        findUserIntegration(c);
    }

    private void findSubmodules(DiscoveryContext c) {
        // Explicit submodules and environment are fallbacks after build-system metadata.
        var paths = java.util.regex.Pattern.compile("(?m)^\\s*path\\s*=\\s*(.+?)\\s*$")
                            .matcher(c.read(c.root.resolve(".gitmodules")));

        while (paths.find()) {
            Path path = c.resolve(c.root, paths.group(1));
            if (path != null && Files.isRegularFile(path.resolve(".vcpkg-root")))
                c.vcpkgRoot(c.root, path.toString());
        }
    }

    private void findOnPath(DiscoveryContext c) {
        String path = c.environment.get("PATH");

        if (path != null) {
            int count = 0;

            for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (++count > 128)
                    break;
                Path executable = c.resolve(c.root, directory);

                if (executable == null)
                    continue;
                executable = executable.resolve(executableName.endsWith(".exe") ? "vcpkg.exe" : "vcpkg");

                if (!ExecutableLocator.isExecutable(executable))
                    continue;

                try {
                    c.vcpkgRoot(c.root, executable.toRealPath().getParent().toString());
                } catch (IOException | SecurityException ignored) {
                    // Continue to other PATH entries when a link cannot be resolved.
                }
            }
        }
    }

    private void findUserIntegration(DiscoveryContext c) {
        Path local = c.resolve(c.root, c.environment.get("LOCALAPPDATA"));

        if (local != null)
            c.vcpkgRoot(c.root, c.read(local.resolve("vcpkg/vcpkg.path.txt")).trim());
    }

    private Optional<String> findInstalledTool(Path installed, String configuredHost) {
        // Host tools must not come from the target triplet when cross-compiling.
        List<String> triplets = new ArrayList<>();
        if (configuredHost != null && configuredHost.matches("[A-Za-z0-9_-]+"))
            triplets.add(configuredHost);
        triplets.add(hostTriplet);
        try (var children = Files.list(installed)) {
            children.limit(256)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith(hostTriplet + "-"))
                    .sorted()
                    .forEach(triplets::add);
        } catch (IOException | SecurityException ignored) {
        }

        for (String triplet : new LinkedHashSet<>(triplets)) {
            // shader-slang is the official port. slang supports existing overlay ports.
            for (String port : List.of("shader-slang", "slang")) {
                Path tool = installed.resolve(triplet).resolve("tools").resolve(port).resolve(executableName);

                if (ExecutableLocator.isExecutable(tool))
                    return Optional.of(tool.toAbsolutePath().toString());
            }
        }

        return Optional.empty();
    }
}
