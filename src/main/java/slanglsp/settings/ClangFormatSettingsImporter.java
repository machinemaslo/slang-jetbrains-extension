package slanglsp.settings;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.modifier.CodeStyleSettingsModifier;
import com.intellij.psi.codeStyle.modifier.TransientCodeStyleSettings;
import com.intellij.psi.codeStyle.modifier.CodeStyleStatusBarUIContributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import slanglsp.language.SlangFileType;
import slanglsp.language.SlangLanguage;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Imports only settings that the native Slang model can represent. No subprocess is used.
 * The platform's per-file code-style modifier extension is still experimental.
 */
@SuppressWarnings("UnstableApiUsage")
public final class ClangFormatSettingsImporter implements CodeStyleSettingsModifier {
    private static final Logger LOG = Logger.getInstance(ClangFormatSettingsImporter.class);

    @Override
    public boolean modifySettings(@NotNull TransientCodeStyleSettings settings, @NotNull PsiFile file) {
        if (!file.getLanguage().isKindOf(SlangLanguage.INSTANCE)) return false;
        var custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
        if (!custom.READ_CLANG_FORMAT || custom.USE_CLANG_FORMAT) return false;
        settings.addDependency(com.intellij.openapi.vfs.VirtualFileManager.getInstance());
        var source = file.getUserData(com.intellij.psi.PsiFileFactory.ORIGINAL_FILE);
        if (source == null) source = file;
        if (source.getVirtualFile() == null) return false;
        try {
            var values = find(source.getVirtualFile().getParent());
            if (values.isEmpty()) return false;
            apply(values, settings);
            return true;
        } catch (java.io.IOException | org.yaml.snakeyaml.error.YAMLException failure) {
            LOG.warn("Cannot read Slang .clang-format settings", failure);
            return false;
        }
    }

    @Override
    public @NotNull String getName() {
        return slanglsp.SlangBundle.message("settings.clang.format.importer.name");
    }

    @Override
    public @Nullable CodeStyleStatusBarUIContributor getStatusBarUiContributor(
            @NotNull TransientCodeStyleSettings settings) {
        return null;
    }

    private static Map<String, Object> find(VirtualFile directory) throws java.io.IOException {
        for (var current = directory; current != null; current = current.getParent()) {
            var config = current.findChild(".clang-format");
            if (config == null || config.isDirectory()) {
                config = current.findChild("_clang-format");
            }
            if (config == null || config.isDirectory()) continue;
            if (config.getLength() > 1_000_000) return Map.of();
            var values = parse(com.intellij.openapi.vfs.VfsUtilCore.loadText(config));
            if ("InheritParentConfig".equals(values.get("BasedOnStyle"))) {
                var merged = new LinkedHashMap<>(find(current.getParent()));
                values.remove("BasedOnStyle");
                mergeOptions(merged, values);
                return merged;
            }
            return values;
        }
        return Map.of();
    }

