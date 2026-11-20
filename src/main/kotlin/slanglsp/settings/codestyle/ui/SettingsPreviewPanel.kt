package slanglsp.settings.codestyle.ui

import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import slanglsp.SlangBundle.message
import slanglsp.highlighting.SlangSyntaxHighlighter
import slanglsp.language.SlangFileType
import slanglsp.language.SlangLanguage

internal abstract class SettingsPreviewPanel(
    settings: CodeStyleSettings,
    id: String,
    private val applyPreviewOptions: Boolean = false,
) : CodeStyleAbstractPanel(settings) {
    private val cards = CardLayout()
    private val preview = JPanel(cards)
    private val description = JBLabel().setAllowAutoWrapping(true)
    private var sample: String? = null
    private val splitter = OnePixelSplitter(true, "slang.codeStyle.preview.$id", 0.57f)

    protected fun installOptions(controls: JComponent, scroll: Boolean = true) {
        val code = JPanel()
        preview.add(JBLabel(message("settings.code.style.preview.hint"), SwingConstants.CENTER), "hint")
        installPreviewPanel(code)
        (editor as? EditorEx)?.isViewer = true
        description.border = com.intellij.util.ui.JBUI.Borders.empty(6, 10)
        preview.add(
            JPanel(BorderLayout()).apply {
                add(description, BorderLayout.NORTH)
                add(code, BorderLayout.CENTER)
            },
            "code")
        splitter.firstComponent = if (scroll) JBScrollPane(controls) else controls
        splitter.secondComponent = preview
        addPanelToWatch(controls)
    }

    protected fun watchPreview(component: Component, explanation: String? = null, example: () -> String) {
        component.addFocusListener(
            object : FocusAdapter() {
                override fun focusGained(event: FocusEvent) {
                    if (event.cause != FocusEvent.Cause.ACTIVATION && event.cause != FocusEvent.Cause.UNKNOWN) {
                        showExample(example(), explanation)
                    }
                }
            })
        component.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    showExample(example(), explanation)
                }
            })
    }

    protected fun showExample(text: String, explanation: String? = null) {
        description.text = explanation.orEmpty()
        description.isVisible = explanation != null
        if (sample == text) {
            return
        }
        sample = text
        cards.show(preview, "code")
        setEditorText(text, true)
    }

    final override fun applySettingsToModel() {
        // General previews must not write executable settings.
        if (applyPreviewOptions) {
            super.applySettingsToModel()
        }
    }

    protected fun clearExample() {
        sample = null
        cards.show(preview, "hint")
    }

    final override fun getPanel(): JComponent = splitter

    final override fun getDefaultLanguage() = SlangLanguage.INSTANCE

    final override fun getFileType() = SlangFileType

    final override fun getRightMargin() = settings.getRightMargin(defaultLanguage)

    final override fun getPreviewText() = sample ?: " "

    final override fun createHighlighter(scheme: EditorColorsScheme) =
        LexerEditorHighlighter(SlangSyntaxHighlighter, scheme)

    override fun updatePreview(useDefaultSample: Boolean) {
        if (sample != null) {
            super.updatePreview(useDefaultSample)
        }
    }
}
