package slanglsp.settings;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;

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
                    loaded.add(new NamingRule(rule.getAttributeValue("name", ""),
                            NamingRule.Category.valueOf(rule.getAttributeValue("category", "ALL")),
                            NamingRule.Style.valueOf(rule.getAttributeValue("style", "ANY")),
                            rule.getAttributeValue("prefix", ""), rule.getAttributeValue("suffix", "")));
                } catch (IllegalArgumentException ignored) {
                    // A newer plugin may have saved categories this version does not know.
                }
            }
            setNamingRules(loaded);
        }
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
            rules.addContent(new org.jdom.Element("rule").setAttribute("name", rule.name())
                    .setAttribute("category", rule.category().name()).setAttribute("style", rule.style().name())
                    .setAttribute("prefix", rule.prefix()).setAttribute("suffix", rule.suffix()));
        }
        custom.addContent(rules);
    }

    @Override
    public boolean equals(Object other) {
        return super.equals(other) && other instanceof SlangCodeStyleSettings settings
                && namingRules.equals(settings.namingRules);
    }

    public boolean BRACES_ON_NEW_LINE = true;
    // 0: follow the common brace setting, 1: same line, 2: next line.
    public int FUNCTION_BRACES = 0;
    public int TYPE_BRACES = 0;
    public int CONTROL_BRACES = 0;
    public boolean USE_CLANG_FORMAT = false;
    public boolean READ_CLANG_FORMAT = false;
    public boolean USE_CUSTOM_CLANG_FORMAT = false;
    public boolean USE_BUNDLED_CLANG_FORMAT = true;
    public String CLANG_FORMAT_PATH = "";

    public String clangFormatExecutable() {
        if (USE_CUSTOM_CLANG_FORMAT) {
            return CLANG_FORMAT_PATH.trim();
        }
        if (USE_BUNDLED_CLANG_FORMAT) {
            return BundledClangFormat.executable();
        }
        return "clang-format";
    }

    public SlangCodeStyleSettings(CodeStyleSettings container) {
        super("SlangCodeStyleSettings", container);
    }
}
