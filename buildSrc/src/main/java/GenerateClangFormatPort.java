import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Generates a host-tool overlay without installing or compiling any packages. */
public abstract class GenerateClangFormatPort extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getManifest();

    @Input
    public abstract Property<String> getHostPlatform();

    @OutputDirectory
    public abstract DirectoryProperty getPortDirectory();

    @TaskAction
    public void generate() throws Exception {
        Map<?, ?> manifest = object(new JsonSlurper().parse(getManifest().get().getAsFile()));
        String baseline = String.valueOf(manifest.get("builtin-baseline"));
        if (!baseline.matches("[0-9a-f]{40}")) {
            throw new GradleException("vcpkg.json must specify a 40-character builtin-baseline.");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        Map<?, ?> versions = object(json(client,
                "https://raw.githubusercontent.com/microsoft/vcpkg/" + baseline + "/versions/baseline.json"));
        String version = String.valueOf(object(object(versions.get("default")).get("llvm")).get("baseline"));
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new GradleException("Unsupported LLVM version at vcpkg baseline " + baseline + ": " + version);
        }
        Map<?, ?> release = object(json(client, "https://pypi.org/pypi/clang-format/" + version + "/json"));
        Map<?, ?> asset = selectAsset((List<?>) release.get("urls"), getHostPlatform().get(), version);
        String url = String.valueOf(asset.get("url"));
        if (!url.startsWith("https://files.pythonhosted.org/")) {
            throw new GradleException("Unexpected clang-format download host: " + url);
        }
        String expected = String.valueOf(object(asset.get("digests")).get("sha256"));
        byte[] archive = download(client, url);
        String actual = digest("SHA-256", archive);
        if (!actual.equals(expected)) {
            throw new GradleException("clang-format archive does not match PyPI's SHA-256 digest.");
        }
        String sha512 = digest("SHA-512", archive);
        String filename = String.valueOf(asset.get("filename"));
        String port = """
                # Generated from vcpkg builtin-baseline @BASELINE@. Do not edit.
                vcpkg_download_distfile(archive
                    URLS "@URL@"
                    FILENAME "@FILENAME@.zip"
                    SHA512 "@SHA512@"
                )
                vcpkg_extract_source_archive(source ARCHIVE "${archive}" NO_REMOVE_ONE_LEVEL)
                set(executable "clang-format")
                if(VCPKG_TARGET_IS_WINDOWS)
                    string(APPEND executable ".exe")
                endif()
                file(INSTALL DESTINATION "${CURRENT_PACKAGES_DIR}/tools/clang-format"
                    TYPE PROGRAM FILES "${source}/clang_format/data/bin/${executable}")
                file(GLOB_RECURSE licenses
                    "${source}/*dist-info/licenses/LICENSE*"
                    "${source}/*dist-info/licenses/COPYING*"
                    "${source}/*dist-info/LICENSE*"
                    "${source}/*dist-info/COPYING*")
                if(NOT licenses)
                    message(FATAL_ERROR "clang-format archive is missing license notices")
                endif()
                vcpkg_install_copyright(FILE_LIST ${licenses})
                set(VCPKG_POLICY_EMPTY_INCLUDE_FOLDER enabled)
                """.replace("@BASELINE@", baseline).replace("@URL@", url)
                .replace("@FILENAME@", filename).replace("@SHA512@", sha512);
        var directory = getPortDirectory().get().getAsFile().toPath();
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("portfile.cmake"), port);
        Files.writeString(directory.resolve("vcpkg.json"), JsonOutput.prettyPrint(JsonOutput.toJson(Map.of(
                "name", "clang-format", "version", version,
                "description", "Prebuilt native clang-format selected by the project's vcpkg baseline",
                "homepage", "https://github.com/ssciwr/clang-format-wheel",
                "license", "Apache-2.0 WITH LLVM-exception"))) + "\n");
        Files.writeString(directory.resolve("provenance.json"), JsonOutput.prettyPrint(JsonOutput.toJson(Map.of(
                "builtin-baseline", baseline, "version", version, "platform", getHostPlatform().get(),
                "url", url, "sha256", actual, "sha512", sha512))) + "\n");
        getLogger().lifecycle("Selected prebuilt clang-format {} from vcpkg baseline {}", version, baseline);
    }

    static Map<?, ?> selectAsset(List<?> assets, String platform, String version) {
        String suffix = switch (platform) {
            case "x64-windows" -> "-win_amd64.whl";
            case "arm64-windows" -> "-win_arm64.whl";
            case "x64-osx", "x64-linux" -> "_x86_64.whl";
            case "arm64-osx" -> "_arm64.whl";
            case "arm64-linux" -> "_aarch64.whl";
            default -> throw new GradleException("No prebuilt clang-format support for " + platform);
        };
        String family = platform.endsWith("-osx") ? "-macosx_"
                : platform.endsWith("-linux") ? "-manylinux_" : "-win_";
        var matches = assets.stream().map(GenerateClangFormatPort::object)
                .filter(asset -> !Boolean.TRUE.equals(asset.get("yanked")))
                .filter(asset -> String.valueOf(asset.get("filename")).startsWith("clang_format-" + version + "-"))
                .filter(asset -> String.valueOf(asset.get("filename")).contains(family))
                .filter(asset -> String.valueOf(asset.get("filename")).endsWith(suffix)).toList();
        if (matches.size() != 1) {
            throw new GradleException("Expected one prebuilt clang-format " + version + " archive for "
                    + platform + ", found " + matches.size() + ". No LLVM source-build fallback is used.");
        }
        return matches.getFirst();
    }

    private static Object json(HttpClient client, String url) throws Exception {
        return new JsonSlurper().parseText(new String(download(client, url), StandardCharsets.UTF_8));
    }

    private static byte[] download(HttpClient client, String url) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new GradleException("Cannot resolve prebuilt clang-format: HTTP " + response.statusCode()
                    + " from " + url + ". No LLVM source-build fallback is used.");
        }
        return response.body();
    }

    private static String digest(String algorithm, byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }

    private static Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new GradleException("Unexpected registry or PyPI metadata: expected a JSON object.");
    }
}
