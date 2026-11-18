package slanglsp.settings

import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import slanglsp.SlangBundle.message
import slanglsp.language.SlangLanguage
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

internal class FormattingOption(
    val fieldName: String,
    val label: String,
    val sample: String,
    private val owner: OptionOwner = OptionOwner.COMMON,
    private val choices: List<Pair<String, Any>> = emptyList(),
    private val range: IntRange = 0..100,
    val group: String = message("settings.code.style.group.options"),
    val description: String? = null,
    private val inheritedValue: ((CodeStyleSettings) -> Any)? = null,
) {
    private val field = owner.type.getField(fieldName)

    fun bind(): Binding = Binding()

    inner class Binding {
        val definition get() = this@FormattingOption
        val followsParent get() = inherited
        private var inherited = false
        val control = createControl()
        private val display by lazy { createControl() }

        private fun createControl(): JComponent = when {
            choices.isNotEmpty() -> JComboBox(choices.map { it.first }.toTypedArray())
            field.type == Boolean::class.javaPrimitiveType -> JCheckBox()
            else -> JSpinner(SpinnerNumberModel(range.first, range.first, range.last, 1))
        }

        fun renderValue(): JComponent = display.apply {
            when (this) {
                is JCheckBox -> isSelected = (control as JCheckBox).isSelected
                is JComboBox<*> -> selectedIndex = (control as JComboBox<*>).selectedIndex
                is JSpinner -> value = (control as JSpinner).value
            }
        }

        fun commitEditor() {
            (control as? JSpinner)?.commitEdit()
        }

        private fun value(): Any = if (inherited) 0 else when (control) {
            is JCheckBox -> control.isSelected
            is JComboBox<*> -> choices[control.selectedIndex].second
            is JSpinner -> control.value
            else -> error("Unsupported formatting control")
        }

        fun reset(settings: CodeStyleSettings) {
            val value = field.get(owner.target(settings))
            inherited = value == 0 && inheritedValue != null
            when (control) {
                is JCheckBox -> control.isSelected = value as Boolean
                is JComboBox<*> -> selectValue(if (inherited) inheritedValue?.invoke(settings) else value)
                is JSpinner -> control.value = value
            }
        }

        fun refreshInherited(settings: CodeStyleSettings) {
            if (inherited) {
                selectValue(inheritedValue?.invoke(settings))
            }
        }

        private fun selectValue(value: Any?) {
            val combo = control as JComboBox<*>
            combo.selectedIndex = choices.indexOfFirst { it.second == value }.coerceAtLeast(0)
        }

        fun apply(settings: CodeStyleSettings) {
            field.set(owner.target(settings), value())
        }

        fun isModified(settings: CodeStyleSettings) = field.get(owner.target(settings)) != value()

        fun onChanged(accept: () -> Boolean, action: () -> Unit) {
            val change = {
                if (accept()) {
                    inherited = false
                    action()
                }
            }
            when (control) {
                is JCheckBox -> control.addActionListener { change() }
                is JComboBox<*> -> control.addActionListener { change() }
                is JSpinner -> control.addChangeListener { change() }
            }
        }

        override fun toString() = label
    }
}

internal enum class OptionOwner(val type: Class<*>) {
    COMMON(CommonCodeStyleSettings::class.java),
    INDENT(CommonCodeStyleSettings.IndentOptions::class.java),
    SLANG(SlangCodeStyleSettings::class.java);

    fun target(settings: CodeStyleSettings): Any = when (this) {
        COMMON -> settings.getCommonSettings(SlangLanguage.INSTANCE)
        INDENT -> settings.getCommonSettings(SlangLanguage.INSTANCE).initIndentOptions()
        SLANG -> settings.getCustomSettings(SlangCodeStyleSettings::class.java)
    }
}

internal object FormattingOptions {
    private const val FUNCTION = "float shade(float x)\n{\nreturn x;\n}"
    private const val CALL = "float shade()\n{\nreturn evaluate(firstSample, secondSample, thirdSample);\n}"
    private const val MULTILINE_CALL = "float shade()\n{\nreturn evaluate(firstSample,\nsecondSample,\nthirdSample);\n}"
    private const val SWITCH = "void shade(int mode)\n{\nswitch (mode)\n{\ncase 0:\nupdate();\nbreak;\n}\n}"
    private const val NAMESPACE = "namespace Outer\n{\nnamespace Inner\n{\nfloat exposure;\n}\n}"
    private const val COMMAS = "float shade() { return evaluate(red,green,blue); }"
    private const val CONDITIONAL = "void shade()\n{\nif (active) { update(); }\nelse { clear(); }\n}"
    private const val INDENT = "float shade()\n{\nif (active)\n{\nreturn evaluate(firstSample,\nsecondSample);\n}\nreturn 0;\n}"
    private val braceStyles = listOf(
        "K&R" to 1,
        "Allman" to 2,
        message("settings.code.style.braces.no.space") to 3,
    )

