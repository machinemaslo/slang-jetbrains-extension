package slanglsp.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.LanguageServersRegistry
import com.redhat.devtools.lsp4ij.ServerStatus
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import slanglsp.server.BatchConnection
import slanglsp.settings.project.ProjectSettings

internal object NativeServerSupport {
    fun isolatedFixture(name: String): CodeInsightTestFixture {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        // A fresh descriptor prevents reuse of the light project and its stopped LSP4IJ wrappers.
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

    fun enable(project: Project?) {
        // Light fixtures share LSP4IJ state. An earlier editor-only test may have
        // disabled this definition when no native executable was configured.
        val definition =
            LanguageServersRegistry.getInstance().getServerDefinition("slanglsp.SlangLanguageServer")
                ?: throw AssertionError("Slang language server definition is not registered")
        definition.setEnabled(true, project)
    }

    fun disable(project: Project?) {
        val definition =
            LanguageServersRegistry.getInstance().getServerDefinition("slanglsp.SlangLanguageServer")
                ?: throw AssertionError("Slang language server definition is not registered")
        definition.setEnabled(false, project)
    }

    /** Wait off the EDT for LSP4IJ to finish stopping the server before the fixture is reused. */
    fun stop(project: Project) {
        val manager = LanguageServerManager.getInstance(project)
        manager.stop(BatchConnection.SERVER_ID, LanguageServerManager.StopOptions().setWillDisable(true))
        val waitingSince = TimeSource.Monotonic.markNow()
        while (true) {
            val status = manager.getServerStatus(BatchConnection.SERVER_ID)
            if (status == ServerStatus.none || status == ServerStatus.stopped) {
                return
            }
            if (waitingSince.elapsedNow() >= 30.seconds) {
                throw AssertionError("Slang server did not stop in ${project.name} (status: $status)")
            }
            Thread.sleep(25)
        }
    }
}
