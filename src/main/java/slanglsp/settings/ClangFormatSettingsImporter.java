package slanglsp.settings;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
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
            if (config == null) config = current.findChild("_clang-format");
            if (config == null || config.isDirectory()) continue;
            if (config.getLength() > 1_000_000) return Map.of();
            var values = parse(com.intellij.openapi.vfs.VfsUtilCore.loadText(config));
            if ("InheritParentConfig".equals(values.get("BasedOnStyle"))) {
                var merged = new LinkedHashMap<>(find(current.getParent()));
                values.remove("BasedOnStyle");
                merged.putAll(values);
                return merged;
            }
            return values;
        }
        return Map.of();
    }

    static Map<String, Object> parse(String yaml) {
        var defaults = new LinkedHashMap<String, Object>();
        var cpp = new LinkedHashMap<String, Object>();
        for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(yaml)) {
            if (!(document instanceof Map<?, ?> map)) continue;
            Object language = map.get("Language");
            if (language != null && !language.equals("Cpp") && !language.equals("Slang")) continue;
            var target = language == null ? defaults : cpp;
            map.forEach((key, value) -> {
                if (key instanceof String name) target.put(name, value);
            });
        }
        defaults.putAll(cpp);
        return defaults;
    }

    static void apply(Map<String, Object> values, CodeStyleSettings settings) {
        var indent = settings.getIndentOptions(SlangFileType.INSTANCE);
        var custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
        Object base = values.get("BasedOnStyle");
        if (base != null) {
            switch (base.toString()) {
                case "LLVM", "Google", "Chromium", "Mozilla", "GNU" -> {
                    indent.INDENT_SIZE = 2;
                    indent.CONTINUATION_INDENT_SIZE = 4;
                    indent.TAB_SIZE = 8;
                    indent.USE_TAB_CHARACTER = false;
                    settings.setRightMargin(SlangLanguage.INSTANCE, 80);
                    custom.BRACES_ON_NEW_LINE = base.equals("GNU");
                }
                case "Microsoft" -> {
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
        Object braces = values.get("BreakBeforeBraces");
        if ("Allman".equals(braces) || "GNU".equals(braces)) custom.BRACES_ON_NEW_LINE = true;
        else if ("Attach".equals(braces)) custom.BRACES_ON_NEW_LINE = false;
        if (java.util.Set.of("Allman", "GNU", "Attach").contains(String.valueOf(braces))) {
            custom.FUNCTION_BRACES = custom.TYPE_BRACES = custom.CONTROL_BRACES = 0;
            var common = settings.getCommonSettings(SlangLanguage.INSTANCE);
            common.ELSE_ON_NEW_LINE = common.CATCH_ON_NEW_LINE = common.WHILE_ON_NEW_LINE = custom.BRACES_ON_NEW_LINE;
        }
    }

    private static int number(Map<String, Object> values, String key, int fallback, boolean zero) {
        Object value = values.get(key);
        return value instanceof Number n && n.intValue() >= (zero ? 0 : 1) && n.intValue() <= 10000
                ? n.intValue() : fallback;
    }
}
