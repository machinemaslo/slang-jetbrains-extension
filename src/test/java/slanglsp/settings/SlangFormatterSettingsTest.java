package slanglsp.settings;

import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SlangFormatterSettingsTest {
    @Test
    void completeCodeStylePanelBuildsWithGeneralSelected() throws Exception {
        var fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("CodeStyleTabs").getFixture();
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                var manager = CodeStyleSettingsManager.getInstance(fixture.getProject());
                var settings = manager.createSettings();
                var configurable = new SlangCodeStyleProvider().createConfigurable(settings, manager.cloneSettings(settings));
                try {
                    var component = configurable.createComponent();
                    assertNotNull(component);
                    configurable.reset();
                    assertFalse(configurable.isModified());
                    var tabs = findTabs(component);
                    assertNotNull(tabs);
                    assertNotNull(tabs.getSelectedInfo());
                    assertEquals("General", tabs.getSelectedInfo().getText());
                    assertEquals(7, tabs.getTabCount());
                } finally {
                    configurable.disposeUIResources();
                }
            });
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }

    private static com.intellij.ui.tabs.JBTabs findTabs(java.awt.Component component) {
        if (component instanceof com.intellij.ui.tabs.JBTabs tabs) return tabs;
        if (component instanceof java.awt.Container container) {
            for (var child : container.getComponents()) {
                var tabs = findTabs(child);
                if (tabs != null) return tabs;
            }
        }
        return null;
    }

    @Test
    void generalTabRoundTripsEngineAndExecutableSelection() throws Exception {
        var fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("FormatterSettings").getFixture();
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                var manager = CodeStyleSettingsManager.getInstance(fixture.getProject());
                var settings = manager.createSettings();
                var source = settings.getCustomSettings(SlangCodeStyleSettings.class);
                assertFalse(source.USE_CLANG_FORMAT);
                source.USE_CLANG_FORMAT = true;
                source.READ_CLANG_FORMAT = true;
                source.USE_CUSTOM_CLANG_FORMAT = true;
                source.USE_BUNDLED_CLANG_FORMAT = false;
                source.CLANG_FORMAT_PATH = "/tools with spaces/clang-format";
                var panel = new SlangFormatterPanel(settings);
                try {
                    panel.reset(settings);
                    assertFalse(panel.isModified(settings));
                    var saved = manager.createSettings();
                    panel.apply(saved);
                    var result = saved.getCustomSettings(SlangCodeStyleSettings.class);
                    assertTrue(result.USE_CLANG_FORMAT);
                    assertTrue(result.READ_CLANG_FORMAT);
                    assertTrue(result.USE_CUSTOM_CLANG_FORMAT);
                    assertFalse(result.USE_BUNDLED_CLANG_FORMAT);
                    assertEquals(source.CLANG_FORMAT_PATH, result.CLANG_FORMAT_PATH);
                } finally {
                    Disposer.dispose(panel);
                }
            });
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }

    @Test
    void findsBundledClangFormatInClionAndRiderLayouts(@TempDir Path root) throws Exception {
        String os = SystemInfo.isWindows ? "win" : SystemInfo.isMac ? "macos" : "linux";
        String arch = System.getProperty("os.arch").matches("aarch64|arm64") ? "arm64" : "x64";
        String name = SystemInfo.isWindows ? "clang-format.exe" : "clang-format";
        assertNull(BundledClangFormat.locate(root));
        for (String base : new String[] {"plugins/clion-radler/DotFiles", "lib/ReSharperHost"}) {
            Path tool = root.resolve(base).resolve(os + "-" + arch).resolve(name);
            Files.createDirectories(tool.getParent());
            Files.writeString(tool, "test fixture");
            assertTrue(tool.toFile().setExecutable(true));
            assertEquals(tool.toString(), BundledClangFormat.locate(root));
            Files.delete(tool);
        }
    }

    @Test
    void yamlLanguageSectionsOverrideDefaultsWithoutApplyingOtherLanguages() {
        var values = SlangClangFormatSettings.parse("""
                ---
                IndentWidth: 3
                UseTab: Never
                ---
                Language: JavaScript
                IndentWidth: 8
                ---
                Language: Cpp
                IndentWidth: 2
                """);
        assertEquals(2, values.get("IndentWidth"));
        assertEquals("Never", values.get("UseTab"));
    }
}
