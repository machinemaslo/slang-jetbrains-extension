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
            var analyzer = com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project);
            var psiManager = com.intellij.psi.PsiManager.getInstance(project);
            for (var virtualFile : com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getOpenFiles()) {
                var file = psiManager.findFile(virtualFile);
                if (file != null && file.getLanguage().isKindOf(slanglsp.language.SlangLanguage.INSTANCE)) {
                    analyzer.restart(file);
                }
            }
        }
    }
}
