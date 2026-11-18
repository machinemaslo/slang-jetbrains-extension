package slanglsp.editor;

import com.intellij.openapi.project.Project;

import java.lang.reflect.Method;

/** JetBrains moved the trust check after 2025.1. Neither API spans the supported IDE range. */
final class ProjectTrust {
    private static final Method CHECK = findCheck();

    private ProjectTrust() {
    }

    static boolean isTrusted(Project project) {
        try {
            return CHECK != null && Boolean.TRUE.equals(CHECK.invoke(null, project));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    private static Method findCheck() {
        var current = find("com.intellij.ide.trustedProjects.TrustedProjects", "isProjectTrusted");
        return current != null ? current : find("com.intellij.ide.impl.TrustedProjects", "isTrusted");
    }

    private static Method find(String owner, String name) {
        try {
            return Class.forName(owner).getMethod(name, Project.class);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }
}
