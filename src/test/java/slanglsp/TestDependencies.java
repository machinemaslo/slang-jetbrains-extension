package slanglsp;

import java.nio.file.Files;
import java.nio.file.Path;

/** Native dependencies provisioned by Gradle's installTestDependencies task. */
public final class TestDependencies {
    private TestDependencies() {
    }

    public static String slangd() {
        return executable("slang.test.executable");
    }

    public static Path vcpkgRoot() {
        return Path.of(requiredProperty("slang.test.vcpkgRoot"));
    }

    public static String clangFormat() {
        return executable("slang.test.clangFormat");
    }

    public static String baseline() throws java.io.IOException {
        try (var reader = Files.newBufferedReader(Path.of(requiredProperty("slang.test.manifest")))) {
            return com.google.gson.JsonParser.parseReader(reader).getAsJsonObject()
                    .get("builtin-baseline").getAsString();
        }
    }

    private static String executable(String property) {
        Path executable = Path.of(requiredProperty(property));
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IllegalStateException("Missing test executable at " + executable + "; run ./gradlew test");
        }
        return executable.toString();
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + name + "; run tests through Gradle with VCPKG_ROOT set");
        }
        return value;
    }
}