    fun indents(settings: CodeStyleSettings) = OptionPreviewPanel(settings, "indents",
        label("indents.tab"), listOf(
            indent("USE_TAB_CHARACTER", "use.tabs", "use.tabs"),
            indent("INDENT_SIZE", "indent.size"),
            indent("CONTINUATION_INDENT_SIZE", "continuation.indent"),
            FormattingOption("TAB_SIZE", message("settings.code.style.tab.width"), INDENT,
                OptionOwner.INDENT, range = 1..32, group = label("group.indents")),
        ),
    )

    private fun indent(field: String, key: String, help: String? = null) =
        FormattingOption(field, label(key), INDENT, OptionOwner.INDENT, range = 0..32,
            group = label("group.indents"), description = help?.let { label("describe.$it") })

    fun braces(settings: CodeStyleSettings): OptionPreviewPanel {
        val options = listOf(
            FormattingOption("BRACES_ON_NEW_LINE", label("braces.new.line"), FUNCTION, OptionOwner.SLANG,
                listOf("Allman" to true, "K&R" to false), group = label("group.braces")),
            brace("NAMESPACE", "namespace Lighting\n{\nfloat exposure;\n}"),
            brace("TYPE", "interface IShade\n{\nfloat shade();\n}"),
            brace("STRUCT", "struct Material\n{\nfloat roughness;\n};"),
            brace("CLASS", "class Scene\n{\nfloat exposure;\n}"),
            brace("ENUM", "enum Mode\n{\nFast,\nAccurate\n}"),
            brace("BUFFER", "cbuffer Scene\n{\nfloat exposure;\n}"),
            brace("FUNCTION", FUNCTION),
            brace("SWITCH", SWITCH),
            brace("CASE", "void shade(int mode)\n{\nswitch (mode)\n{\ncase 0:\n{\nupdate();\nbreak;\n}\n}\n}"),
            brace("INITIALIZER", "float weights[2] =\n{\n0.5, 1.0\n};"),
            brace("CONTROL", CONDITIONAL),
            common("ELSE_ON_NEW_LINE", "else", CONDITIONAL),
            common("CATCH_ON_NEW_LINE", "catch", "void shade()\n{\ntry { update(); }\ncatch { clear(); }\n}"),
            common("WHILE_ON_NEW_LINE", "while", "void shade()\n{\ndo { update(); }\nwhile (active);\n}"),
            FormattingOption("KEEP_BLANK_LINES_AFTER_LBRACE", label("keep.blank.lines.after.brace"),
                "float shade()\n{\n\n\nreturn 1.0;\n}", OptionOwner.SLANG, group = label("group.blank.keep")),
        )
        return OptionPreviewPanel(settings, "braces", label("braces.tab"), options)
    }

    private fun brace(field: String, sample: String) =
        FormattingOption("${field}_BRACES", label("${field.lowercase(java.util.Locale.ROOT)}_braces"),
            sample, OptionOwner.SLANG, braceStyles,
            group = label("group.braces"), inheritedValue = { settings ->
                val style = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
                val parent = when (field) {
                    "STRUCT", "CLASS", "ENUM", "NAMESPACE", "BUFFER" -> style.TYPE_BRACES
                    "SWITCH", "CASE", "INITIALIZER" -> style.CONTROL_BRACES
                    else -> 0
                }
                if (parent != 0) parent else if (style.BRACES_ON_NEW_LINE) 2 else 1
            })

    fun blankLines(settings: CodeStyleSettings) = OptionPreviewPanel(settings, "blankLines", label("blank.lines.tab"), listOf(
        common("KEEP_BLANK_LINES_IN_CODE", "blank.lines.code", "void shade()\n{\nupdate();\n\n\n\nclear();\n}"),
        common("KEEP_BLANK_LINES_IN_DECLARATIONS", "blank.lines.declarations", "struct Material\n{\nfloat roughness;\n\n\n\nfloat exposure;\n};"),
        common("KEEP_BLANK_LINES_BEFORE_RBRACE", "blank.lines.before.brace", "void shade()\n{\nupdate();\n\n\n\n}"),
        common("BLANK_LINES_AROUND_METHOD", "blank.lines.functions", "float shadow() { return 0; }\nfloat shade() { return 1; }"),
    ))

    fun wrapping(settings: CodeStyleSettings) = OptionPreviewPanel(settings, "wrapping", label("wrapping.tab"), listOf(
        FormattingOption("RIGHT_MARGIN", label("right.margin"), WRAP_SAMPLE, range = 1..1000, group = label("group.wrapping")),
        common("KEEP_LINE_BREAKS", "keep.line.breaks", WRAP_SAMPLE),
        wrap("CALL_PARAMETERS_WRAP", "argument.lists"),
        wrap("ASSIGNMENT_WRAP", "assignment.wrap"),
    ))

    private val WRAP_SAMPLE = "$CALL\n\n${MULTILINE_CALL.replace("shade()", "shadow()")}\n\n" +
        "float intensity = firstSample + secondSample + thirdSample + exposure * gain;"

