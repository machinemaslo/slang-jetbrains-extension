package slanglsp.settings.codestyle

import java.util.regex.Pattern
import slanglsp.SlangBundle

/** Immutable, ordered code-style rule. The first matching category wins. */
class NamingRule(val name: String, categories: Set<Category>, variants: List<Variant>, val enabled: Boolean) {
    @JvmRecord
    data class Variant(val style: Style, val prefix: String, val suffix: String) {
        internal fun preview(): String {
            if (style == Style.ANY && prefix.isEmpty() && suffix.isEmpty()) {
                return label(style)
            }

            val sample =
                when (style) {
                    Style.SNAKE_CASE -> "all_lower"
                    Style.LOWER_CAMEL_CASE -> "lower_camel_case"
                    Style.UPPER_CAMEL_CASE -> "upper_camel_case"
                    Style.SCREAMING_SNAKE_CASE -> "screaming_snake_case"
                    Style.ANY -> "sampleName"
                }
            return checkNotNull(format(this, sample, ""))
        }
    }

    constructor(
        name: String,
        category: Category,
        style: Style,
        prefix: String,
        suffix: String
    ) : this(name, setOf(category), listOf(Variant(style, prefix, suffix)), enabled = true)

    fun category(): Category {
        return categories.min()
    }

    fun matches(category: Category?): Boolean {
        return Category.ALL in categories || category in categories
    }

    enum class Category {
        TYPES,
        INTERFACES,
        ENUMS,
        ENUM_MEMBERS,
        NAMESPACES,
        FUNCTIONS,
        METHODS,
        FIELDS,
        GLOBALS,
        CONSTANTS,
        GENERIC_PARAMETERS,
        PARAMETERS,
        LOCALS,
        ALL
    }

    enum class Style {
        SNAKE_CASE,
        LOWER_CAMEL_CASE,
        UPPER_CAMEL_CASE,
        SCREAMING_SNAKE_CASE,
        ANY
    }

    fun suggest(identifier: String, abbreviations: String): String {
        if (!enabled) {
            return identifier
        }
        for (variant in variants) {
            if (suggest(variant, identifier, abbreviations) == identifier) {
                return identifier
            }
        }
        return suggest(variants.first(), identifier, abbreviations)
    }

    val categories: Set<Category> = if (Category.ALL in categories) setOf(Category.ALL) else categories.toSet()
    val variants: List<Variant> = variants.toList()