    static Map<String, Object> parse(String yaml) {
        var defaults = new LinkedHashMap<String, Object>();
        var cpp = new LinkedHashMap<String, Object>();
        var slang = new LinkedHashMap<String, Object>();
        for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(yaml)) {
            if (!(document instanceof Map<?, ?> map)) {
                continue;
            }
            switch (String.valueOf(map.get("Language"))) {
                case "null" -> mergeOptions(defaults, map);
                case "Cpp" -> mergeOptions(cpp, map);
                case "Slang" -> mergeOptions(slang, map);
                default -> {
                    // Ignore sections dedicated to other languages.
                }
            }
        }
        mergeOptions(defaults, cpp);
        mergeOptions(defaults, slang);
        return defaults;
    }

    private static void mergeOptions(Map<String, Object> target, Map<?, ?> source) {
        source.forEach((key, value) -> {
            if (!(key instanceof String name) || name.equals("Language")) {
                return;
            }
            if (target.get(name) instanceof Map<?, ?> previous && value instanceof Map<?, ?> overrides) {
                var combined = new LinkedHashMap<Object, Object>(previous);
                overrides.forEach(combined::put);
                target.put(name, combined);
            } else {
                target.put(name, value);
            }
        });
    }

    static void apply(Map<String, Object> values, CodeStyleSettings settings) {
        var indent = settings.getIndentOptions(SlangFileType.INSTANCE);
        var custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
        Object base = values.get("BasedOnStyle");
        if (base != null) {
            switch (base.toString().toLowerCase(Locale.ROOT)) {
                case "llvm", "google", "chromium", "mozilla", "gnu" -> {
                    indent.INDENT_SIZE = 2;
                    indent.CONTINUATION_INDENT_SIZE = 4;
                    indent.TAB_SIZE = 8;
                    indent.USE_TAB_CHARACTER = false;
                    settings.setRightMargin(SlangLanguage.INSTANCE, 80);
                    custom.BRACES_ON_NEW_LINE = "GNU".equalsIgnoreCase(base.toString());
                }
                case "microsoft" -> {
                    indent.INDENT_SIZE = 4;
                    indent.CONTINUATION_INDENT_SIZE = 4;
                    indent.TAB_SIZE = 4;
                    indent.USE_TAB_CHARACTER = false;
                    settings.setRightMargin(SlangLanguage.INSTANCE, 120);
                    custom.BRACES_ON_NEW_LINE = true;
                }
                default -> { /* Unrepresented presets leave native defaults intact. */ }
            }
        }
        indent.INDENT_SIZE = number(values, "IndentWidth", indent.INDENT_SIZE, false);
        indent.CONTINUATION_INDENT_SIZE = number(values, "ContinuationIndentWidth", indent.CONTINUATION_INDENT_SIZE, false);
        indent.TAB_SIZE = number(values, "TabWidth", indent.TAB_SIZE, false);
        int margin = number(values, "ColumnLimit", settings.getRightMargin(SlangLanguage.INSTANCE), true);
        settings.setRightMargin(SlangLanguage.INSTANCE, margin == 0 ? Integer.MAX_VALUE : margin);
        if (values.containsKey("UseTab")) {
            String tabs = values.get("UseTab").toString();
            if (tabs.equals("Never")) indent.USE_TAB_CHARACTER = false;
            else if (java.util.Set.of("ForIndentation", "ForContinuationAndIndentation",
                    "AlignWithSpaces", "Always").contains(tabs)) indent.USE_TAB_CHARACTER = true;
        }
        var common = settings.getCommonSettings(SlangLanguage.INSTANCE);
        custom.DISABLE_FORMAT = flag(values, "DisableFormat", custom.DISABLE_FORMAT);
        applyBraceStyle(values, custom, common);
        applySpacing(values, custom, common);
        common.INDENT_CASE_FROM_SWITCH = flag(values, "IndentCaseLabels", common.INDENT_CASE_FROM_SWITCH);
        custom.NAMESPACE_INDENTATION = switch (String.valueOf(values.get("NamespaceIndentation"))) {
            case "All" -> 0;
            case "None" -> 1;
            case "Inner" -> 2;
            default -> custom.NAMESPACE_INDENTATION;
        };
        common.KEEP_BLANK_LINES_IN_CODE = number(values, "MaxEmptyLinesToKeep", common.KEEP_BLANK_LINES_IN_CODE, true);
        common.KEEP_BLANK_LINES_IN_DECLARATIONS = number(values, "MaxEmptyLinesToKeep",
                common.KEEP_BLANK_LINES_IN_DECLARATIONS, true);
        common.KEEP_BLANK_LINES_BEFORE_RBRACE = number(values, "MaxEmptyLinesToKeep",
                common.KEEP_BLANK_LINES_BEFORE_RBRACE, true);
        custom.KEEP_BLANK_LINES_AFTER_LBRACE = flag(options(values.get("KeepEmptyLines")), "AtStartOfBlock",
                flag(values, "KeepEmptyLinesAtTheStartOfBlocks", custom.KEEP_BLANK_LINES_AFTER_LBRACE));
    }

    private static void applyBraceStyle(Map<String, Object> values, SlangCodeStyleSettings custom,
            CommonCodeStyleSettings common) {
        String style = String.valueOf(values.get("BreakBeforeBraces"));
        switch (style) {
            case "Allman", "GNU", "Attach", "Linux", "Mozilla", "Stroustrup", "WebKit", "Custom" -> {
                custom.resetBraceOverrides();
                custom.BRACES_ON_NEW_LINE = style.equals("Allman") || style.equals("GNU");
                common.ELSE_ON_NEW_LINE = custom.BRACES_ON_NEW_LINE || style.equals("Stroustrup");
                common.CATCH_ON_NEW_LINE = common.ELSE_ON_NEW_LINE;
                common.WHILE_ON_NEW_LINE = style.equals("GNU");
            }
            default -> {
                return;
            }
        }

        switch (style) {
            case "Linux" -> custom.FUNCTION_BRACES = custom.NAMESPACE_BRACES = custom.CLASS_BRACES = 2;
            case "Mozilla" -> custom.FUNCTION_BRACES = custom.STRUCT_BRACES = custom.CLASS_BRACES = custom.ENUM_BRACES = 2;
            case "Stroustrup", "WebKit" -> custom.FUNCTION_BRACES = 2;
            case "Custom" -> applyBraceWrapping(options(values.get("BraceWrapping")), custom, common);
            default -> {
                // The basic placement above is sufficient for these styles.
            }
        }
    }

    private static void applyBraceWrapping(Map<?, ?> wrapping, SlangCodeStyleSettings custom,
            CommonCodeStyleSettings common) {
        custom.FUNCTION_BRACES = placement(wrapping, "AfterFunction");
        custom.NAMESPACE_BRACES = placement(wrapping, "AfterNamespace");
        custom.STRUCT_BRACES = placement(wrapping, "AfterStruct");
        custom.CLASS_BRACES = placement(wrapping, "AfterClass");
        custom.ENUM_BRACES = placement(wrapping, "AfterEnum");
        custom.CASE_BRACES = placement(wrapping, "AfterCaseLabel");
        if ("Always".equals(wrapping.get("AfterControlStatement")) || flag(wrapping, "AfterControlStatement", false)) {
            custom.CONTROL_BRACES = custom.SWITCH_BRACES = 2;
        }
        // ClangFormat does not use AfterControlStatement for initializer lists or case label blocks.
        custom.INITIALIZER_BRACES = 1;
        common.ELSE_ON_NEW_LINE = flag(wrapping, "BeforeElse", false);
        common.CATCH_ON_NEW_LINE = flag(wrapping, "BeforeCatch", false);
        common.WHILE_ON_NEW_LINE = flag(wrapping, "BeforeWhile", false);
    }

    private static void applySpacing(Map<String, Object> values, SlangCodeStyleSettings custom,
            CommonCodeStyleSettings common) {
        if (values.get("SpaceBeforeAssignmentOperators") instanceof Boolean before) {
            custom.SPACE_BEFORE_ASSIGNMENT = before ? 1 : 0;
        }
        switch (String.valueOf(values.get("SpacesInAngles"))) {
            case "Always", "true" -> custom.SPACE_WITHIN_GENERIC_ANGLES = true;
            case "Never", "false" -> custom.SPACE_WITHIN_GENERIC_ANGLES = false;
            default -> {
                // Conditional angle spacing is not supported by the native formatter.
            }
        }
        if (values.get("SpaceBeforeCpp11BracedList") instanceof Boolean before && custom.INITIALIZER_BRACES != 2
                && (custom.INITIALIZER_BRACES != 0 || !custom.BRACES_ON_NEW_LINE && custom.CONTROL_BRACES != 2)) {
            custom.INITIALIZER_BRACES = before ? 1 : 3;
        }
        common.SPACE_WITHIN_BRACKETS = flag(values, "SpacesInSquareBrackets", common.SPACE_WITHIN_BRACKETS);
        custom.SPACE_AFTER_LOGICAL_NOT = flag(values, "SpaceAfterLogicalNot", custom.SPACE_AFTER_LOGICAL_NOT);
        common.SPACE_WITHIN_PARENTHESES = flag(values, "SpacesInParentheses", common.SPACE_WITHIN_PARENTHESES);
        if ("Never".equals(values.get("SpacesInParens"))) {
            common.SPACE_WITHIN_PARENTHESES = false;
        }

        boolean beforeControls;
        boolean beforeCalls;
        switch (String.valueOf(values.get("SpaceBeforeParens"))) {
            case "Never" -> beforeControls = beforeCalls = false;
            case "Always" -> beforeControls = beforeCalls = true;
            case "ControlStatements", "ControlStatementsExceptControlMacros" -> {
                beforeControls = true;
                beforeCalls = false;
            }
            case "Custom" -> {
                var options = options(values.get("SpaceBeforeParensOptions"));
                beforeControls = flag(options, "AfterControlStatements", false);
                // ClangFormat can distinguish declarations from calls; the native formatter cannot.
                beforeCalls = common.SPACE_BEFORE_METHOD_CALL_PARENTHESES;
            }
            default -> {
                return;
            }
        }

        common.SPACE_BEFORE_IF_PARENTHESES = common.SPACE_BEFORE_FOR_PARENTHESES = beforeControls;
        common.SPACE_BEFORE_WHILE_PARENTHESES = common.SPACE_BEFORE_SWITCH_PARENTHESES = beforeControls;
        common.SPACE_BEFORE_CATCH_PARENTHESES = beforeControls;
        common.SPACE_BEFORE_METHOD_CALL_PARENTHESES = beforeCalls;
    }

    private static int placement(Map<?, ?> values, String key) {
        return flag(values, key, false) ? 2 : 1;
    }

    private static boolean flag(Map<?, ?> values, String key, boolean fallback) {
        return values.get(key) instanceof Boolean enabled ? enabled : fallback;
    }

    private static Map<?, ?> options(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static int number(Map<String, Object> values, String key, int fallback, boolean zero) {
        Object value = values.get(key);
        return value instanceof Number n && n.intValue() >= (zero ? 0 : 1) && n.intValue() <= 10000
                ? n.intValue() : fallback;
    }
}
