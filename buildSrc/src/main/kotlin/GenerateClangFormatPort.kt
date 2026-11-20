import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Generates a host-tool overlay without installing or compiling any packages. */
abstract class GenerateClangFormatPort : DefaultTask() {
    @get:PathSensitive(PathSensitivity.NONE) @get:InputFile abstract val manifest: RegularFileProperty

    @get:Input abstract val hostPlatform: Property<String>

    @get:OutputDirectory abstract val portDirectory: DirectoryProperty

    @TaskAction
    @Throws(Exception::class)
    fun generate() {
        val manifest: MutableMap<*, *> = `object`(JsonSlurper().parse(this.manifest.get().asFile))
        val baseline = manifest["builtin-baseline"].toString()
        if (!baseline.matches("[0-9a-f]{40}".toRegex())) {
            throw GradleException("vcpkg.json must specify a 40-character builtin-baseline.")
        }
        val client =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        val versions: MutableMap<*, *> =
            `object`(json(client, "https://raw.githubusercontent.com/microsoft/vcpkg/$baseline/versions/baseline.json"))
        val version = `object`(`object`(versions["default"])["llvm"])["baseline"].toString()
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+".toRegex())) {
            throw GradleException("Unsupported LLVM version at vcpkg baseline $baseline: $version")
        }
        val release: MutableMap<*, *> = `object`(json(client, "https://pypi.org/pypi/clang-format/$version/json"))
        val asset = selectAsset(release["urls"] as List<*>, this.hostPlatform.get(), version)
        val url = asset["url"].toString()
        if (!url.startsWith("https://files.pythonhosted.org/")) {
            throw GradleException("Unexpected clang-format download host: $url")
        }
        val expected = `object`(asset["digests"])["sha256"].toString()
        val archive = download(client, url)
        val actual: String = digest("SHA-256", archive)
        if (actual != expected) {
            throw GradleException("clang-format archive does not match PyPI's SHA-256 digest.")
        }
        val sha512: String = digest("SHA-512", archive)
        val filename = asset["filename"].toString()
        val port =
            $$"""
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
                """
                .trimIndent()
                .replace("@BASELINE@", baseline)
                .replace("@URL@", url)
                .replace("@FILENAME@", filename)
                .replace("@SHA512@", sha512) + "\n"
        val directory = portDirectory.get().asFile.toPath()
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("portfile.cmake"), port)
        Files.writeString(
            directory.resolve("vcpkg.json"),
            JsonOutput.prettyPrint(
                JsonOutput.toJson(
                    mapOf(
                        "name" to "clang-format",
                        "version" to version,
                        "description" to "Prebuilt native clang-format selected by the project's vcpkg baseline",
                        "homepage" to "https://github.com/ssciwr/clang-format-wheel",
                        "license" to "Apache-2.0 WITH LLVM-exception"))) + "\n")
        Files.writeString(
            directory.resolve("provenance.json"),
            JsonOutput.prettyPrint(
                JsonOutput.toJson(
                    mapOf(
                        "builtin-baseline" to baseline,
                        "version" to version,
                        "platform" to hostPlatform.get(),
                        "url" to url,
                        "sha256" to actual,
                        "sha512" to sha512))) + "\n")
        logger.lifecycle("Selected prebuilt clang-format {} from vcpkg baseline {}", version, baseline)
    }

    companion object {
        fun selectAsset(assets: List<*>, platform: String, version: String): Map<*, *> {
            val suffix =
                when (platform) {
                    "x64-windows" -> "-win_amd64.whl"
                    "arm64-windows" -> "-win_arm64.whl"
                    "x64-osx",
                    "x64-linux" -> "_x86_64.whl"
                    "arm64-osx" -> "_arm64.whl"
                    "arm64-linux" -> "_aarch64.whl"
                    else -> throw GradleException("No prebuilt clang-format support for $platform")
                }
            val family =
                if (platform.endsWith("-osx")) "-macosx_"
                else if (platform.endsWith("-linux")) "-manylinux_" else "-win_"
            val matches =
                assets
                    .asSequence()
                    .map(::`object`)
                    .filter { asset -> asset["yanked"] != true }
                    .filter { asset -> asset["filename"].toString().startsWith("clang_format-$version-") }
                    .filter { asset -> asset["filename"].toString().contains(family) }
                    .filter { asset -> asset["filename"].toString().endsWith(suffix) }
                    .toList()
            if (matches.size != 1) {
                throw GradleException(
                    ("Expected one prebuilt clang-format " +
                        version +
                        " archive for " +
                        platform +
                        ", found " +
                        matches.size +
                        ". No LLVM source-build fallback is used."))
            }
            return matches.single()
        }

        @Throws(Exception::class)
        private fun json(client: HttpClient, url: String): Any? {
            return JsonSlurper().parseText(String(download(client, url), StandardCharsets.UTF_8))
        }

        @Throws(Exception::class)
        private fun download(client: HttpClient, url: String): ByteArray {
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() != 200) {
                throw GradleException(
                    ("Cannot resolve prebuilt clang-format: HTTP " +
                        response.statusCode() +
                        " from " +
                        url +
                        ". No LLVM source-build fallback is used."))
            }
            return response.body()
        }

        @Throws(Exception::class)
        private fun digest(algorithm: String, bytes: ByteArray): String {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes))
        }

        private fun `object`(value: Any?): MutableMap<*, *> {
            if (value is MutableMap<*, *>) {
                return value
            }
            throw GradleException("Unexpected registry or PyPI metadata: expected a JSON object.")
        }
    }
}
