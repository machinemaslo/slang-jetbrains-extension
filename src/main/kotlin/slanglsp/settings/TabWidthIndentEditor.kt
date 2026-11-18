package slanglsp.settings

import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.openapi.application.ApplicationBundle
import slanglsp.SlangBundle.message
import java.awt.Container
import javax.swing.JLabel

internal class TabWidthIndentEditor : SmartIndentOptionsEditor() {
    override fun createPanel() = super.createPanel().also(::renameTabLabel)

    private fun renameTabLabel(container: Container) {
        val original = ApplicationBundle.message("editbox.indent.tab.size")
        for (component in container.components) {
            if (component is JLabel && component.text == original) {
                component.text = message("settings.code.style.tab.width")
            }
            if (component is Container) {
                renameTabLabel(component)
            }
        }
    }
}
