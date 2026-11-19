package slanglsp.formatting.clang

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.modifier.CodeStyleSettingsModifier
import com.intellij.psi.codeStyle.modifier.CodeStyleStatusBarUIContributor
import com.intellij.psi.codeStyle.modifier.TransientCodeStyleSettings
import java.io.IOException
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import slanglsp.SlangBundle
import slanglsp.language.SlangFileType
import slanglsp.language.SlangLanguage
import slanglsp.settings.codestyle.SlangCodeStyleSettings

/**
 * Imports only settings that the native Slang model can represent. No subprocess is used. The platform's per-file
 * code-style modifier extension is still experimental.
 */
@Suppress("UnstableApiUsage")
class ClangFormatSettingsImporter : CodeStyleSettingsModifier {
    @JvmRecord private data class ParsedConfig(val stamp: Long, val options: Map<String, Any?>)

    override fun modifySettings(settings: TransientCodeStyleSettings, file: PsiFile): Boolean {
        if (!file.language.isKindOf(SlangLanguage.INSTANCE)) {
            return false
        }
        val custom = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
        if (!custom.READ_CLANG_FORMAT || custom.USE_CLANG_FORMAT) {
            return false
        }
        settings.addDependency(VirtualFileManager.getInstance())
        var source = file.getUserData<PsiFile?>(PsiFileFactory.ORIGINAL_FILE)
        if (source == null) {
            source = file
        }
        if (source.virtualFile == null) {
            return false
        }
        try {
            val values = Options.find(source.virtualFile.parent)
            if (values.isEmpty()) {
                return false
            }
            Options.apply(values, settings)
            return true
        } catch (failure: IOException) {
            Options.LOG.warn("Cannot read Slang .clang-format settings", failure)
            return false
        } catch (failure: YAMLException) {
            Options.LOG.warn("Cannot read Slang .clang-format settings", failure)
            return false
        }
    }

    override fun getName(): String {
        return SlangBundle.message("settings.clang.format.importer.name")
    }

    override fun getStatusBarUiContributor(settings: TransientCodeStyleSettings): CodeStyleStatusBarUIContributor? {
        return null
    }

    internal object Options {
        val LOG = Logger.getInstance(ClangFormatSettingsImporter::class.java)
        private val PARSED_CONFIG: Key<ParsedConfig?> = Key.create("slang.clangFormat.parsedConfig")

        @Throws(IOException::class)
        fun find(directory: VirtualFile?): Map<String, Any?> {
            var current = directory
            while (current != null) {
                var config = current.findChild(".clang-format")
                if (config == null || config.isDirectory) {
                    config = current.findChild("_clang-format")
                }
                if (config == null || config.isDirectory) {
                    current = current.parent
                    continue
                }
                if (config.length > 1000000) {
                    return emptyMap()
                }
                val values = readOptions(config)
                if ("InheritParentConfig" == values["BasedOnStyle"]) {
                    val merged = find(current.parent).toMutableMap()
                    val overrides = values.toMutableMap()
                    overrides.remove("BasedOnStyle")
                    mergeOptions(merged, overrides)
                    return merged
                }
                return values
            }
            return emptyMap()
        }

        @JvmStatic
        @Throws(IOException::class)
        fun readOptions(config: VirtualFile): Map<String, Any?> {
            val stamp = config.modificationStamp
            val cached: ParsedConfig? = config.getUserData(PARSED_CONFIG)
            if (cached != null && cached.stamp == stamp) {
                return cached.options
            }
            val options = parse(VfsUtilCore.loadText(config))
            if (config.modificationStamp == stamp) {
                config.putUserData(PARSED_CONFIG, ParsedConfig(stamp, options))
            }
            return options
        }

        @JvmStatic
        fun parse(yaml: String): Map<String, Any?> {
            val defaults = mutableMapOf<String, Any?>()
            val cpp = mutableMapOf<String, Any?>()
            val slang = mutableMapOf<String, Any?>()
            for (document in Yaml(SafeConstructor(LoaderOptions())).loadAll(yaml)) {
                if (document !is Map<*, *>) {
                    continue
                }
                when (document["Language"].toString()) {
                    "null" -> mergeOptions(defaults, document)
                    "Cpp" -> mergeOptions(cpp, document)
                    "Slang" -> mergeOptions(slang, document)
                    else -> {}
                }
            }
            mergeOptions(defaults, cpp)
            mergeOptions(defaults, slang)
            return defaults
        }

        private fun mergeOptions(target: MutableMap<String, Any?>, source: Map<*, *>) {
            source.forEach { (key: Any?, value: Any?) ->
                if (key !is String || key == "Language") {
                    return@forEach
                }
                val previous = target[key]
                if (previous is Map<*, *> && value is Map<*, *>) {
                    val combined: LinkedHashMap<Any?, Any?> = LinkedHashMap(previous)
                    combined.putAll(value)
                    target[key] = combined
                } else {
                    target[key] = value
                }
            }
        }

