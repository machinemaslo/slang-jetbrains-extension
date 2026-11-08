package slanglsp.discovery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Builds project metadata and package trees shared by the discovery bundle. */
final class VcpkgProjectFixture {
    private static final String BASELINE = "e6f9e70a29a3e80a1fc510d8503304315447112f";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    enum BuildSystem {
        CMAKE,
        MSBUILD,
        XMAKE,
        MESON
    }

    private final Path temporary;

    VcpkgProjectFixture(Path temporary) {
        this.temporary = temporary;
    }

    Path createTool(Path installed, String triplet, String executable) throws Exception {
        write(installed.resolve("vcpkg/status"), """
                Package: shader-slang
                Architecture: %s
                Status: install ok installed
                """.formatted(triplet));

        Path tool = write(
                installed.resolve(triplet + "/tools/shader-slang/" + executable),
                "discovery fixture; never executed\n"
        );
        assertTrue(tool.toFile().setExecutable(true));

        return tool;
    }

    Path createProject(BuildSystem system, Path installed, String triplet) throws Exception {
        Path root = temporary.resolve(system.name().toLowerCase(Locale.ROOT) + " project");
        writeJson(root.resolve("vcpkg.json"), Map.of(
                "name", "discovery-bundle",
                "version-string", "0.1.0",
                "builtin-baseline", BASELINE,
                "dependencies", List.of("shader-slang")
        ));
        write(root.resolve("main.cpp"), "int main() { return 0; }\n");

        switch (system) {
            case CMAKE -> createCmake(root, installed, triplet);
            case MSBUILD -> createMsbuild(root, installed, triplet);
            case XMAKE -> createXmake(root, installed);
            case MESON -> createMeson(root, installed, triplet);
        }

        return root;
    }

    private void createCmake(Path root, Path installed, String triplet) throws Exception {
        write(root.resolve("CMakeLists.txt"), """
                cmake_minimum_required(VERSION 3.25)
                project(discovery_bundle LANGUAGES NONE)
                """);

        Map<String, String> variables = Map.of(
                "VCPKG_INSTALLED_DIR", portablePath(installed),
                "VCPKG_HOST_TRIPLET", triplet
        );
        Map<String, Object> preset = Map.of(
                "name", "bundle",
                "generator", "Ninja",
                "binaryDir", "${sourceDir}/build",
                "cacheVariables", variables
        );

        writeJson(root.resolve("CMakePresets.json"), Map.of(
                "version", 3,
                "configurePresets", List.of(preset)
        ));
    }

    private void createMsbuild(Path root, Path installed, String triplet) throws Exception {
        write(root.resolve("bundle.slnx"), """
                <Solution>
                    <Project Path="src/bundle.vcxproj"/>
                </Solution>
                """);
        write(root.resolve("src/bundle.vcxproj"), """
                <Project>
                    <Import Project="../dependencies.props"/>
                </Project>
                """);
        write(root.resolve("dependencies.props"), """
                <Project>
                    <PropertyGroup>
                        <VcpkgManifestInstalledBaseDir>%s</VcpkgManifestInstalledBaseDir>
                        <VcpkgHostTriplet>%s</VcpkgHostTriplet>
                    </PropertyGroup>
                </Project>
                """.formatted(escapeXml(portablePath(installed)), triplet));
    }

    private void createXmake(Path root, Path installed) throws Exception {
        write(root.resolve("xmake.lua"), """
                add_rules("mode.debug", "mode.release")
                add_requires("vcpkg::shader-slang")

                target("bundle")
                    set_kind("binary")
                    add_files("main.cpp")
                """);

        // Saved configuration fixture, not the output of an Xmake invocation.
        String vcpkg = JSON.toJson(portablePath(installed.getParent()));
        write(root.resolve(".xmake/linux/x86_64/xmake.conf"), """
                {
                    vcpkg = %s,
                }
                """.formatted(vcpkg));
    }

    private void createMeson(Path root, Path installed, String triplet) throws Exception {
        write(root.resolve("meson.build"), """
                project('discovery-bundle', 'cpp')
                slang = dependency('slang', method: 'pkg-config')
                """);

        // Saved introspection fixture, not the output of a Meson invocation.
        String include = portablePath(installed) + "/" + triplet + "/include";
        Map<String, Object> dependency = Map.of(
                "name", "slang",
                "compile_args", List.of("-I", include)
        );

        writeJson(root.resolve("builddir/meson-info/intro-dependencies.json"), List.of(dependency));
    }

    // Process is not AutoCloseable on our Java 21 baseline; cleanup is in finally.
    @SuppressWarnings("resource")
    void run(Path root, String... command) throws Exception {
        Path log = Files.createTempFile(temporary, "setup-", ".log");
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();

        try {
            assertTrue(process.waitFor(10, TimeUnit.MINUTES), "Setup timed out: " + Arrays.toString(command));
            assertEquals(0, process.exitValue(), () -> commandOutput(command, log));
        } finally {
            process.descendants().forEach(ProcessHandle::destroy);

            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String commandOutput(String[] command, Path log) {
        try {
            return Arrays.toString(command) + "\n" + Files.readString(log);
        } catch (Exception failure) {
            return failure.toString();
        }
    }

    private Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private void writeJson(Path file, Object value) throws Exception {
        write(file, JSON.toJson(value));
    }

    private static String portablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;");
    }
}
