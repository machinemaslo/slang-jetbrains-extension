package slanglsp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;

/** Short blocking PSI reads using the public API shared by all supported platform versions. */
final class SlangReadAction {
    private SlangReadAction() { }

    static <T> T compute(Computable<T> action) {
        return ApplicationManager.getApplication().runReadAction(action);
    }

    static void run(Runnable action) {
        ApplicationManager.getApplication().runReadAction(action);
    }
}
