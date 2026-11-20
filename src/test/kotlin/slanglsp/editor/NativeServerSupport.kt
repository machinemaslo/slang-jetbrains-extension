package slanglsp.editor

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.stopClients
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import slanglsp.analysis.DocumentSymbolRequests
import slanglsp.server.nativeclient.NativeVerification
import slanglsp.server.nativeclient.SlangLspProvider
import slanglsp.settings.project.ProjectSettings

internal object NativeServerSupport {
    fun isolatedFixture(name: String): CodeInsightTestFixture {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        // A fresh descriptor keeps the project's editors, clients and settings isolated.
        return factory.createCodeInsightFixture(
            factory.createLightFixtureBuilder(LightProjectDescriptor(), name).getFixture(), TempDirTestFixtureImpl())
    }

    /** On the EDT, register the root and prepare any initial files before enabling slangd. */
    fun configure(
        fixture: CodeInsightTestFixture,
        executable: String,
        prepareFilesAndGetIncludePath: () -> String = { fixture.tempDirPath }
    ) {
        ModuleRootModificationUtil.addContentRoot(fixture.module, fixture.tempDirPath)
        val project = fixture.project
        val settings = ProjectSettings.getInstance(project)
        val state = settings.getState()
        state.explicitSlangdLocation = Path.of(executable).parent.toString()
        state.useLocalVcpkgSlangd = false
        state.additionalIncludePaths = mutableListOf(prepareFilesAndGetIncludePath())
        settings.loadState(state)
        enable(project)
    }

    fun enable(project: Project) {
        TrustedProjects.setProjectTrusted(project, true)
    }

    fun disable(project: Project) {
        TrustedProjects.setProjectTrusted(project, false)
        stop(project)
    }

    fun stop(project: Project) {
        runInEdtAndWait {
            project.service<NativeVerification>().reset()
            LspClientManager.getInstance(project).stopClients<SlangLspProvider>()
        }
    }

    fun <T : Any> awaitResult(request: () -> T?): T? {
        val started = TimeSource.Monotonic.markNow()
        do {
            request()?.let { return it }
            Thread.sleep(50)
        } while (started.elapsedNow() < 10.seconds)

        return null
    }

    fun awaitLiveSymbols(file: PsiFile) {
        checkNotNull(awaitResult {
            runReadActionBlocking {
                val document = checkNotNull(PsiDocumentManager.getInstance(file.project).getDocument(file))
                DocumentSymbolRequests.get(file, document, document.modificationStamp, batch = false)
            }.get(11, TimeUnit.SECONDS)?.takeIf { it.isNotEmpty() }
        }) { "slangd did not return live symbols for ${file.name}" }
    }
}
