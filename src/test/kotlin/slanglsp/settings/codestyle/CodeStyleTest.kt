package slanglsp.settings.codestyle

import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.jdom.Element
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import slanglsp.FormattingFixtures
import slanglsp.TestDependencies
import slanglsp.formatting.clang.ClangFormatSettingsImporter
import slanglsp.formatting.clang.ClangFormatSettingsImporter.Options.parse
import slanglsp.formatting.clang.ClangFormatStyle
import slanglsp.formatting.clang.ClangFormatStyle.export
import slanglsp.language.SlangLanguage
import slanglsp.settings.codestyle.CodeStylePresets.Slang
import slanglsp.settings.project.ProjectSettings

internal class CodeStyleTest {
    @Test
    @Throws(Exception::class)
    fun exportedStyleWorksInCppAndSlangdMode(@TempDir directory: Path) {
        val settings = CodeStyleSettingsManager.getInstance().createSettings()
        val custom = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
        custom.FUNCTION_BRACES = 2
        custom.CONTROL_BRACES = 1
        custom.NAMESPACE_INDENTATION = 1
        custom.SPACE_AFTER_LOGICAL_NOT = true
        val path = Files.writeString(directory.resolve(".clang-format"), export(settings))
        for (filename in arrayOf("sample.cpp", "sample.slang.cs")) {
            val formatted: String =
                format("file:$path", "bool test(bool v) { if (!v) { return false; } return v; }", filename)
            Assertions.assertTrue(formatted.contains("test(bool v)\n{"), formatted)
            Assertions.assertTrue(formatted.contains("if (! v) {"), formatted)
        }
        val parsed = parse(Files.readString(path))
        val imported = CodeStyleSettingsManager.getInstance().createSettings()
        ClangFormatSettingsImporter.Options.apply(parsed, imported)
        val roundTrip = imported.getCustomSettings(SlangCodeStyleSettings::class.java)
        Assertions.assertEquals(2, roundTrip.FUNCTION_BRACES)
        Assertions.assertEquals(1, roundTrip.NAMESPACE_INDENTATION)
        Assertions.assertTrue(roundTrip.SPACE_AFTER_LOGICAL_NOT)
    }

    @Test
    @Throws(Exception::class)
    fun presetsPersistAndProduceNativeFormatting() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("CodeStyle").getFixture()
        runInEdtAndWait { fixture.setUp() }
        try {
            val styles = arrayOfNulls<CodeStyleSettings>(2)
            runInEdtAndWait {
                Assertions.assertInstanceOf<CodeStyleProvider?>(
                    CodeStyleProvider::class.java,
                    LanguageCodeStyleSettingsProvider.forLanguage(SlangLanguage.INSTANCE))
                val manager = CodeStyleSettingsManager.getInstance(fixture.project)
                styles[0] = manager.createSettings()
                Assertions.assertEquals(ClangFormatStyle.DEFAULT_STYLE, ClangFormatStyle.clangFormatStyle(styles[0]!!))
                styles[0]!!
                    .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                    .BRACES_ON_NEW_LINE = false
                styles[0]!!.getCommonSettings(SlangLanguage.INSTANCE).initIndentOptions().CONTINUATION_INDENT_SIZE = 8
                val xml = Element("code_scheme")
                styles[0]!!.writeExternal(xml)
                styles[1] = manager.createSettings()
                styles[1]!!.readExternal(xml)
                Assertions.assertEquals(
                    ClangFormatStyle.clangFormatStyle(styles[0]!!), ClangFormatStyle.clangFormatStyle(styles[1]!!))

                val provider = CodeStyleProvider()
                val configurable = provider.createConfigurable(styles[0]!!, manager.cloneSettings(styles[0]!!))
                try {
                    Assertions.assertNotNull(configurable.createComponent())
                    configurable.reset()
                    Assertions.assertFalse(configurable.isModified)
                } finally {
                    configurable.disposeUIResources()
                }
                Slang().apply(styles[0]!!)
            }
            val source = FormattingFixtures.withoutFinalNewline("preset/before.slang")
            val slang = format(ClangFormatStyle.clangFormatStyle(styles[0]!!), source)
            val java = format(ClangFormatStyle.clangFormatStyle(styles[1]!!), source)
            Assertions.assertTrue(slang.contains("evaluate(float value)\n{"), slang)
            Assertions.assertTrue(slang.contains("if (value > 0)\n    {"), slang)
            Assertions.assertTrue(java.contains("evaluate(float value) {"), java)
            Assertions.assertTrue(java.contains("if (value > 0) {"), java)
            Assertions.assertEquals(ClangFormatStyle.DEFAULT_STYLE, ClangFormatStyle.clangFormatStyle(styles[0]!!))
            val state = ProjectSettings.State()
            Assertions.assertEquals(
                ClangFormatStyle.DEFAULT_STYLE, state.createServerSettings()["slang.format.clangFormatFallbackStyle"])
            state.clangFormatFallbackStyle = "Google"
            Assertions.assertEquals("Google", state.createServerSettings()["slang.format.clangFormatFallbackStyle"])
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    @Test
    fun customFormattingOptionsPersistAsPublicFields() {
        val fixture =
            IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("CodeStyleFields").getFixture()
        runInEdtAndWait { fixture.setUp() }
        try {
            runInEdtAndWait {
                val manager = CodeStyleSettingsManager.getInstance(fixture.project)
                fun customSettings() = manager.createSettings().getCustomSettings(SlangCodeStyleSettings::class.java)
                val defaults = customSettings()
                val settings =
                    customSettings().apply {
                        STRUCT_BRACES = 1
                        CLASS_BRACES = 2
                        ENUM_BRACES = 3
                        SPACE_WITHIN_GENERIC_ANGLES = true
                        KEEP_BLANK_LINES_AFTER_LBRACE = false
                        DISABLE_FORMAT = true
                    }
                val xml = Element("code_scheme")
                settings.writeExternal(xml, defaults)

                val loaded = customSettings()
                loaded.readExternal(xml)
                Assertions.assertEquals(1, loaded.STRUCT_BRACES)
                Assertions.assertEquals(2, loaded.CLASS_BRACES)
                Assertions.assertEquals(3, loaded.ENUM_BRACES)
                Assertions.assertTrue(loaded.SPACE_WITHIN_GENERIC_ANGLES)
                Assertions.assertFalse(loaded.KEEP_BLANK_LINES_AFTER_LBRACE)
                Assertions.assertTrue(loaded.DISABLE_FORMAT)
                Assertions.assertEquals(settings, loaded)
                Assertions.assertNotEquals(defaults, loaded)
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    companion object {
        @Throws(Exception::class)
        private fun format(style: String, source: String, filename: String = "sample.slang"): String {
            val process =
                ProcessBuilder(TestDependencies.clangFormat(), "--style=$style", "--assume-filename=$filename")
                    .redirectErrorStream(true)
                    .start()
            try {
                process.outputStream.write(source.toByteArray(StandardCharsets.UTF_8))
                process.outputStream.close()
                Assertions.assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                val output = String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
                Assertions.assertEquals(0, process.exitValue(), output)
                return output
            } finally {
                process.destroyForcibly()
            }
        }
    }
}