        fun apply(values: Map<String, Any?>, settings: CodeStyleSettings) {
            val indent = settings.getIndentOptions(SlangFileType)
            val custom = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
            val base = values["BasedOnStyle"]
            if (base != null) {
                when (base.toString().lowercase()) {
                    "llvm",
                    "google",
                    "chromium",
                    "mozilla",
                    "gnu" -> {
                        indent.INDENT_SIZE = 2
                        indent.CONTINUATION_INDENT_SIZE = 4
                        indent.TAB_SIZE = 8
                        indent.USE_TAB_CHARACTER = false
                        settings.setRightMargin(SlangLanguage.INSTANCE, 80)
                        custom.BRACES_ON_NEW_LINE = "GNU".equals(base.toString(), ignoreCase = true)
                    }

                    "microsoft" -> {
                        indent.INDENT_SIZE = 4
                        indent.CONTINUATION_INDENT_SIZE = 4
                        indent.TAB_SIZE = 4
                        indent.USE_TAB_CHARACTER = false
                        settings.setRightMargin(SlangLanguage.INSTANCE, 120)
                        custom.BRACES_ON_NEW_LINE = true
                    }

                    else -> {}
                }
            }
            indent.INDENT_SIZE = number(values, "IndentWidth", indent.INDENT_SIZE, false)
            indent.CONTINUATION_INDENT_SIZE =
                number(values, "ContinuationIndentWidth", indent.CONTINUATION_INDENT_SIZE, false)
            indent.TAB_SIZE = number(values, "TabWidth", indent.TAB_SIZE, false)
            val margin: Int = number(values, "ColumnLimit", settings.getRightMargin(SlangLanguage.INSTANCE), true)
            settings.setRightMargin(SlangLanguage.INSTANCE, if (margin == 0) Int.MAX_VALUE else margin)
            if (values.containsKey("UseTab")) {
                val tabs = values["UseTab"].toString()
                if (tabs == "Never") {
                    indent.USE_TAB_CHARACTER = false
                } else if (mutableSetOf<String?>(
                        "ForIndentation", "ForContinuationAndIndentation", "AlignWithSpaces", "Always")
                    .contains(tabs)) {
                    indent.USE_TAB_CHARACTER = true
                }
            }
            val common = settings.getCommonSettings(SlangLanguage.INSTANCE)
            custom.DISABLE_FORMAT = flag(values, "DisableFormat", custom.DISABLE_FORMAT)
            applyBraceStyle(values, custom, common)
            applySpacing(values, custom, common)
            common.INDENT_CASE_FROM_SWITCH = flag(values, "IndentCaseLabels", common.INDENT_CASE_FROM_SWITCH)
            custom.NAMESPACE_INDENTATION =
                when (values["NamespaceIndentation"].toString()) {
                    "All" -> 0
                    "None" -> 1
                    "Inner" -> 2
                    else -> custom.NAMESPACE_INDENTATION
                }
            common.KEEP_BLANK_LINES_IN_CODE =
                number(values, "MaxEmptyLinesToKeep", common.KEEP_BLANK_LINES_IN_CODE, true)
            common.KEEP_BLANK_LINES_IN_DECLARATIONS =
                number(values, "MaxEmptyLinesToKeep", common.KEEP_BLANK_LINES_IN_DECLARATIONS, true)
            common.KEEP_BLANK_LINES_BEFORE_RBRACE =
                number(values, "MaxEmptyLinesToKeep", common.KEEP_BLANK_LINES_BEFORE_RBRACE, true)
            custom.KEEP_BLANK_LINES_AFTER_LBRACE =
                flag(
                    options(values["KeepEmptyLines"]),
                    "AtStartOfBlock",
                    flag(values, "KeepEmptyLinesAtTheStartOfBlocks", custom.KEEP_BLANK_LINES_AFTER_LBRACE))
        }

        private fun applyBraceStyle(
            values: Map<String, Any?>,
            custom: SlangCodeStyleSettings,
            common: CommonCodeStyleSettings
        ) {
            val style = values["BreakBeforeBraces"].toString()
            when (style) {
                "Allman",
                "GNU",
                "Attach",
                "Linux",
                "Mozilla",
                "Stroustrup",
                "WebKit",
                "Custom" -> {
                    custom.resetBraceOverrides()
                    custom.BRACES_ON_NEW_LINE = style == "Allman" || style == "GNU"
                    common.ELSE_ON_NEW_LINE = custom.BRACES_ON_NEW_LINE || style == "Stroustrup"
                    common.CATCH_ON_NEW_LINE = common.ELSE_ON_NEW_LINE
                    common.WHILE_ON_NEW_LINE = style == "GNU"
                }

                else -> {
                    return
                }
            }

            when (style) {
                "Linux" -> {
                    custom.CLASS_BRACES = 2
                    custom.NAMESPACE_BRACES = custom.CLASS_BRACES
                    custom.FUNCTION_BRACES = custom.NAMESPACE_BRACES
                }

                "Mozilla" -> {
                    custom.ENUM_BRACES = 2
                    custom.CLASS_BRACES = custom.ENUM_BRACES
                    custom.STRUCT_BRACES = custom.CLASS_BRACES
                    custom.FUNCTION_BRACES = custom.STRUCT_BRACES
                }

                "Stroustrup",
                "WebKit" -> custom.FUNCTION_BRACES = 2
                "Custom" -> applyBraceWrapping(options(values["BraceWrapping"]), custom, common)
                else -> {}
            }
        }

