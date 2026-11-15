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

class SlangCodeStyleTest {
    @Test
    void presetsPersistAndProduceNativeFormatting() throws Exception {
        var fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("SlangCodeStyle").getFixture();
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            CodeStyleSettings[] styles = new CodeStyleSettings[2];
            EdtTestUtil.runInEdtAndWait(() -> {
                assertInstanceOf(SlangCodeStyleProvider.class,
                        LanguageCodeStyleSettingsProvider.forLanguage(SlangLanguage.INSTANCE));
                var manager = CodeStyleSettingsManager.getInstance(fixture.getProject());
                styles[0] = manager.createSettings();
                assertEquals(SlangCodeStyle.DEFAULT_STYLE, SlangCodeStyle.clangFormatStyle(styles[0]));
                new SlangCodeStylePresets.Java().apply(styles[0]);
                var xml = new org.jdom.Element("code_scheme");
                styles[0].writeExternal(xml);
                styles[1] = manager.createSettings();
                styles[1].readExternal(xml);
                assertEquals(SlangCodeStyle.clangFormatStyle(styles[0]), SlangCodeStyle.clangFormatStyle(styles[1]));

                var provider = new SlangCodeStyleProvider();
                var configurable = provider.createConfigurable(styles[0], manager.cloneSettings(styles[0]));
                try {
                    assertNotNull(configurable.createComponent());
                    configurable.reset();
                    assertFalse(configurable.isModified());
                } finally {
                    configurable.disposeUIResources();
                }
                new SlangCodeStylePresets.Slang().apply(styles[0]);
            });
            String source = "float evaluate(float value){if(value>0){return value;}return 0;}";
            String slang = format(SlangCodeStyle.clangFormatStyle(styles[0]), source);
            String java = format(SlangCodeStyle.clangFormatStyle(styles[1]), source);
            assertTrue(slang.contains("evaluate(float value)\n{"), slang);
            assertTrue(slang.contains("if (value > 0)\n    {"), slang);
            assertTrue(java.contains("evaluate(float value) {"), java);
            assertTrue(java.contains("if (value > 0) {"), java);
            assertEquals(SlangCodeStyle.DEFAULT_STYLE, SlangCodeStyle.clangFormatStyle(styles[0]));
            var state = new ProjectSettings.State();
            assertEquals(SlangCodeStyle.DEFAULT_STYLE,
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
