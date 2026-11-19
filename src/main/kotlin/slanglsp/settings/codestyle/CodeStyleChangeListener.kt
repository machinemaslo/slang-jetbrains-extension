package slanglsp.settings.codestyle

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.psi.codeStyle.CodeStyleSettingsChangeEvent
import com.intellij.psi.codeStyle.CodeStyleSettingsListener
import slanglsp.server.client.ServerClient

class CodeStyleChangeListener : CodeStyleSettingsListener {
    override fun codeStyleSettingsChanged(event: CodeStyleSettingsChangeEvent) {
        ServerClient.notifySettingsChanged(event.project)
        val project = event.project
        if (!project.isDisposed) {
            DaemonCodeAnalyzer.getInstance(project).settingsChanged()
        }
    }
}
