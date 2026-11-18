package slanglsp.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import slanglsp.SlangBundle.message
import slanglsp.language.SlangLanguage
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import kotlin.reflect.KMutableProperty1

internal sealed class FormattingOption(
    val label: String,
    val sample: String,
    val group: String,
    val description: String? = null,
) {
    abstract fun bind(): Binding

    interface Binding {
        val definition: FormattingOption
        val followsParent: Boolean
        val control: JComponent

        fun renderValue(): JComponent
        fun commitEditor()
        fun reset(settings: CodeStyleSettings)
        fun refreshInherited(settings: CodeStyleSettings)
        fun apply(settings: CodeStyleSettings)
        fun isModified(settings: CodeStyleSettings): Boolean
        fun onChanged(accept: () -> Boolean, action: () -> Unit)
    }
}

private class Setting<T>(val read: (CodeStyleSettings) -> T, val write: (CodeStyleSettings, T) -> Unit)

private fun <S, T> KMutableProperty1<S, T>.inSettings(owner: (CodeStyleSettings) -> S) =
    Setting({ get(owner(it)) }, { settings, value -> set(owner(settings), value) })

private class OptionEditor<T>(
    val component: JComponent,
    val read: () -> T,
    val write: (T) -> Unit,
    val listen: (() -> Unit) -> Unit,
    val commit: () -> Unit = {},
)

private class InheritedValue<T>(val stored: T, val resolve: (CodeStyleSettings) -> T)

private class ValueOption<T>(
    label: String,
    sample: String,
    group: String,
    description: String?,
    private val setting: Setting<T>,
    private val editor: () -> OptionEditor<T>,
    private val inheritance: InheritedValue<T>? = null,
) : FormattingOption(label, sample, group, description) {
    override fun bind(): Binding = object : Binding {
        override val definition = this@ValueOption
        private val input = editor()
        private val display by lazy(editor)
        private var inherited: InheritedValue<T>? = null

        override val control get() = input.component
        override val followsParent get() = inherited != null

        override fun renderValue(): JComponent {
            display.write(input.read())
            return display.component
        }

        override fun commitEditor() = input.commit()

        private fun value(): T = inherited?.stored ?: input.read()

        override fun reset(settings: CodeStyleSettings) {
            val stored = setting.read(settings)
            inherited = inheritance?.takeIf { it.stored == stored }
            input.write(inherited?.resolve?.invoke(settings) ?: stored)
        }

        override fun refreshInherited(settings: CodeStyleSettings) {
            inherited?.let { input.write(it.resolve(settings)) }
        }

        override fun apply(settings: CodeStyleSettings) = setting.write(settings, value())

        override fun isModified(settings: CodeStyleSettings) = setting.read(settings) != value()

        override fun onChanged(accept: () -> Boolean, action: () -> Unit) {
            input.listen {
                if (accept()) {
                    inherited = null
                    action()
                }
            }
        }

        override fun toString() = label
    }
}

private fun checkboxEditor(): OptionEditor<Boolean> {
    val checkbox = JCheckBox()
    return OptionEditor(
        checkbox,
        read = { checkbox.isSelected },
        write = { checkbox.isSelected = it },
        listen = { onChange -> checkbox.addActionListener { onChange() } },
    )
}

private fun spinnerEditor(range: IntRange): OptionEditor<Int> {
    val model = SpinnerNumberModel(range.first, range.first, range.last, 1)
    val spinner = JSpinner(model)
    return OptionEditor(
        spinner,
        read = { model.number.toInt() },
        write = { model.value = it },
        listen = { onChange -> spinner.addChangeListener { onChange() } },
        commit = spinner::commitEdit,
    )
}

private fun <T> choiceEditor(choices: List<Pair<String, T>>): OptionEditor<T> {
    val combo = ComboBox(choices.map { it.first }.toTypedArray())
    return OptionEditor(
        combo,
        read = { choices[combo.selectedIndex].second },
        write = { value -> combo.selectedIndex = choices.indexOfFirst { it.second == value }.coerceAtLeast(0) },
        listen = { onChange -> combo.addActionListener { onChange() } },
    )
}

private fun checkbox(
    setting: Setting<Boolean>,
    title: String,
    sample: String,
    group: String,
    help: String? = null,
): FormattingOption = ValueOption(title, sample, group, help, setting, ::checkboxEditor)

private fun number(
    setting: Setting<Int>,
    title: String,
    sample: String,
    group: String,
    range: IntRange = 0..100,
): FormattingOption = ValueOption(title, sample, group, null, setting, { spinnerEditor(range) })

