package slanglsp.settings;

import com.intellij.psi.codeStyle.CodeStyleSettingsChangeEvent;
import com.intellij.psi.codeStyle.CodeStyleSettingsListener;
import org.jetbrains.annotations.NotNull;
import slanglsp.server.ServerClient;

public final class CodeStyleChangeListener implements CodeStyleSettingsListener {
    @Override
    public void codeStyleSettingsChanged(@NotNull CodeStyleSettingsChangeEvent event) {
        ServerClient.notifySettingsChanged(event.getProject());
        var project = event.getProject();
        if (!project.isDisposed()) {
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).settingsChanged();
        }
    }
}
