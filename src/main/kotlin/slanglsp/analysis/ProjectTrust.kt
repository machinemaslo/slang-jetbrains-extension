package slanglsp.analysis

import com.intellij.openapi.project.Project
import java.lang.reflect.Method
import kotlin.String

/** JetBrains moved the trust check after 2025.1. Neither API spans the supported IDE range. */
internal object ProjectTrust {
    private val CHECK: Method? = findCheck()

    fun isTrusted(project: Project): Boolean {
        return try {
            CHECK?.invoke(null, project) == true
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun findCheck(): Method? {
        val current = find("com.intellij.ide.trustedProjects.TrustedProjects", "isProjectTrusted")
        return current ?: find("com.intellij.ide.impl.TrustedProjects", "isTrusted")
    }

    private fun find(owner: String, name: String): Method? {
        return try {
            Class.forName(owner).getMethod(name, Project::class.java)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
}