private fun <T> choice(
    setting: Setting<T>,
    title: String,
    sample: String,
    group: String,
    choices: List<Pair<String, T>>,
    inherited: InheritedValue<T>? = null,
): FormattingOption = ValueOption(title, sample, group, null, setting, { choiceEditor(choices) }, inherited)

private fun commonSettings(settings: CodeStyleSettings) = settings.getCommonSettings(SlangLanguage.INSTANCE)
private fun indentSettings(settings: CodeStyleSettings) =
    commonSettings(settings).run { indentOptions ?: initIndentOptions() }
private fun slangSettings(settings: CodeStyleSettings) = settings.getCustomSettings(SlangCodeStyleSettings::class.java)

private fun <T> KMutableProperty1<CommonCodeStyleSettings, T>.common() = inSettings(::commonSettings)
private fun <T> KMutableProperty1<CommonCodeStyleSettings.IndentOptions, T>.indent() = inSettings(::indentSettings)
private fun <T> KMutableProperty1<SlangCodeStyleSettings, T>.slang() = inSettings(::slangSettings)

private fun group(key: String): String = message("settings.code.style.group.$key")

private typealias Common = CommonCodeStyleSettings
private typealias Indent = CommonCodeStyleSettings.IndentOptions
private typealias SlangStyle = SlangCodeStyleSettings

internal object FormattingOptions {
    private enum class BraceParent {
        DEFAULT, TYPE, CONTROL;

        fun resolve(settings: SlangCodeStyleSettings): Int {
            val style = when (this) {
                DEFAULT -> 0
                TYPE -> settings.TYPE_BRACES
                CONTROL -> settings.CONTROL_BRACES
            }
            return if (style != 0) style else if (settings.BRACES_ON_NEW_LINE) 2 else 1
        }
    }

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

    fun indents(settings: CodeStyleSettings) = OptionPreviewPanel(
        settings, "indents", message("settings.code.style.indents.tab"), listOf(
            checkbox(
                Indent::USE_TAB_CHARACTER.indent(), message("settings.code.style.use.tabs"), INDENT,
                group("indents"), message("settings.code.style.describe.use.tabs"),
            ),
            number(Indent::INDENT_SIZE.indent(), message("settings.code.style.indent.size"), INDENT, group("indents"), 0..32),
            number(
                Indent::CONTINUATION_INDENT_SIZE.indent(), message("settings.code.style.continuation.indent"),
                INDENT, group("indents"), 0..32,
            ),
            number(Indent::TAB_SIZE.indent(), message("settings.code.style.tab.width"), INDENT, group("indents"), 1..32),
        ),
    )

    fun braces(settings: CodeStyleSettings): OptionPreviewPanel {
        val options = listOf(
            choice(
                SlangStyle::BRACES_ON_NEW_LINE.slang(), message("settings.code.style.braces.new.line"), FUNCTION,
                group("braces"), listOf("Allman" to true, "K&R" to false),
            ),
            brace(
                SlangStyle::NAMESPACE_BRACES, "namespace_braces", "namespace Lighting\n{\nfloat exposure;\n}", BraceParent.TYPE,
            ),
            brace(SlangStyle::TYPE_BRACES, "type_braces", "interface IShade\n{\nfloat shade();\n}"),
            brace(
                SlangStyle::STRUCT_BRACES, "struct_braces", "struct Material\n{\nfloat roughness;\n};", BraceParent.TYPE,
            ),
            brace(SlangStyle::CLASS_BRACES, "class_braces", "class Scene\n{\nfloat exposure;\n}", BraceParent.TYPE),
            brace(SlangStyle::ENUM_BRACES, "enum_braces", "enum Mode\n{\nFast,\nAccurate\n}", BraceParent.TYPE),
            brace(SlangStyle::BUFFER_BRACES, "buffer_braces", "cbuffer Scene\n{\nfloat exposure;\n}", BraceParent.TYPE),
            brace(SlangStyle::FUNCTION_BRACES, "function_braces", FUNCTION),
            brace(SlangStyle::SWITCH_BRACES, "switch_braces", SWITCH, BraceParent.CONTROL),
            brace(
                SlangStyle::CASE_BRACES, "case_braces",
                "void shade(int mode)\n{\nswitch (mode)\n{\ncase 0:\n{\nupdate();\nbreak;\n}\n}\n}", BraceParent.CONTROL,
            ),
            brace(SlangStyle::INITIALIZER_BRACES, "initializer_braces", "float weights[2] =\n{\n0.5, 1.0\n};", BraceParent.CONTROL),
            brace(SlangStyle::CONTROL_BRACES, "control_braces", CONDITIONAL),
            checkbox(
                Common::ELSE_ON_NEW_LINE.common(), message("settings.code.style.else"), CONDITIONAL,
                group("keywords"), message("settings.code.style.describe.else"),
            ),
            checkbox(
                Common::CATCH_ON_NEW_LINE.common(), message("settings.code.style.catch"),
                "void shade()\n{\ntry { update(); }\ncatch { clear(); }\n}", group("keywords"),
                message("settings.code.style.describe.catch"),
            ),
            checkbox(
                Common::WHILE_ON_NEW_LINE.common(), message("settings.code.style.while"),
                "void shade()\n{\ndo { update(); }\nwhile (active);\n}", group("keywords"),
                message("settings.code.style.describe.while"),
            ),
            checkbox(
                SlangStyle::KEEP_BLANK_LINES_AFTER_LBRACE.slang(), message("settings.code.style.keep.blank.lines.after.brace"),
                "float shade()\n{\n\n\nreturn 1.0;\n}", group("blank.keep"),
            ),
        )
        return OptionPreviewPanel(settings, "braces", message("settings.code.style.braces.tab"), options)
    }

