package slanglsp.analysis

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project

internal object ProjectTrust {
    fun isTrusted(project: Project): Boolean {
        return TrustedProjects.isProjectTrusted(project)
    }
}
