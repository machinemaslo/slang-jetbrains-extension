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

                    var indents = assertInstanceOf(com.intellij.ui.OnePixelSplitter.class,
                            unwrapTab(tabs.getTabAt(1).getComponent()));
                    var braces = assertInstanceOf(com.intellij.ui.OnePixelSplitter.class,
                            unwrapTab(tabs.getTabAt(3).getComponent()));
                    var blankLines = assertInstanceOf(com.intellij.ui.OnePixelSplitter.class,
                            unwrapTab(tabs.getTabAt(4).getComponent()));
                    for (var tab : tabs.getTabs()) {
                        if (tab.getText().equals(SlangBundle.message("naming.tab"))) {
                            assertFalse(unwrapTab(tab.getComponent()) instanceof com.intellij.ui.OnePixelSplitter);
                            continue;
                        }
                        var splitter = assertInstanceOf(com.intellij.ui.OnePixelSplitter.class,
                                unwrapTab(tab.getComponent()));
                        assertTrue(splitter.isVertical(), tab.getText());
                    }
                    var hint = assertInstanceOf(javax.swing.JLabel.class,
                            blankLines.getSecondComponent().getComponent(0));
                    assertEquals("Click an option to see preview", hint.getText());
                    assertTrue(hint.isVisible());
                    selectOption(blankLines,
                            SlangBundle.message("settings.code.style.blank.lines.code"));
                    assertFalse(hint.isVisible());
                    var spaces = assertInstanceOf(com.intellij.ui.OnePixelSplitter.class,
                            unwrapTab(tabs.getTabAt(6).getComponent()));
                    selectOption(spaces, SlangBundle.message("settings.code.style.spaces.before.comma"));
                    var preview = spaces.getSecondComponent().getComponent(1);
                    var explanation = findComponent(preview, javax.swing.JLabel.class);
                    assertNotNull(explanation);
                    assertTrue(explanation.getText().contains("Before comma"));
                    var editor = com.intellij.openapi.editor.EditorFactory.getInstance().getEditorList().stream()
                            .filter(item -> javax.swing.SwingUtilities.isDescendingFrom(item.getComponent(), preview))
                            .findFirst().orElseThrow();
                    String before = editor.getDocument().getText();
                    assertTrue(before.contains("evaluate"), before);
                    selectOption(spaces, SlangBundle.message("settings.code.style.spaces.after.comma"));
                    assertEquals(before, editor.getDocument().getText());
                    assertTrue(explanation.getText().contains("After comma"));
                    var braceTable = selectOption(braces, SlangBundle.message("settings.code.style.function_braces"));
                    assertEquals(2, braceTable.getColumnCount());
                    int row = braceTable.getSelectedRow();
                    assertTrue(braceTable.editCellAt(row, 1));
                    assertEquals(row, braceTable.getEditingRow());
                    assertEquals(1, braceTable.getEditingColumn());
                    var placements = assertInstanceOf(javax.swing.JComboBox.class, braceTable.getEditorComponent());
                    assertEquals("Allman", placements.getSelectedItem());
                    assertEquals(3, placements.getItemCount());
                    for (int i = 0; i < placements.getItemCount(); i++) {
                        assertNotEquals("Slang", placements.getItemAt(i));
                    }
                    assertFalse(configurable.isModified(), "Preview selections must not change the scheme");
                    placements.setSelectedItem("K&R");
                    assertTrue(braceTable.getCellEditor().stopCellEditing());
                    assertTrue(configurable.isModified());
                    float previousIndents = indents.getProportion();
                    try {
                        indents.setProportion(0.7f);
                        float previousBraces = braces.getProportion();
                        blankLines.setProportion(0.4f);
                        assertEquals(0.7f, indents.getProportion());
                        assertEquals(previousBraces, braces.getProportion());
                    } finally {
                        indents.setProportion(previousIndents);
                    }
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

    private static com.intellij.ui.treeStructure.treetable.TreeTable selectOption(
            com.intellij.ui.OnePixelSplitter panel, String label) {
        var table = findComponent(panel.getFirstComponent(), com.intellij.ui.treeStructure.treetable.TreeTable.class);
        assertNotNull(table);
        var tree = table.getTree();
        var root = (javax.swing.tree.DefaultMutableTreeNode) tree.getModel().getRoot();
        var nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (javax.swing.tree.DefaultMutableTreeNode) nodes.nextElement();
            if (node.isLeaf() && node.toString().equals(label)) {
                var path = new javax.swing.tree.TreePath(node.getPath());
                tree.expandPath(path.getParentPath());
                int row = tree.getRowForPath(path);
                table.setRowSelectionInterval(row, row);
                return table;
            }
        }
        return fail("Missing option: " + label);
    }

    private static <T> T findComponent(java.awt.Component component, Class<T> type) {
        if (type.isInstance(component)) {
            return type.cast(component);
        }
        if (component instanceof java.awt.Container container) {
            for (var child : container.getComponents()) {
                var match = findComponent(child, type);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
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
                source.CLANG_FORMAT_PATH = Path.of(java.util.Objects.requireNonNull(fixture.getProject().getBasePath()),
                        "tools with spaces", "clang-format")
                        .toAbsolutePath().toString();
                var panel = new FormatterPanel(settings, bundledAvailable ? "bundled fixture" : null);
                try {
                    panel.reset(settings);
                    assertFalse(panel.isModified(settings));
                    var splitter = assertInstanceOf(com.intellij.ui.OnePixelSplitter.class, panel.getPanel());
                    var scroll = assertInstanceOf(javax.swing.JScrollPane.class, splitter.getFirstComponent());
                    var controls = ((JPanel) scroll.getViewport().getView()).getComponents();
                    assertEquals(bundledAvailable, Arrays.stream(controls)
                            .anyMatch(component -> component instanceof JCheckBox checkBox && checkBox.isVisible()
                                    && checkBox.getText().equals(SlangBundle.message("settings.formatter.external"))));
                    var pathField = Arrays.stream(controls)
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