        private fun applyBraceWrapping(
            wrapping: Map<*, *>,
            custom: SlangCodeStyleSettings,
            common: CommonCodeStyleSettings
        ) {
            custom.FUNCTION_BRACES = placement(wrapping, "AfterFunction")
            custom.NAMESPACE_BRACES = placement(wrapping, "AfterNamespace")
            custom.STRUCT_BRACES = placement(wrapping, "AfterStruct")
            custom.CLASS_BRACES = placement(wrapping, "AfterClass")
            custom.ENUM_BRACES = placement(wrapping, "AfterEnum")
            custom.CASE_BRACES = placement(wrapping, "AfterCaseLabel")
            if ("Always" == wrapping["AfterControlStatement"] || flag(wrapping, "AfterControlStatement", false)) {
                custom.SWITCH_BRACES = 2
                custom.CONTROL_BRACES = custom.SWITCH_BRACES
            }
            // ClangFormat does not use AfterControlStatement for initializer lists or case label blocks.
            custom.INITIALIZER_BRACES = 1
            common.ELSE_ON_NEW_LINE = flag(wrapping, "BeforeElse", false)
            common.CATCH_ON_NEW_LINE = flag(wrapping, "BeforeCatch", false)
            common.WHILE_ON_NEW_LINE = flag(wrapping, "BeforeWhile", false)
        }

        private fun applySpacing(
            values: Map<String, Any?>,
            custom: SlangCodeStyleSettings,
            common: CommonCodeStyleSettings
        ) {
            val before = values["SpaceBeforeAssignmentOperators"]
            if (before is Boolean) {
                custom.SPACE_BEFORE_ASSIGNMENT = if (before) 1 else 0
            }
            when (values["SpacesInAngles"].toString()) {
                "Always",
                "true" -> custom.SPACE_WITHIN_GENERIC_ANGLES = true
                "Never",
                "false" -> custom.SPACE_WITHIN_GENERIC_ANGLES = false
                else -> {}
            }
            val beforeInitializer = values["SpaceBeforeCpp11BracedList"]
            if (beforeInitializer is Boolean &&
                custom.INITIALIZER_BRACES != 2 &&
                (custom.INITIALIZER_BRACES != 0 || !custom.BRACES_ON_NEW_LINE && custom.CONTROL_BRACES != 2)) {
                custom.INITIALIZER_BRACES = if (beforeInitializer) 1 else 3
            }
            common.SPACE_WITHIN_BRACKETS = flag(values, "SpacesInSquareBrackets", common.SPACE_WITHIN_BRACKETS)
            custom.SPACE_AFTER_LOGICAL_NOT = flag(values, "SpaceAfterLogicalNot", custom.SPACE_AFTER_LOGICAL_NOT)
            common.SPACE_WITHIN_PARENTHESES = flag(values, "SpacesInParentheses", common.SPACE_WITHIN_PARENTHESES)
            if ("Never" == values["SpacesInParens"]) {
                common.SPACE_WITHIN_PARENTHESES = false
            }

            val (beforeControls, beforeCalls) =
                when (values["SpaceBeforeParens"].toString()) {
                    "Never" -> false to false
                    "Always" -> true to true
                    "ControlStatements",
                    "ControlStatementsExceptControlMacros" -> true to false
                    "Custom" -> {
                        val options = options(values["SpaceBeforeParensOptions"])
                        // ClangFormat can distinguish declarations from calls; the native formatter cannot.
                        flag(options, "AfterControlStatements", false) to common.SPACE_BEFORE_METHOD_CALL_PARENTHESES
                    }
                    else -> return
                }

            common.SPACE_BEFORE_FOR_PARENTHESES = beforeControls
            common.SPACE_BEFORE_IF_PARENTHESES = beforeControls
            common.SPACE_BEFORE_SWITCH_PARENTHESES = beforeControls
            common.SPACE_BEFORE_WHILE_PARENTHESES = beforeControls
            common.SPACE_BEFORE_CATCH_PARENTHESES = beforeControls
            common.SPACE_BEFORE_METHOD_CALL_PARENTHESES = beforeCalls
        }

        private fun placement(values: Map<*, *>, key: String): Int {
            return if (flag(values, key, false)) 2 else 1
        }

        private fun flag(values: Map<*, *>, key: String, fallback: Boolean): Boolean {
            return values[key] as? Boolean ?: fallback
        }

        private fun options(value: Any?): Map<*, *> {
            return value as? Map<*, *> ?: emptyMap<Any?, Any?>()
        }

        private fun number(values: Map<String, Any?>, key: String, fallback: Int, zero: Boolean): Int {
            val value = values[key]
            return if (value is Number && value.toInt() >= (if (zero) 0 else 1) && value.toInt() <= 10000) value.toInt()
            else fallback
        }
    }
}