    private fun wrap(field: String, key: String) = FormattingOption(field, label(key), WRAP_SAMPLE,
        choices = listOf(label("wrap.never") to CommonCodeStyleSettings.DO_NOT_WRAP,
            label("wrap.needed") to CommonCodeStyleSettings.WRAP_AS_NEEDED,
            label("wrap.always") to CommonCodeStyleSettings.WRAP_ALWAYS,
            label("wrap.chop") to CommonCodeStyleSettings.WRAP_ON_EVERY_ITEM),
        group = label("group.wrapping"),
    )

    fun alignment(settings: CodeStyleSettings) = OptionPreviewPanel(settings, "alignment", label("alignment.tab"), listOf(
        common("ALIGN_MULTILINE_PARAMETERS_IN_CALLS", "align.arguments", MULTILINE_CALL),
        common("INDENT_CASE_FROM_SWITCH", "indent.case", SWITCH),
        common("INDENT_BREAK_FROM_CASE", "indent.break", SWITCH),
        FormattingOption("NAMESPACE_INDENTATION", label("namespace.indent"), NAMESPACE, OptionOwner.SLANG,
            listOf(label("namespace.indent.all") to 0, label("namespace.indent.none") to 1, label("namespace.indent.inner") to 2),
            group = label("group.alignment")),
    ))

    fun spaces(settings: CodeStyleSettings) = OptionPreviewPanel(settings, "spaces", label("spaces.tab"), listOf(
        common("SPACE_BEFORE_IF_PARENTHESES", "spaces.if", "void shade() { if(active) update(); }"),
        common("SPACE_BEFORE_FOR_PARENTHESES", "spaces.for", "void shade() { for(int i = 0; i < 4; i++) update(); }"),
        common("SPACE_BEFORE_WHILE_PARENTHESES", "spaces.while", "void shade() { while(active) update(); }"),
        common("SPACE_BEFORE_SWITCH_PARENTHESES", "spaces.switch", SWITCH),
        common("SPACE_BEFORE_CATCH_PARENTHESES", "spaces.catch", "void shade() { try { update(); } catch(Error e) { clear(); } }"),
        common("SPACE_BEFORE_METHOD_CALL_PARENTHESES", "function.parentheses", "float shade() { return evaluate(1.0); }"),
        common("SPACE_WITHIN_PARENTHESES", "spaces.parentheses", "float shade() { return (exposure); }"),
        common("SPACE_WITHIN_BRACKETS", "spaces.brackets", "float shade() { return weights[0]; }"),
        common("SPACE_BEFORE_COMMA", "spaces.before.comma", COMMAS),
        common("SPACE_AFTER_COMMA", "spaces.after.comma", COMMAS),
        common("SPACE_AFTER_SEMICOLON", "spaces.after.semicolon", "void shade() { for(int i=0;i<4;i++) update(); }"),
        common("SPACE_AROUND_ASSIGNMENT_OPERATORS", "spaces.assignment", "void shade() { exposure=1.0; }"),
        common("SPACE_AROUND_LOGICAL_OPERATORS", "spaces.logical", "bool shade() { return active&&visible; }"),
        common("SPACE_AROUND_EQUALITY_OPERATORS", "spaces.equality", "bool shade() { return mode==0; }"),
        common("SPACE_AROUND_ADDITIVE_OPERATORS", "spaces.additive", "float shade() { return exposure+gain; }"),
        common("SPACE_AROUND_SHIFT_OPERATORS", "spaces.shift", "uint shade() { return flags<<2; }"),
        FormattingOption("SPACE_WITHIN_GENERIC_ANGLES", label("space.within.angles"),
            "StructuredBuffer<float> weights;", OptionOwner.SLANG, group = label("group.within"),
            description = label("describe.space.within.angles")),
        FormattingOption("SPACE_AFTER_LOGICAL_NOT", label("space.after.not"),
            "bool shade() { return !active; }", OptionOwner.SLANG, group = label("group.operators"),
            description = label("describe.space.after.not")),
    ))

    private fun common(field: String, key: String, sample: String): FormattingOption {
        val group = when {
            field.contains("_OPERATOR") -> "operators"
            field.startsWith("SPACE_BEFORE_") && field.endsWith("PARENTHESES") -> "before.parentheses"
            field.startsWith("SPACE_WITHIN") -> "within"
            field.startsWith("SPACE_") -> "punctuation"
            field.startsWith("KEEP_BLANK") -> "blank.keep"
            field == "BLANK_LINES_AROUND_METHOD" -> "blank.insert"
            field.endsWith("_ON_NEW_LINE") -> "keywords"
            field == "KEEP_LINE_BREAKS" -> "wrapping"
            else -> "alignment"
        }
        val description = when {
            field.startsWith("SPACE_") -> message("settings.code.style.describe.spacing", label(key))
            field.startsWith("KEEP_BLANK") || field == "BLANK_LINES_AROUND_METHOD" -> null
            else -> label("describe.$key")
        }
        return FormattingOption(field, label(key), sample, group = label("group.$group"), description = description)
    }

    private fun label(key: String) = message("settings.code.style.$key")
}