    init {
        require(categories.isNotEmpty() && variants.isNotEmpty()) {
            "A naming rule needs at least one category and style"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is NamingRule &&
            name == other.name &&
            categories == other.categories &&
            variants == other.variants &&
            enabled == other.enabled

    override fun hashCode(): Int = java.util.Objects.hash(name, categories, variants, enabled)

    companion object {
        const val ABBREVIATIONS: String = "GPU, CPU, API, UV, ID, BRDF, SSA, D3D"

        private val WORDS: Pattern = Pattern.compile("[A-Z]+(?=[A-Z][a-z]|[0-9]|$)|[A-Z]?[a-z]+|[A-Z]+|[0-9]+")
        private val ACRONYM_SEPARATOR = Regex("[,;\\s]+")

        private fun suggest(variant: Variant, identifier: String, abbreviations: String): String {
            val (_, prefix, suffix) = variant
            var base = identifier
            val prefixBoundary = prefix.length > 1 || prefix == "_" || (base.getOrNull(1)?.isUpperCase() == true)
            if (prefix.isNotEmpty() && base.startsWith(prefix) && prefixBoundary) {
                base = base.substring(prefix.length)
            }
            if (suffix.isNotEmpty() && base.endsWith(suffix)) {
                base = base.substring(0, base.length - suffix.length)
            }

            return format(variant, base, abbreviations) ?: identifier
        }

        private fun format(variant: Variant, base: String, abbreviations: String): String? {
            val (style, prefix, suffix) = variant
            val acronyms =
                abbreviations.split(ACRONYM_SEPARATOR).filter { it.isNotEmpty() }.mapTo(hashSetOf()) { it.uppercase() }
            val words = mutableListOf<String>()
            val acronymPattern =
                acronyms
                    .sortedByDescending { it.length }
                    .joinToString("|") {
                        "(?i:${Pattern.quote(it)})(?=[A-Z_0-9]|$)"
                    }
            val pattern = if (acronymPattern.isEmpty()) WORDS else Pattern.compile("$acronymPattern|${WORDS.pattern()}")
            val matcher = pattern.matcher(base)
            while (matcher.find()) {
                words.add(matcher.group().lowercase())
            }
            if (words.isEmpty() && style != Style.ANY) {
                return null
            }
            val converted =
                when (style) {
                    Style.SNAKE_CASE -> words.joinToString("_")
                    Style.SCREAMING_SNAKE_CASE -> words.joinToString("_").uppercase()
                    Style.LOWER_CAMEL_CASE,
                    Style.UPPER_CAMEL_CASE ->
                        buildString {
                            for ((i, word) in words.withIndex()) {
                                val uppercase = word.uppercase()
                                append(
                                    when {
                                        i == 0 && style == Style.LOWER_CAMEL_CASE -> word
                                        uppercase in acronyms -> uppercase
                                        else -> word.replaceFirstChar { it.uppercaseChar() }
                                    })
                            }
                        }

                    Style.ANY -> base
                }
            return "$prefix$converted$suffix"
        }

        @JvmStatic
        fun defaults(camel: Boolean): List<NamingRule> {
            if (camel) {
                return slang()
            }
            val rules: MutableList<NamingRule> = ArrayList()
            for (category in Category.entries) {
                if (category == Category.ALL) {
                    continue
                }
                val style = if (category == Category.GENERIC_PARAMETERS) Style.UPPER_CAMEL_CASE else Style.SNAKE_CASE
                rules.add(NamingRule(label(category), category, style, "", ""))
            }
            return rules.toList()
        }

        /** Slang's documented recommendations, checked against its shader examples and enum tests. */
        @JvmStatic
        fun slang(): List<NamingRule> {
            val rules: MutableList<NamingRule> = ArrayList()
            for (category in Category.entries) {
                if (category == Category.ALL) {
                    continue
                }
                val style =
                    when (category) {
                        Category.TYPES,
                        Category.INTERFACES,
                        Category.ENUMS,
                        Category.ENUM_MEMBERS,
                        Category.NAMESPACES,
                        Category.GENERIC_PARAMETERS,
                        Category.GLOBALS,
                        Category.CONSTANTS -> Style.UPPER_CAMEL_CASE
                        else -> Style.LOWER_CAMEL_CASE
                    }
                val prefix =
                    when (category) {
                        Category.INTERFACES -> "I"
                        Category.GLOBALS -> "g"
                        Category.CONSTANTS -> "k"
                        else -> ""
                    }
                rules.add(NamingRule(label(category), category, style, prefix, ""))
            }
            return rules.toList()
        }

        @JvmStatic
        fun example(category: Category): String {
            return when (category) {
                Category.TYPES -> "SurfaceGeometry"
                Category.INTERFACES -> "ITransformer"
                Category.ENUMS -> "Fruit"
                Category.ENUM_MEMBERS -> "Orange"
                Category.NAMESPACES -> "Slang"
                Category.FUNCTIONS -> "computeLighting"
                Category.METHODS -> "transform"
                Category.FIELDS -> "diffuseColor"
                Category.GLOBALS -> "gViewParams"
                Category.CONSTANTS -> "kSampleCount"
                Category.GENERIC_PARAMETERS -> "T"
                Category.PARAMETERS -> "inputValue"
                Category.LOCALS -> "worldPosition"
                Category.ALL -> "sampleName"
            }
        }

        fun label(category: Category): String {
            return when (category) {
                Category.TYPES -> SlangBundle.message("naming.category.types")
                Category.INTERFACES -> SlangBundle.message("naming.category.interfaces")
                Category.ENUMS -> SlangBundle.message("naming.category.enums")
                Category.ENUM_MEMBERS -> SlangBundle.message("naming.category.enum_members")
                Category.NAMESPACES -> SlangBundle.message("naming.category.namespaces")
                Category.FUNCTIONS -> SlangBundle.message("naming.category.functions")
                Category.METHODS -> SlangBundle.message("naming.category.methods")
                Category.FIELDS -> SlangBundle.message("naming.category.fields")
                Category.GLOBALS -> SlangBundle.message("naming.category.globals")
                Category.CONSTANTS -> SlangBundle.message("naming.category.constants")
                Category.GENERIC_PARAMETERS -> SlangBundle.message("naming.category.generic_parameters")
                Category.PARAMETERS -> SlangBundle.message("naming.category.parameters")
                Category.LOCALS -> SlangBundle.message("naming.category.locals")
                Category.ALL -> SlangBundle.message("naming.category.all")
            }
        }

        fun label(style: Style): String {
            return when (style) {
                Style.SNAKE_CASE -> SlangBundle.message("naming.style.snake_case")
                Style.LOWER_CAMEL_CASE -> SlangBundle.message("naming.style.lower_camel_case")
                Style.UPPER_CAMEL_CASE -> SlangBundle.message("naming.style.upper_camel_case")
                Style.SCREAMING_SNAKE_CASE -> SlangBundle.message("naming.style.screaming_snake_case")
                Style.ANY -> SlangBundle.message("naming.style.any")
            }
        }
    }
}
