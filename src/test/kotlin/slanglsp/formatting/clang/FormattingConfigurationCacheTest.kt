package slanglsp.formatting.clang

import com.intellij.testFramework.fixtures.IdeaProjectTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import slanglsp.TestDependencies
import slanglsp.formatting.clang.FormattingConfiguration.validate

internal class FormattingConfigurationCacheTest {
    private lateinit var fixture: IdeaProjectTestFixture

    @BeforeEach
    fun setUp() {
        fixture = IdeaTestFixtureFactory.getFixtureFactory()
            .createLightFixtureBuilder("FormattingConfigurationCache")
            .fixture

        runInEdtAndWait {
            fixture.setUp()
        }
    }

    @AfterEach
    fun tearDown() {
        runInEdtAndWait {
            fixture.tearDown()
        }
    }

    @Test
    @Throws(Exception::class)
    fun validatesOnceUntilParentConfigChangesAndDoesNotCacheFailure(@TempDir root: Path) {
        val nested = Files.createDirectory(root.resolve("nested"))
        val parent = Files.writeString(root.resolve(".clang-format"), "IndentWidth: 2\n")
        val config = Files.writeString(nested.resolve(".clang-format"), "BasedOnStyle: InheritParentConfig\n")
        val launches = AtomicInteger()
        val executable = TestDependencies.clangFormat()
        val source = nested.resolve("shader.slang")

        Assertions.assertEquals("file:$config", validate(source, executable, launches))
        Assertions.assertEquals("file:$config", validate(nested.resolve("other.slang"), executable, launches))
        Assertions.assertEquals(1, launches.get())

        Files.writeString(parent, "IndentWidth: 8\n")
        validate(source, executable, launches)
        Assertions.assertEquals(2, launches.get())

        Files.writeString(config, "NotAClangFormatOption: true\n")
        Assertions.assertThrows<IOException?>(IOException::class.java) { validate(source, executable, launches) }
        Assertions.assertThrows<IOException?>(IOException::class.java) { validate(source, executable, launches) }
        Assertions.assertEquals(4, launches.get())
    }

    companion object {
        @Throws(Exception::class)
        private fun validate(source: Path, executable: String, launches: AtomicInteger): Any? {
            val style = "slang.format.clangFormatStyle"
            val settings = hashMapOf<String?, Any?>(style to "file")
            validate(source, executable, settings) { launches.incrementAndGet() }
            return settings[style]
        }
    }
}
