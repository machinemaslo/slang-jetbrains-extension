package slanglsp.settings

import com.intellij.openapi.ui.ComboBox
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
    fieldName: String,
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
            choices.isNotEmpty() -> ComboBox(choices.map { it.first }.toTypedArray())
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
        message("settings.code.style.indents.tab"), listOf(
            indent("USE_TAB_CHARACTER", message("settings.code.style.use.tabs"),
                message("settings.code.style.describe.use.tabs")),
            indent("INDENT_SIZE", message("settings.code.style.indent.size")),
            indent("CONTINUATION_INDENT_SIZE", message("settings.code.style.continuation.indent")),
            FormattingOption("TAB_SIZE", message("settings.code.style.tab.width"), INDENT,
                OptionOwner.INDENT, range = 1..32, group = message("settings.code.style.group.indents")),
        ),
    )

    private fun indent(field: String, title: String, help: String? = null) =
        FormattingOption(field, title, INDENT, OptionOwner.INDENT, range = 0..32,
            group = message("settings.code.style.group.indents"), description = help)

    fun braces(settings: CodeStyleSettings): OptionPreviewPanel {
        val options = listOf(
            FormattingOption("BRACES_ON_NEW_LINE", message("settings.code.style.braces.new.line"), FUNCTION, OptionOwner.SLANG,
                listOf("Allman" to true, "K&R" to false), group = message("settings.code.style.group.braces")),
            brace("NAMESPACE", message("settings.code.style.namespace_braces"), "namespace Lighting\n{\nfloat exposure;\n}"),
            brace("TYPE", message("settings.code.style.type_braces"), "interface IShade\n{\nfloat shade();\n}"),
            brace("STRUCT", message("settings.code.style.struct_braces"), "struct Material\n{\nfloat roughness;\n};"),
            brace("CLASS", message("settings.code.style.class_braces"), "class Scene\n{\nfloat exposure;\n}"),
            brace("ENUM", message("settings.code.style.enum_braces"), "enum Mode\n{\nFast,\nAccurate\n}"),
            brace("BUFFER", message("settings.code.style.buffer_braces"), "cbuffer Scene\n{\nfloat exposure;\n}"),
            brace("FUNCTION", message("settings.code.style.function_braces"), FUNCTION),
            brace("SWITCH", message("settings.code.style.switch_braces"), SWITCH),
            brace("CASE", message("settings.code.style.case_braces"),
                "void shade(int mode)\n{\nswitch (mode)\n{\ncase 0:\n{\nupdate();\nbreak;\n}\n}\n}"),
            brace("INITIALIZER", message("settings.code.style.initializer_braces"), "float weights[2] =\n{\n0.5, 1.0\n};"),
            brace("CONTROL", message("settings.code.style.control_braces"), CONDITIONAL),
            common("ELSE_ON_NEW_LINE", message("settings.code.style.else"), CONDITIONAL,
                message("settings.code.style.describe.else")),
            common("CATCH_ON_NEW_LINE", message("settings.code.style.catch"),
                "void shade()\n{\ntry { update(); }\ncatch { clear(); }\n}", message("settings.code.style.describe.catch")),
            common("WHILE_ON_NEW_LINE", message("settings.code.style.while"),
                "void shade()\n{\ndo { update(); }\nwhile (active);\n}", message("settings.code.style.describe.while")),
            FormattingOption("KEEP_BLANK_LINES_AFTER_LBRACE", message("settings.code.style.keep.blank.lines.after.brace"),
                "float shade()\n{\n\n\nreturn 1.0;\n}", OptionOwner.SLANG, group = message("settings.code.style.group.blank.keep")),
        )
        return OptionPreviewPanel(settings, "braces", message("settings.code.style.braces.tab"), options)
    }

    private fun brace(field: String, title: String, sample: String) =
        FormattingOption("${field}_BRACES", title, sample, OptionOwner.SLANG, braceStyles,
            group = message("settings.code.style.group.braces"), inheritedValue = { settings ->
                val style = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
                val parent = when (field) {
                    "STRUCT", "CLASS", "ENUM", "NAMESPACE", "BUFFER" -> style.TYPE_BRACES
                    "SWITCH", "CASE", "INITIALIZER" -> style.CONTROL_BRACES
                    else -> 0
                }
                if (parent != 0) parent else if (style.BRACES_ON_NEW_LINE) 2 else 1
            })

    fun blankLines(settings: CodeStyleSettings) = OptionPreviewPanel(settings, "blankLines", message("settings.code.style.blank.lines.tab"), listOf(
        common("KEEP_BLANK_LINES_IN_CODE", message("settings.code.style.blank.lines.code"), "void shade()\n{\nupdate();\n\n\n\nclear();\n}"),
        common("KEEP_BLANK_LINES_IN_DECLARATIONS", message("settings.code.style.blank.lines.declarations"), "struct Material\n{\nfloat roughness;\n\n\n\nfloat exposure;\n};"),
        common("KEEP_BLANK_LINES_BEFORE_RBRACE", message("settings.code.style.blank.lines.before.brace"), "void shade()\n{\nupdate();\n\n\n\n}"),
        common("BLANK_LINES_AROUND_METHOD", message("settings.code.style.blank.lines.functions"), "float shadow() { return 0; }\nfloat shade() { return 1; }"),
    ))

    fun wrapping(settings: CodeStyleSettings) = OptionPreviewPanel(settings, "wrapping", message("settings.code.style.wrapping.tab"), listOf(
        FormattingOption("RIGHT_MARGIN", message("settings.code.style.right.margin"), WRAP_SAMPLE,
            range = 1..1000, group = message("settings.code.style.group.wrapping")),
        common("KEEP_LINE_BREAKS", message("settings.code.style.keep.line.breaks"), WRAP_SAMPLE,
            message("settings.code.style.describe.keep.line.breaks")),
        wrap("CALL_PARAMETERS_WRAP", message("settings.code.style.argument.lists")),
        wrap("ASSIGNMENT_WRAP", message("settings.code.style.assignment.wrap")),
    ))

    private val WRAP_SAMPLE = "$CALL\n\n${MULTILINE_CALL.replace("shade()", "shadow()")}\n\n" +
        "float intensity = firstSample + secondSample + thirdSample + exposure * gain;"

    private fun wrap(field: String, title: String) = FormattingOption(field, title, WRAP_SAMPLE,
        choices = listOf(message("settings.code.style.wrap.never") to CommonCodeStyleSettings.DO_NOT_WRAP,
            message("settings.code.style.wrap.needed") to CommonCodeStyleSettings.WRAP_AS_NEEDED,
            message("settings.code.style.wrap.always") to CommonCodeStyleSettings.WRAP_ALWAYS,
            message("settings.code.style.wrap.chop") to CommonCodeStyleSettings.WRAP_ON_EVERY_ITEM),
        group = message("settings.code.style.group.wrapping"),
    )

    fun alignment(settings: CodeStyleSettings) = OptionPreviewPanel(settings, "alignment", message("settings.code.style.alignment.tab"), listOf(
        common("ALIGN_MULTILINE_PARAMETERS_IN_CALLS", message("settings.code.style.align.arguments"), MULTILINE_CALL,
            message("settings.code.style.describe.align.arguments")),
        common("INDENT_CASE_FROM_SWITCH", message("settings.code.style.indent.case"), SWITCH,
            message("settings.code.style.describe.indent.case")),
        common("INDENT_BREAK_FROM_CASE", message("settings.code.style.indent.break"), SWITCH,
            message("settings.code.style.describe.indent.break")),
        FormattingOption("NAMESPACE_INDENTATION", message("settings.code.style.namespace.indent"), NAMESPACE, OptionOwner.SLANG,
            listOf(message("settings.code.style.namespace.indent.all") to 0,
                message("settings.code.style.namespace.indent.none") to 1, message("settings.code.style.namespace.indent.inner") to 2),
            group = message("settings.code.style.group.alignment")),
    ))

    fun spaces(settings: CodeStyleSettings) = OptionPreviewPanel(settings, "spaces", message("settings.code.style.spaces.tab"), listOf(
        common("SPACE_BEFORE_IF_PARENTHESES", message("settings.code.style.spaces.if"), "void shade() { if(active) update(); }"),
        common("SPACE_BEFORE_FOR_PARENTHESES", message("settings.code.style.spaces.for"), "void shade() { for(int i = 0; i < 4; i++) update(); }"),
        common("SPACE_BEFORE_WHILE_PARENTHESES", message("settings.code.style.spaces.while"), "void shade() { while(active) update(); }"),
        common("SPACE_BEFORE_SWITCH_PARENTHESES", message("settings.code.style.spaces.switch"), SWITCH),
        common("SPACE_BEFORE_CATCH_PARENTHESES", message("settings.code.style.spaces.catch"), "void shade() { try { update(); } catch(Error e) { clear(); } }"),
        common("SPACE_BEFORE_METHOD_CALL_PARENTHESES", message("settings.code.style.function.parentheses"), "float shade() { return evaluate(1.0); }"),
        common("SPACE_WITHIN_PARENTHESES", message("settings.code.style.spaces.parentheses"), "float shade() { return (exposure); }"),
        common("SPACE_WITHIN_BRACKETS", message("settings.code.style.spaces.brackets"), "float shade() { return weights[0]; }"),
        common("SPACE_BEFORE_COMMA", message("settings.code.style.spaces.before.comma"), COMMAS),
        common("SPACE_AFTER_COMMA", message("settings.code.style.spaces.after.comma"), COMMAS),
        common("SPACE_AFTER_SEMICOLON", message("settings.code.style.spaces.after.semicolon"), "void shade() { for(int i=0;i<4;i++) update(); }"),
        common("SPACE_AROUND_ASSIGNMENT_OPERATORS", message("settings.code.style.spaces.assignment"), "void shade() { exposure=1.0; }"),
        common("SPACE_AROUND_LOGICAL_OPERATORS", message("settings.code.style.spaces.logical"), "bool shade() { return active&&visible; }"),
        common("SPACE_AROUND_EQUALITY_OPERATORS", message("settings.code.style.spaces.equality"), "bool shade() { return mode==0; }"),
        common("SPACE_AROUND_ADDITIVE_OPERATORS", message("settings.code.style.spaces.additive"), "float shade() { return exposure+gain; }"),
        common("SPACE_AROUND_SHIFT_OPERATORS", message("settings.code.style.spaces.shift"), "uint shade() { return flags<<2; }"),
        FormattingOption("SPACE_WITHIN_GENERIC_ANGLES", message("settings.code.style.space.within.angles"),
            "StructuredBuffer<float> weights;", OptionOwner.SLANG, group = message("settings.code.style.group.within"),
            description = message("settings.code.style.describe.space.within.angles")),
        FormattingOption("SPACE_AFTER_LOGICAL_NOT", message("settings.code.style.space.after.not"),
            "bool shade() { return !active; }", OptionOwner.SLANG, group = message("settings.code.style.group.operators"),
            description = message("settings.code.style.describe.space.after.not")),
    ))

    private fun common(field: String, title: String, sample: String, help: String? = null): FormattingOption {
        val group = when {
            field.contains("_OPERATOR") -> message("settings.code.style.group.operators")
            field.startsWith("SPACE_BEFORE_") && field.endsWith("PARENTHESES") -> message("settings.code.style.group.before.parentheses")
            field.startsWith("SPACE_WITHIN") -> message("settings.code.style.group.within")
            field.startsWith("SPACE_") -> message("settings.code.style.group.punctuation")
            field.startsWith("KEEP_BLANK") -> message("settings.code.style.group.blank.keep")
            field == "BLANK_LINES_AROUND_METHOD" -> message("settings.code.style.group.blank.insert")
            field.endsWith("_ON_NEW_LINE") -> message("settings.code.style.group.keywords")
            field == "KEEP_LINE_BREAKS" -> message("settings.code.style.group.wrapping")
            else -> message("settings.code.style.group.alignment")
        }
        val description = help ?: if (field.startsWith("SPACE_")) {
            message("settings.code.style.describe.spacing", title)
        } else {
            null
        }
        return FormattingOption(field, title, sample, group = group, description = description)
    }
}
