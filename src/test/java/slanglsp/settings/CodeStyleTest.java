package slanglsp.settings;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import org.junit.jupiter.api.Test;
import slanglsp.TestDependencies;
import slanglsp.language.SlangLanguage;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CodeStyleTest {
    @Test
    void presetsPersistAndProduceNativeFormatting() throws Exception {
        var fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("CodeStyle").getFixture();
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            CodeStyleSettings[] styles = new CodeStyleSettings[2];
            EdtTestUtil.runInEdtAndWait(() -> {
                assertInstanceOf(SlangCodeStyleProvider.class,
                        LanguageCodeStyleSettingsProvider.forLanguage(SlangLanguage.INSTANCE));
                var manager = CodeStyleSettingsManager.getInstance(fixture.getProject());
                styles[0] = manager.createSettings();
                assertEquals(ClangFormatStyle.DEFAULT_STYLE, ClangFormatStyle.clangFormatStyle(styles[0]));
                styles[0].getCustomSettings(SlangCodeStyleSettings.class).BRACES_ON_NEW_LINE = false;
                styles[0].getCommonSettings(SlangLanguage.INSTANCE).initIndentOptions().CONTINUATION_INDENT_SIZE = 8;
                var xml = new org.jdom.Element("code_scheme");
                styles[0].writeExternal(xml);
                styles[1] = manager.createSettings();
                styles[1].readExternal(xml);
                assertEquals(ClangFormatStyle.clangFormatStyle(styles[0]), ClangFormatStyle.clangFormatStyle(styles[1]));

                var provider = new SlangCodeStyleProvider();
                var configurable = provider.createConfigurable(styles[0], manager.cloneSettings(styles[0]));
                try {
                    assertNotNull(configurable.createComponent());
                    configurable.reset();
                    assertFalse(configurable.isModified());
                } finally {
                    configurable.disposeUIResources();
                }
                new CodeStylePresets.Slang().apply(styles[0]);
            });
            String source = "float evaluate(float value){if(value>0){return value;}return 0;}";
            String slang = format(ClangFormatStyle.clangFormatStyle(styles[0]), source);
            String java = format(ClangFormatStyle.clangFormatStyle(styles[1]), source);
            assertTrue(slang.contains("evaluate(float value)\n{"), slang);
            assertTrue(slang.contains("if (value > 0)\n    {"), slang);
            assertTrue(java.contains("evaluate(float value) {"), java);
            assertTrue(java.contains("if (value > 0) {"), java);
            assertEquals(ClangFormatStyle.DEFAULT_STYLE, ClangFormatStyle.clangFormatStyle(styles[0]));
            var state = new ProjectSettings.State();
            assertEquals(ClangFormatStyle.DEFAULT_STYLE,
                    state.createServerSettings().get("slang.format.clangFormatFallbackStyle"));
            state.clangFormatFallbackStyle = "Google";
            assertEquals("Google", state.createServerSettings().get("slang.format.clangFormatFallbackStyle"));
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }

    private static String format(String style, String source) throws Exception {
        var process = new ProcessBuilder(TestDependencies.clangFormat(), "--style=" + style,
                "--assume-filename=sample.slang").redirectErrorStream(true).start();
        try {
            process.getOutputStream().write(source.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            process.destroyForcibly();
        }
    }
}
