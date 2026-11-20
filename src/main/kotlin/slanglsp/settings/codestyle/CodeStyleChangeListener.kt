package slanglsp.settings.codestyle

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.psi.codeStyle.CodeStyleSettingsChangeEvent
import com.intellij.psi.codeStyle.CodeStyleSettingsListener
import slanglsp.server.ServerSettings

class CodeStyleChangeListener : CodeStyleSettingsListener {
    override fun codeStyleSettingsChanged(event: CodeStyleSettingsChangeEvent) {
        val project = event.project

        // The IDE also fires this event when it finishes computing a file's effective code style.
        // That does not change the project settings sent to slangd.
        if (project.isDisposed || event.virtualFile != null) {
            return
        }

        ServerSettings.notifySettingsChanged(project)
        DaemonCodeAnalyzer.getInstance(project).settingsChanged()
    }
}
