package slanglsp.settings.project

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import slanglsp.SlangBundle.message

class SettingsConfigurable(private val project: Project) : SearchableConfigurable {
    private var gui: SettingsPanel? = null

    override fun getDisplayName() = message("settings.slang.display.name")

    override fun getId() = "slanglsp.SlangConfigurable"

    override fun createComponent() =
        (gui
                ?: SettingsPanel().also {
                    it.createUI(project)
                    gui = it
                })
            .rootPanel

    override fun isModified() = gui?.isModified == true

    override fun apply() {
        gui?.apply()
    }

    override fun reset() {
        gui?.reset()
    }

    override fun disposeUIResources() {
        gui = null
    }
}
