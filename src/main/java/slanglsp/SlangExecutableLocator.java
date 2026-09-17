package slanglsp;

import java.io.File;
import java.nio.file.*;
import java.util.Optional;
import java.util.function.Supplier;

final class SlangExecutableLocator {
    static Optional<String> find(String explicitDirectory, boolean useVcpkg,
                                 Supplier<Optional<String>> vcpkg, String path, String executableName) {
        Optional<String> explicit = inDirectory(explicitDirectory, executableName);
        if (explicit.isPresent()) return explicit;
        if (useVcpkg) {
            Optional<String> local = vcpkg.get();
            if (local.isPresent()) return local;
        }
        if (path != null) {
            for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                Optional<String> found = inDirectory(directory, executableName);
                if (found.isPresent()) return found;
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