    private fun brace(
        field: KMutableProperty1<SlangStyle, Int>,
        labelKey: String,
        sample: String,
        parent: BraceParent = BraceParent.DEFAULT,
    ) = choice(
        field.slang(), message("settings.code.style.$labelKey"), sample, group("braces"), braceStyles,
        inherited = InheritedValue(0) { parent.resolve(slangSettings(it)) },
    )

    fun blankLines(settings: CodeStyleSettings) = OptionPreviewPanel(
        settings, "blankLines", message("settings.code.style.blank.lines.tab"), listOf(
            number(
                Common::KEEP_BLANK_LINES_IN_CODE.common(), message("settings.code.style.blank.lines.code"),
                "void shade()\n{\nupdate();\n\n\n\nclear();\n}", group("blank.keep"),
            ),
            number(
                Common::KEEP_BLANK_LINES_IN_DECLARATIONS.common(), message("settings.code.style.blank.lines.declarations"),
                "struct Material\n{\nfloat roughness;\n\n\n\nfloat exposure;\n};", group("blank.keep"),
            ),
            number(
                Common::KEEP_BLANK_LINES_BEFORE_RBRACE.common(), message("settings.code.style.blank.lines.before.brace"),
                "void shade()\n{\nupdate();\n\n\n\n}", group("blank.keep"),
            ),
            number(
                Common::BLANK_LINES_AROUND_METHOD.common(), message("settings.code.style.blank.lines.functions"),
                "float shadow() { return 0; }\nfloat shade() { return 1; }", group("blank.insert"),
            ),
        ),
    )

    fun wrapping(settings: CodeStyleSettings) = OptionPreviewPanel(
        settings, "wrapping", message("settings.code.style.wrapping.tab"), listOf(
            number(Common::RIGHT_MARGIN.common(), message("settings.code.style.right.margin"), WRAP_SAMPLE, group("wrapping"), 1..1000),
            checkbox(
                Common::KEEP_LINE_BREAKS.common(), message("settings.code.style.keep.line.breaks"), WRAP_SAMPLE,
                group("wrapping"), message("settings.code.style.describe.keep.line.breaks"),
            ),
            wrap(Common::CALL_PARAMETERS_WRAP, message("settings.code.style.argument.lists")),
            wrap(Common::ASSIGNMENT_WRAP, message("settings.code.style.assignment.wrap")),
        ),
    )

    private val WRAP_SAMPLE = "$CALL\n\n${MULTILINE_CALL.replace("shade()", "shadow()")}\n\n" +
        "float intensity = firstSample + secondSample + thirdSample + exposure * gain;"

    private fun wrap(field: KMutableProperty1<Common, Int>, title: String) = choice(
        field.common(), title, WRAP_SAMPLE, group("wrapping"), listOf(
            message("settings.code.style.wrap.never") to Common.DO_NOT_WRAP,
            message("settings.code.style.wrap.needed") to Common.WRAP_AS_NEEDED,
            message("settings.code.style.wrap.always") to Common.WRAP_ALWAYS,
            message("settings.code.style.wrap.chop") to Common.WRAP_ON_EVERY_ITEM,
        ),
    )

