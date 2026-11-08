package slanglsp.discovery;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;
import java.util.function.Supplier;

public final class ExecutableLocator {
    public static Optional<String> find(String explicitDirectory, boolean useVcpkg,
                                 Supplier<Optional<String>> vcpkg, String path, String vulkanSdk, String executableName) {
        Optional<String> explicit = inDirectory(explicitDirectory, executableName);
        if (explicit.isPresent()) return explicit;
        if (useVcpkg) {
            Optional<String> local = vcpkg.get();
            if (local.isPresent()) return local;
        }
        Optional<String> sdk = inDirectory(vulkanSdk,
                (executableName.endsWith(".exe") ? "Bin/" : "bin/") + executableName);
        if (sdk.isPresent()) return sdk;
        if (path != null) {
            for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                Optional<String> found = inDirectory(directory, executableName);
                if (found.isPresent()) return found;
            }
            // A PATH entry may expose only a symlink to the compiler in a full SDK.
            String compilerName = executableName.endsWith(".exe") ? "slangc.exe" : "slangc";
            for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                Optional<String> compiler = inDirectory(directory, compilerName);
                if (compiler.isEmpty()) continue;
                try {
                    Path sdkDirectory = Path.of(compiler.get()).toRealPath().getParent();
                    Optional<String> found = inDirectory(sdkDirectory.toString(), executableName);
                    if (found.isPresent()) return found;
                } catch (IOException | SecurityException ignored) {
                    // Broken/inaccessible compiler links must not block later PATH entries.
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> inDirectory(String directory, String executableName) {
        if (directory == null || directory.isBlank()) return Optional.empty();
        try {
            Path executable = Path.of(directory).resolve(executableName);
            return isExecutable(executable) ? Optional.of(executable.toAbsolutePath().toString()) : Optional.empty();
        } catch (InvalidPathException | SecurityException ignored) {
            return Optional.empty();
        }
    }

    static boolean isExecutable(Path executable) {
        return Files.isRegularFile(executable) && Files.isExecutable(executable);
    }
}
