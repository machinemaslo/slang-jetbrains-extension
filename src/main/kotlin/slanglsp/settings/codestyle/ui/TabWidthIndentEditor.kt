package slanglsp.settings.codestyle.ui

import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.openapi.application.ApplicationBundle
import java.awt.Container
import javax.swing.JLabel
import javax.swing.JPanel
import slanglsp.SlangBundle.message

internal class TabWidthIndentEditor : SmartIndentOptionsEditor() {
    override fun createPanel(): JPanel {
        val panel: JPanel = super.createPanel()
        renameTabLabel(panel)
        return panel
    }

    private fun renameTabLabel(container: Container) {
        val original: String = ApplicationBundle.message("editbox.indent.tab.size")
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
