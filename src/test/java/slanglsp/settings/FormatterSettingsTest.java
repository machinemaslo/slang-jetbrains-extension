package slanglsp.settings;

import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import slanglsp.SlangBundle;

import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static slanglsp.FormattingFixtures.text;

class FormatterSettingsTest {
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
                    assertEquals(8, tabs.getTabCount());

                    var indents = unwrapTab(tabs.getTabAt(1).getComponent());
                    var indentLayout = assertInstanceOf(BorderLayout.class, indents.getLayout());
                    assertNotNull(indentLayout.getLayoutComponent(BorderLayout.NORTH));
                    assertNotNull(indentLayout.getLayoutComponent(BorderLayout.CENTER));
                    assertNull(indentLayout.getLayoutComponent(BorderLayout.WEST));

                    var braces = unwrapTab(tabs.getTabs().stream()
                            .filter(tab -> tab.getText().equals(SlangBundle.message("settings.code.style.braces.tab")))
                            .findFirst().orElseThrow().getComponent());
                    var braceLayout = assertInstanceOf(GridBagLayout.class, braces.getLayout());
                    assertEquals(0, braceLayout.getConstraints(braces.getComponent(0)).gridy);
                    assertEquals(1, braceLayout.getConstraints(braces.getComponent(1)).gridy);
                } finally {
                    configurable.disposeUIResources();
                }
            });
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }

    private static java.awt.Container unwrapTab(java.awt.Container tab) {
        while (tab.getComponentCount() == 1 && tab.getComponent(0) instanceof java.awt.Container child) {
            tab = child;
        }
        return tab;
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

    @ParameterizedTest(name = "clang-format={0}, import={1}, external={2}, bundled available={3}")
    @CsvSource({
            "false, false, false, true",
            "false, true, false, false",
            "true, false, true, false",
            "true, false, true, true",
            "true, false, false, true",
            "true, false, false, false"
    })
    void generalTabRoundTripsEngineAndExecutableSelection(
            boolean clang, boolean readConfig, boolean external, boolean bundledAvailable) throws Exception {
        var fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("FormatterSettings").getFixture();
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                var manager = CodeStyleSettingsManager.getInstance(fixture.getProject());
                var settings = manager.createSettings();
                var source = settings.getCustomSettings(SlangCodeStyleSettings.class);
                assertFalse(source.USE_CLANG_FORMAT);
                source.USE_CLANG_FORMAT = clang;
                source.READ_CLANG_FORMAT = readConfig;
                source.USE_CUSTOM_CLANG_FORMAT = external;
                source.CLANG_FORMAT_PATH = Path.of(fixture.getProject().getBasePath(), "tools with spaces", "clang-format")
                        .toAbsolutePath().toString();
                var panel = new FormatterPanel(settings, bundledAvailable ? "bundled fixture" : null);
                try {
                    panel.reset(settings);
                    assertFalse(panel.isModified(settings));
                    assertEquals(bundledAvailable, Arrays.stream(panel.getPanel().getComponents())
                            .anyMatch(component -> component instanceof JCheckBox checkBox && checkBox.isVisible()
                                    && checkBox.getText().equals(SlangBundle.message("settings.formatter.external"))));
                    var pathField = Arrays.stream(panel.getPanel().getComponents())
                            .filter(JPanel.class::isInstance)
                            .flatMap(row -> Arrays.stream(((JPanel) row).getComponents()))
                            .filter(JTextField.class::isInstance)
                            .map(JTextField.class::cast)
                            .findFirst().orElseThrow();
                    assertEquals(clang && (!bundledAvailable || external), pathField.isEnabled());
                    String bundled = BundledClangFormat.executable();
                    assertEquals(!external && bundled != null ? bundled : source.CLANG_FORMAT_PATH,
                            source.clangFormatExecutable());
                    var saved = manager.createSettings();
                    panel.apply(saved);
                    var result = saved.getCustomSettings(SlangCodeStyleSettings.class);
                    assertEquals(clang, result.USE_CLANG_FORMAT);
                    assertEquals(readConfig, result.READ_CLANG_FORMAT);
                    assertEquals(external, result.USE_CUSTOM_CLANG_FORMAT);
                    assertEquals(source.CLANG_FORMAT_PATH, result.CLANG_FORMAT_PATH);
                    if (pathField.isEnabled()) {
                        pathField.setText("clang-format");
                        assertThrows(ConfigurationException.class, () -> panel.apply(saved));
                        pathField.setText("");
                        assertThrows(ConfigurationException.class, () -> panel.apply(saved));
                    }
                } finally {
                    Disposer.dispose(panel);
                }
            });
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }

    @Test
    void yamlLanguageSectionsOverrideDefaultsWithoutApplyingOtherLanguages() {
        var values = ClangFormatSettingsImporter.parse(text("configuration/languages-parser/.clang-format"));
        assertEquals(2, values.get("IndentWidth"));
        assertEquals("Never", values.get("UseTab"));
    }

    @Test
    void slangSectionTakesPrecedenceOverCppAndMergesBraceWrapping() {
        var values = ClangFormatSettingsImporter.parse("""
                ---
                BraceWrapping:
                  AfterNamespace: true
                ---
                Language: Slang
                BraceWrapping:
                  AfterFunction: false
                ---
                Language: Cpp
                BraceWrapping:
                  AfterStruct: true
                  AfterFunction: true
                """);
        assertEquals(java.util.Map.of("AfterNamespace", true, "AfterStruct", true, "AfterFunction", false),
                values.get("BraceWrapping"));
    }
}
