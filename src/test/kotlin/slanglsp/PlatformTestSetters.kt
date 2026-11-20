package slanglsp

import com.intellij.openapi.editor.Document
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import java.awt.datatransfer.StringSelection

// These Java getter/setter pairs have different types or signatures, so Kotlin exposes read-only properties.
internal fun Document.replaceAllText(value: CharSequence) {
    setText(value)
}

@Suppress("UsePropertyAccessSyntax")
internal fun CodeStyleSettingsManager.useTemporarySettings(settings: CodeStyleSettings) {
    setTemporarySettings(settings)
}

@Suppress("UsePropertyAccessSyntax")
internal fun CopyPasteManager.copyText(text: String) {
    setContents(StringSelection(text))
}
