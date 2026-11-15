package slanglsp.settings;

import com.intellij.psi.codeStyle.CodeStyleSettingsChangeEvent;
import com.intellij.psi.codeStyle.CodeStyleSettingsListener;
import org.jetbrains.annotations.NotNull;
import slanglsp.server.ServerClient;

public final class SlangCodeStyleListener implements CodeStyleSettingsListener {
    @Override
    public void codeStyleSettingsChanged(@NotNull CodeStyleSettingsChangeEvent event) {
        ServerClient.notifySettingsChanged(event.getProject());
    }
}
