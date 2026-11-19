package slanglsp

import com.google.gson.JsonParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Native dependencies provisioned by Gradle's installTestDependencies task. */
object TestDependencies {
    fun slangd(): String {
        return executable("slang.test.executable")
    }

    fun vcpkgRoot(): Path {
        return Path.of(requiredProperty("slang.test.vcpkgRoot"))
    }

    fun clangFormat(): String {
        return executable("slang.test.clangFormat")
    }

    @Throws(IOException::class)
    fun baseline(): String? {
        Files.newBufferedReader(Path.of(requiredProperty("slang.test.manifest"))).use { reader ->
            return JsonParser.parseReader(reader).asJsonObject.get("builtin-baseline").asString
        }
    }

    private fun executable(property: String): String {
        val executable = Path.of(requiredProperty(property))
        check(!(!Files.isRegularFile(executable) || !Files.isExecutable(executable))) {
            "Missing test executable at $executable; run ./gradlew test"
        }
        return executable.toString()
    }

    private fun requiredProperty(name: String): String {
        val value = System.getProperty(name)
        check(!(value == null || value.isBlank())) { "Missing $name; run tests through Gradle with VCPKG_ROOT set" }
        return value
    }
}
