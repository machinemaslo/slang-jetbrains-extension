package slanglsp.settings;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public final class SlangCodeStyleSettings extends CustomCodeStyleSettings {
    public String NAMING_ABBREVIATIONS = NamingRule.ABBREVIATIONS;
    private java.util.List<NamingRule> namingRules = NamingRule.slang();

    public java.util.List<NamingRule> getNamingRules() {
        return namingRules;
    }

    public void setNamingRules(java.util.List<NamingRule> rules) {
        namingRules = java.util.List.copyOf(rules);
    }

    @Override
    public void readExternal(org.jdom.Element parent) throws com.intellij.openapi.util.InvalidDataException {
        super.readExternal(parent);
        var custom = parent.getChild(getTagName());
        var rules = custom == null ? null : custom.getChild("naming");
        if (rules != null) {
            var loaded = new java.util.ArrayList<NamingRule>();
            for (var rule : rules.getChildren("rule")) {
                try {
                    var categories = java.util.EnumSet.noneOf(NamingRule.Category.class);
                    for (var category : rule.getAttributeValue("categories",
                            rule.getAttributeValue("category", "ALL")).split(",")) {
                        categories.add(NamingRule.Category.valueOf(category));
                    }
                    var variants = rule.getChildren("variant").stream()
                            .map(SlangCodeStyleSettings::readVariant).toList();
                    loaded.add(new NamingRule(rule.getAttributeValue("name", ""), categories,
                            variants.isEmpty() ? java.util.List.of(readVariant(rule)) : variants,
                            !"false".equals(rule.getAttributeValue("enabled"))));
                } catch (IllegalArgumentException ignored) {
                    // A newer plugin may have saved categories this version does not know.
                }
            }
            setNamingRules(loaded);
        }
    }

    private static NamingRule.Variant readVariant(org.jdom.Element element) {
        return new NamingRule.Variant(NamingRule.Style.valueOf(element.getAttributeValue("style", "ANY")),
                element.getAttributeValue("prefix", ""), element.getAttributeValue("suffix", ""));
    }

    @Override
    public void writeExternal(org.jdom.Element parent, @org.jetbrains.annotations.NotNull CustomCodeStyleSettings defaults)
            throws com.intellij.openapi.util.WriteExternalException {
        super.writeExternal(parent, defaults);
        var custom = parent.getChild(getTagName());
        if (custom == null) {
            custom = new org.jdom.Element(getTagName());
            parent.addContent(custom);
        }
        custom.removeChildren("naming");
        var rules = new org.jdom.Element("naming");
        for (var rule : namingRules) {
            var element = new org.jdom.Element("rule").setAttribute("name", rule.name())
                    .setAttribute("categories", rule.categories().stream().sorted().map(Enum::name)
                            .collect(java.util.stream.Collectors.joining(",")))
                    .setAttribute("enabled", Boolean.toString(rule.enabled()));
            for (var variant : rule.variants()) {
                element.addContent(new org.jdom.Element("variant").setAttribute("style", variant.style().name())
                        .setAttribute("prefix", variant.prefix()).setAttribute("suffix", variant.suffix()));
            }
            rules.addContent(element);
        }
        custom.addContent(rules);
    }

    @Override
    public boolean equals(Object other) {
        return super.equals(other) && other instanceof SlangCodeStyleSettings settings
                && namingRules.equals(settings.namingRules);
    }

    public boolean BRACES_ON_NEW_LINE = true;
    // 0: inherit, 1: end of line, 2: next line, 3: end of line with no space.
    public int FUNCTION_BRACES = 0;
    public int TYPE_BRACES = 0;
    public int CONTROL_BRACES = 0;
    public int NAMESPACE_BRACES = 0;
    public int BUFFER_BRACES = 0;
    public int SWITCH_BRACES = 0;
    public int CASE_BRACES = 0;
    public int INITIALIZER_BRACES = 0;
    public int STRUCT_BRACES = 0;
    public int CLASS_BRACES = 0;
    public int ENUM_BRACES = 0;

    // -1 follows the IDE's symmetric setting; 0 and 1 override spacing before the operator only.
    public int SPACE_BEFORE_ASSIGNMENT = -1;
    public boolean SPACE_WITHIN_GENERIC_ANGLES = false;
    public boolean SPACE_AFTER_LOGICAL_NOT = false;
    public boolean KEEP_BLANK_LINES_AFTER_LBRACE = true;
    public boolean DISABLE_FORMAT = false;
    // 0: All, 1: None, 2: Inner.
    public int NAMESPACE_INDENTATION = 0;

    void resetBraceOverrides() {
        FUNCTION_BRACES = TYPE_BRACES = CONTROL_BRACES = 0;
        NAMESPACE_BRACES = BUFFER_BRACES = SWITCH_BRACES = CASE_BRACES = INITIALIZER_BRACES = 0;
        STRUCT_BRACES = CLASS_BRACES = ENUM_BRACES = 0;
    }

    public boolean USE_CLANG_FORMAT = false;
    public boolean READ_CLANG_FORMAT = false;
    public boolean USE_CUSTOM_CLANG_FORMAT = false;
    public String CLANG_FORMAT_PATH = "";

    public String clangFormatExecutable() {
        String bundled = BundledClangFormat.executable();
        if (bundled != null && !USE_CUSTOM_CLANG_FORMAT) {
            return bundled;
        }

        String path = CLANG_FORMAT_PATH.trim();
        return isAbsoluteExecutablePath(path) ? path : null;
    }

    static boolean isAbsoluteExecutablePath(String path) {
        try {
            return Path.of(path).isAbsolute();
        } catch (InvalidPathException ignored) {
            return false;
        }
    }

    public SlangCodeStyleSettings(CodeStyleSettings container) {
        super("SlangCodeStyleSettings", container);
    }
}
