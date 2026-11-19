package slanglsp

import java.awt.Toolkit
import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener

class DesktopTestSession : LauncherSessionListener {
    override fun launcherSessionOpened(session: LauncherSession?) {
        // Initialize JBR's process-wide desktop theme watcher before fixtures snapshot live threads.
        Toolkit.getDefaultToolkit().getDesktopProperty("awt.os.theme.isDark")
    }
}