    fun alignment(settings: CodeStyleSettings) = OptionPreviewPanel(
        settings, "alignment", message("settings.code.style.alignment.tab"), listOf(
            checkbox(
                Common::ALIGN_MULTILINE_PARAMETERS_IN_CALLS.common(), message("settings.code.style.align.arguments"),
                MULTILINE_CALL, group("alignment"), message("settings.code.style.describe.align.arguments"),
            ),
            checkbox(
                Common::INDENT_CASE_FROM_SWITCH.common(), message("settings.code.style.indent.case"),
                SWITCH, group("alignment"), message("settings.code.style.describe.indent.case"),
            ),
            checkbox(
                Common::INDENT_BREAK_FROM_CASE.common(), message("settings.code.style.indent.break"),
                SWITCH, group("alignment"), message("settings.code.style.describe.indent.break"),
            ),
            choice(
                SlangStyle::NAMESPACE_INDENTATION.slang(), message("settings.code.style.namespace.indent"),
                NAMESPACE, group("alignment"), listOf(
                    message("settings.code.style.namespace.indent.all") to 0,
                    message("settings.code.style.namespace.indent.none") to 1,
                    message("settings.code.style.namespace.indent.inner") to 2,
                ),
            ),
        ),
    )

    fun spaces(settings: CodeStyleSettings) = OptionPreviewPanel(
        settings, "spaces", message("settings.code.style.spaces.tab"), listOf(
            spacing(Common::SPACE_BEFORE_IF_PARENTHESES, "spaces.if", "void shade() { if(active) update(); }", "before.parentheses"),
            spacing(
                Common::SPACE_BEFORE_FOR_PARENTHESES, "spaces.for",
                "void shade() { for(int i = 0; i < 4; i++) update(); }", "before.parentheses",
            ),
            spacing(
                Common::SPACE_BEFORE_WHILE_PARENTHESES, "spaces.while",
                "void shade() { while(active) update(); }", "before.parentheses",
            ),
            spacing(Common::SPACE_BEFORE_SWITCH_PARENTHESES, "spaces.switch", SWITCH, "before.parentheses"),
            spacing(
                Common::SPACE_BEFORE_CATCH_PARENTHESES, "spaces.catch",
                "void shade() { try { update(); } catch(Error e) { clear(); } }", "before.parentheses",
            ),
            spacing(
                Common::SPACE_BEFORE_METHOD_CALL_PARENTHESES, "function.parentheses",
                "float shade() { return evaluate(1.0); }", "before.parentheses",
            ),
            spacing(
                Common::SPACE_WITHIN_PARENTHESES, "spaces.parentheses", "float shade() { return (exposure); }", "within",
            ),
            spacing(Common::SPACE_WITHIN_BRACKETS, "spaces.brackets", "float shade() { return weights[0]; }", "within"),
            spacing(Common::SPACE_BEFORE_COMMA, "spaces.before.comma", COMMAS, "punctuation"),
            spacing(Common::SPACE_AFTER_COMMA, "spaces.after.comma", COMMAS, "punctuation"),
            spacing(
                Common::SPACE_AFTER_SEMICOLON, "spaces.after.semicolon",
                "void shade() { for(int i=0;i<4;i++) update(); }", "punctuation",
            ),
            spacing(
                Common::SPACE_AROUND_ASSIGNMENT_OPERATORS, "spaces.assignment", "void shade() { exposure=1.0; }", "operators",
            ),
            spacing(
                Common::SPACE_AROUND_LOGICAL_OPERATORS, "spaces.logical", "bool shade() { return active&&visible; }", "operators",
            ),
            spacing(Common::SPACE_AROUND_EQUALITY_OPERATORS, "spaces.equality", "bool shade() { return mode==0; }", "operators"),
            spacing(
                Common::SPACE_AROUND_ADDITIVE_OPERATORS, "spaces.additive", "float shade() { return exposure+gain; }", "operators",
            ),
            spacing(Common::SPACE_AROUND_SHIFT_OPERATORS, "spaces.shift", "uint shade() { return flags<<2; }", "operators"),
            checkbox(
                SlangStyle::SPACE_WITHIN_GENERIC_ANGLES.slang(), message("settings.code.style.space.within.angles"),
                "StructuredBuffer<float> weights;", group("within"), message("settings.code.style.describe.space.within.angles"),
            ),
            checkbox(
                SlangStyle::SPACE_AFTER_LOGICAL_NOT.slang(), message("settings.code.style.space.after.not"),
                "bool shade() { return !active; }", group("operators"), message("settings.code.style.describe.space.after.not"),
            ),
        ),
    )

    private fun spacing(field: KMutableProperty1<Common, Boolean>, key: String, sample: String, group: String): FormattingOption {
        val title = message("settings.code.style.$key")
        return checkbox(field.common(), title, sample, group(group), message("settings.code.style.describe.spacing", title))
    }
}
