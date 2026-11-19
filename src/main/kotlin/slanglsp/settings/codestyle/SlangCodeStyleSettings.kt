package slanglsp.settings.codestyle

import com.intellij.openapi.util.InvalidDataException
import com.intellij.openapi.util.WriteExternalException
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CustomCodeStyleSettings
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.*
import org.jdom.Element
import slanglsp.formatting.clang.BundledClangFormat

// Keep the existing XML tag and public JVM field names for persisted code-style schemes.
@Suppress("PropertyName")
class SlangCodeStyleSettings(container: CodeStyleSettings) :
    CustomCodeStyleSettings("SlangCodeStyleSettings", container) {
    @JvmField var NAMING_ABBREVIATIONS: String = NamingRule.ABBREVIATIONS
    private var rulesSnapshot = NamingRule.slang()
    val namingRules: List<NamingRule>
        get() = rulesSnapshot

    fun setNamingRules(rules: List<NamingRule>) {
        rulesSnapshot = rules.toList()
    }

    @Throws(InvalidDataException::class)
    override fun readExternal(parent: Element) {
        super.readExternal(parent)
        val custom = parent.getChild(tagName)
        val rules = if (custom == null) null else custom.getChild("naming")
        if (rules != null) {
            val loaded = ArrayList<NamingRule>()
            for (rule in rules.getChildren("rule")) {
                try {
                    val categories = EnumSet.noneOf(NamingRule.Category::class.java)
                    for (category in
                        rule
                            .getAttributeValue("categories", rule.getAttributeValue("category", "ALL"))
                            .split(",".toRegex())
                            .dropLastWhile { it.isEmpty() }
                            .toTypedArray()) {
                        categories.add(NamingRule.Category.valueOf(category))
                    }
                    val variants = rule.getChildren("variant").map(::readVariant)
                    loaded.add(
                        NamingRule(
                            rule.getAttributeValue("name", ""),
                            categories,
                            variants.ifEmpty { listOf(readVariant(rule)) },
                            "false" != rule.getAttributeValue("enabled")))
                } catch (_: IllegalArgumentException) {
                    // A newer plugin may have saved categories this version does not know.
                }
            }
            setNamingRules(loaded)
        }
    }

    @Throws(WriteExternalException::class)
    override fun writeExternal(parent: Element, defaults: CustomCodeStyleSettings) {
        super.writeExternal(parent, defaults)
        var custom = parent.getChild(tagName)
        if (custom == null) {
            custom = Element(tagName)
            parent.addContent(custom)
        }
        custom.removeChildren("naming")
        val rules = Element("naming")
        for (rule in namingRules) {
            val element =
                Element("rule")
                    .setAttribute("name", rule.name)
                    .setAttribute("categories", rule.categories.sorted().joinToString(",") { it.name })
                    .setAttribute("enabled", rule.enabled.toString())
            for ((style, prefix, suffix) in rule.variants) {
                element.addContent(
                    Element("variant")
                        .setAttribute("style", style.name)
                        .setAttribute("prefix", prefix)
                        .setAttribute("suffix", suffix))
            }
            rules.addContent(element)
        }
        custom.addContent(rules)
    }

    override fun equals(other: Any?): Boolean {
        return super.equals(other) && other is SlangCodeStyleSettings && namingRules == other.namingRules
    }

    // The platform superclass compares fields in equals but inherits an identity hash.
    override fun hashCode(): Int = 31 * namingRules.hashCode() + NAMING_ABBREVIATIONS.hashCode()

    @JvmField var BRACES_ON_NEW_LINE: Boolean = true

    // 0: inherit, 1: end of line, 2: next line, 3: end of line with no space.
    @JvmField var FUNCTION_BRACES: Int = 0
    @JvmField var TYPE_BRACES: Int = 0
    @JvmField var CONTROL_BRACES: Int = 0
    @JvmField var NAMESPACE_BRACES: Int = 0
    @JvmField var BUFFER_BRACES: Int = 0
    @JvmField var SWITCH_BRACES: Int = 0
    @JvmField var CASE_BRACES: Int = 0
    @JvmField var INITIALIZER_BRACES: Int = 0
    @JvmField var STRUCT_BRACES: Int = 0
    @JvmField var CLASS_BRACES: Int = 0
    @JvmField var ENUM_BRACES: Int = 0

    // -1 follows the IDE's symmetric setting; 0 and 1 override spacing before the operator only.
    @JvmField var SPACE_BEFORE_ASSIGNMENT: Int = -1
    @JvmField var SPACE_WITHIN_GENERIC_ANGLES: Boolean = false
    @JvmField var SPACE_AFTER_LOGICAL_NOT: Boolean = false
    @JvmField var KEEP_BLANK_LINES_AFTER_LBRACE: Boolean = true
    @JvmField var DISABLE_FORMAT: Boolean = false

    // 0: All, 1: None, 2: Inner.
    @JvmField var NAMESPACE_INDENTATION: Int = 0

    fun resetBraceOverrides() {
        CONTROL_BRACES = 0
        TYPE_BRACES = 0
        FUNCTION_BRACES = 0
        INITIALIZER_BRACES = 0
        CASE_BRACES = 0
        SWITCH_BRACES = 0
        BUFFER_BRACES = 0
        NAMESPACE_BRACES = 0
        ENUM_BRACES = 0
        CLASS_BRACES = 0
        STRUCT_BRACES = 0
    }

    @JvmField var USE_CLANG_FORMAT: Boolean = false
    @JvmField var READ_CLANG_FORMAT: Boolean = false
    @JvmField var USE_CUSTOM_CLANG_FORMAT: Boolean = false
    @JvmField var CLANG_FORMAT_PATH: String = ""

    fun clangFormatExecutable(): String? {
        val bundled = BundledClangFormat.executable()
        if (bundled != null && !USE_CUSTOM_CLANG_FORMAT) {
            return bundled
        }

        val path = CLANG_FORMAT_PATH.trim { it <= ' ' }
        return if (isAbsoluteExecutablePath(path)) path else null
    }

    companion object {
        private fun readVariant(element: Element): NamingRule.Variant {
            return NamingRule.Variant(
                NamingRule.Style.valueOf(element.getAttributeValue("style", "ANY")),
                element.getAttributeValue("prefix", ""),
                element.getAttributeValue("suffix", ""))
        }

        fun isAbsoluteExecutablePath(path: String): Boolean {
            return try {
                Path.of(path).isAbsolute
            } catch (_: InvalidPathException) {
                false
            }
        }
    }
}
