package slanglsp;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.awt.Toolkit;

public final class DesktopTestSession implements LauncherSessionListener {
    @Override
    public void launcherSessionOpened(LauncherSession session) {
        // Initialize JBR's process-wide desktop theme watcher before fixtures snapshot live threads.
        Toolkit.getDefaultToolkit().getDesktopProperty("awt.os.theme.isDark");
    }
}
