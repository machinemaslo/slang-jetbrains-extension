package slanglsp.settings;

import com.intellij.openapi.util.Disposer;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class NamingSettingsTest {
    @Test
    void acceptsAlternativeStylesAndSuggestsTheFirstOne() {
        var rule = new NamingRule("Functions and methods",
                Set.of(NamingRule.Category.FUNCTIONS, NamingRule.Category.METHODS),
                List.of(new NamingRule.Variant(NamingRule.Style.LOWER_CAMEL_CASE, "", ""),
                        new NamingRule.Variant(NamingRule.Style.SNAKE_CASE, "", "")), true);
        assertTrue(rule.matches(NamingRule.Category.METHODS));
        assertFalse(rule.matches(NamingRule.Category.LOCALS));
        assertEquals("already_valid", rule.suggest("already_valid", ""));
        assertEquals("convertThis", rule.suggest("ConvertThis", ""));
        var disabled = new NamingRule(rule.name(), rule.categories(), rule.variants(), false);
        assertEquals("ConvertThis", disabled.suggest("ConvertThis", ""));
    }

    @Test
    void slangDefaultsFollowUpstreamDeclarations() {
        for (var rule : NamingRule.slang()) {
            var example = NamingRule.example(rule.category());
            assertEquals(example, rule.suggest(example, "GPU, CPU, API, UV, ID, BRDF, D3D"), rule.name());
        }
        var byCategory = NamingRule.slang().stream().collect(
                java.util.stream.Collectors.toMap(NamingRule::category, rule -> rule));
        assertEquals("gCounter", byCategory.get(NamingRule.Category.GLOBALS).suggest("counter", ""));
        assertEquals("gGeometry", byCategory.get(NamingRule.Category.GLOBALS).suggest("geometry", ""));
        assertEquals("kSampleCount", byCategory.get(NamingRule.Category.CONSTANTS).suggest("sample_count", ""));
        assertEquals("ITransformer", byCategory.get(NamingRule.Category.INTERFACES).suggest("transformer", ""));
        assertEquals("IBRDF", byCategory.get(NamingRule.Category.INTERFACES).suggest("IBRDF", "BRDF"));
        assertEquals("Orange", byCategory.get(NamingRule.Category.ENUM_MEMBERS).suggest("orange", ""));
        assertEquals("d3dThing", byCategory.get(NamingRule.Category.LOCALS).suggest("D3DThing", "D3D"));
        assertEquals("nodeID", byCategory.get(NamingRule.Category.PARAMETERS).suggest("node_id", "ID"));
        assertEquals("Slang", new CodeStylePresets.Slang().getName());
    }

    @Test
    void convertsAcronymsAffixesAndPreservesConformingNames() {
        var snake = new NamingRule("Functions", NamingRule.Category.FUNCTIONS,
                NamingRule.Style.SNAKE_CASE, "", "");
        assertEquals("test_function", snake.suggest("testFunction", ""));
        assertEquals("gpu_texture_id", snake.suggest("GPUTextureID", "GPU, ID"));
        var camel = new NamingRule("Fields", NamingRule.Category.FIELDS,
                NamingRule.Style.LOWER_CAMEL_CASE, "m_", "_");
        assertEquals("m_textureID_", camel.suggest("texture_id", "ID"));
        assertEquals("m_textureID_", camel.suggest("m_textureID_", "ID"));
        assertEquals("m_value_", camel.suggest("m_value_", ""));
        var any = new NamingRule("Exemption", NamingRule.Category.ALL, NamingRule.Style.ANY, "", "");
        assertEquals("any_STRANGE_Name", any.suggest("any_STRANGE_Name", ""));
        for (var style : NamingRule.Style.values()) {
            var rule = new NamingRule("Rule", NamingRule.Category.ALL, style, "", "");
            String suggested = rule.suggest("sampleGPUTexture2", "GPU");
            assertEquals(suggested, rule.suggest(suggested, "GPU"), style.toString());
        }
    }

    @Test
    void rulesRoundTripCloneAndProfileCheckbox() throws Exception {
        var fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("NamingSettings").getFixture();
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                var manager = CodeStyleSettingsManager.getInstance(fixture.getProject());
                var settings = manager.createSettings();
                var custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
                var rule = new NamingRule("My <fields>",
                        Set.of(NamingRule.Category.FIELDS, NamingRule.Category.LOCALS),
                        List.of(new NamingRule.Variant(NamingRule.Style.LOWER_CAMEL_CASE, "m_", "_"),
                                new NamingRule.Variant(NamingRule.Style.SNAKE_CASE, "", "")), false);
                custom.setNamingRules(List.of(rule));
                custom.NAMING_ABBREVIATIONS = "GPU, API";
                var xml = new org.jdom.Element("code_scheme");
                try {
                    settings.writeExternal(xml);
                    var loaded = manager.createSettings();
                    loaded.readExternal(xml);
                    assertEquals(custom.getNamingRules(),
                            loaded.getCustomSettings(SlangCodeStyleSettings.class).getNamingRules());
                    assertEquals(custom.NAMING_ABBREVIATIONS,
                            loaded.getCustomSettings(SlangCodeStyleSettings.class).NAMING_ABBREVIATIONS);
                    var clone = manager.cloneSettings(settings);
                    clone.getCustomSettings(SlangCodeStyleSettings.class).setNamingRules(List.of());
                    assertEquals(List.of(rule), custom.getNamingRules());
                    assertNotEquals(custom, clone.getCustomSettings(SlangCodeStyleSettings.class));
                    var emptyXml = new org.jdom.Element("code_scheme");
                    clone.writeExternal(emptyXml);
                    loaded.readExternal(emptyXml);
                    assertTrue(loaded.getCustomSettings(SlangCodeStyleSettings.class).getNamingRules().isEmpty());
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                var project = fixture.getProject();
                var editor = new NamingRuleDialog(project, rule);
                try {
                    assertEquals(rule, editor.rule());
                } finally {
                    editor.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE);
                }
                var panel = new NamingPanel(settings, project);
                var profiles = (com.intellij.profile.codeInspection.BaseInspectionProfileManager)
                        com.intellij.profile.codeInspection.InspectionProfileManager.getInstance(project);
                var previous = profiles.getCurrentProfile();
                boolean initialize = com.intellij.codeInspection.ex.InspectionProfileImpl.INIT_INSPECTIONS;
                com.intellij.codeInspection.ex.InspectionProfileImpl.INIT_INSPECTIONS = true;
                var supplier = new com.intellij.codeInspection.ex.InspectionToolsSupplier() {
                    @Override
                    public @org.jetbrains.annotations.NotNull List<com.intellij.codeInspection.ex.InspectionToolWrapper<?, ?>> createTools() {
                        return List.of(new com.intellij.codeInspection.ex.LocalInspectionToolWrapper(
                                new slanglsp.editor.InconsistentNamingInspection()));
                    }
                };
                var profile = new com.intellij.codeInspection.ex.InspectionProfileImpl("Naming checkbox test",
                        supplier, profiles);
                profiles.addProfile(profile);
                profiles.setRootProfile(profile.getName());
                try {
                    panel.reset(settings);
                    assertFalse(panel.isModified(settings));
                    var checkbox = findCheckbox(panel.getPanel());
                    assertNotNull(checkbox);
                    boolean original = checkbox.isSelected();
                    checkbox.setSelected(!original);
                    assertTrue(panel.isModified(settings));
                    panel.apply(settings);
                    panel.reset(settings);
                    assertEquals(!original, checkbox.isSelected());
                    checkbox.setSelected(original);
                    panel.apply(settings);
                } finally {
                    profiles.setRootProfile(previous.getName());
                    profiles.deleteProfile(profile);
                    Disposer.dispose(supplier);
                    Disposer.dispose(panel);
                    com.intellij.codeInspection.ex.InspectionProfileImpl.INIT_INSPECTIONS = initialize;
                }
            });
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }

    private static javax.swing.JCheckBox findCheckbox(java.awt.Component component) {
        if (component instanceof javax.swing.JCheckBox checkbox) {
            return checkbox;
        }
        if (component instanceof java.awt.Container container) {
            for (var child : container.getComponents()) {
                var checkbox = findCheckbox(child);
                if (checkbox != null) {
                    return checkbox;
                }
            }
        }
        return null;
    }
}
