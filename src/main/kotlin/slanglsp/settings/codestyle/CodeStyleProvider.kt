package slanglsp.settings.codestyle

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import slanglsp.SlangBundle.message
import slanglsp.language.SlangLanguage
import slanglsp.settings.codestyle.ui.FormatterPanel
import slanglsp.settings.codestyle.ui.FormattingOptions
import slanglsp.settings.codestyle.ui.NamingPanel
import slanglsp.settings.codestyle.ui.TabWidthIndentEditor

class CodeStyleProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage() = SlangLanguage.INSTANCE

    override fun createCustomSettings(settings: CodeStyleSettings) = SlangCodeStyleSettings(settings)

    override fun customizeDefaults(common: CommonCodeStyleSettings, indent: CommonCodeStyleSettings.IndentOptions) {
        common.RIGHT_MARGIN = 100
        indent.INDENT_SIZE = 4
        indent.CONTINUATION_INDENT_SIZE = 4
        indent.TAB_SIZE = 4
        indent.USE_TAB_CHARACTER = false
        common.ELSE_ON_NEW_LINE = true
        common.CATCH_ON_NEW_LINE = true
        common.WHILE_ON_NEW_LINE = true
        common.CALL_PARAMETERS_WRAP = CommonCodeStyleSettings.WRAP_AS_NEEDED
        common.ASSIGNMENT_WRAP = CommonCodeStyleSettings.WRAP_AS_NEEDED
        common.KEEP_BLANK_LINES_IN_CODE = 2
        common.KEEP_BLANK_LINES_IN_DECLARATIONS = 2
        common.KEEP_BLANK_LINES_BEFORE_RBRACE = 2
        common.BLANK_LINES_AROUND_METHOD = 0
        common.INDENT_CASE_FROM_SWITCH = true
        common.INDENT_BREAK_FROM_CASE = true
    }

    override fun getIndentOptionsEditor(): IndentOptionsEditor = TabWidthIndentEditor()

    override fun getCodeSample(settingsType: SettingsType) = "float shade(float value) { return value * 2; }"

    override fun createConfigurable(base: CodeStyleSettings, model: CodeStyleSettings) =
        object : CodeStyleAbstractConfigurable(base, model, "Slang") {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel {
                val panel =
                    object : TabbedLanguageCodeStylePanel(language, currentSettings, settings) {
                        override fun initTabs(settings: CodeStyleSettings) {
                            addTab(FormatterPanel(settings))
                            addTab(FormattingOptions.indents(settings))
                            addTab(NamingPanel(settings))
                            addTab(FormattingOptions.braces(settings))
                            addTab(FormattingOptions.blankLines(settings))
                            addTab(FormattingOptions.wrapping(settings))
                            addTab(FormattingOptions.spaces(settings))
                            addTab(FormattingOptions.alignment(settings))
                        }
                    }
                panel.reset(settings)
                panel.changeTab(message("settings.general"))
                return panel
            }
        }
}
