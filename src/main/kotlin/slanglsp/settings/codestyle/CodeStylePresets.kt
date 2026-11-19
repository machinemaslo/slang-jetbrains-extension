package slanglsp.settings.codestyle

import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.PredefinedCodeStyle
import slanglsp.language.SlangLanguage

object CodeStylePresets {
    private fun apply(settings: CodeStyleSettings) {
        val common = settings.getCommonSettings(SlangLanguage.INSTANCE)
        val indent = common.initIndentOptions()
        common.RIGHT_MARGIN = 100
        indent.INDENT_SIZE = 4
        indent.CONTINUATION_INDENT_SIZE = 4
        indent.TAB_SIZE = 4
        indent.USE_TAB_CHARACTER = false
        val custom = settings.getCustomSettings(SlangCodeStyleSettings::class.java)
        custom.BRACES_ON_NEW_LINE = true
        custom.resetBraceOverrides()
        common.WHILE_ON_NEW_LINE = true
        common.CATCH_ON_NEW_LINE = true
        common.ELSE_ON_NEW_LINE = true
        custom.setNamingRules(NamingRule.slang())
        custom.NAMING_ABBREVIATIONS = NamingRule.ABBREVIATIONS
    }

    class Slang : PredefinedCodeStyle("Slang", SlangLanguage.INSTANCE) {
        override fun apply(settings: CodeStyleSettings) {
            CodeStylePresets.apply(settings)
        }
    }
}
