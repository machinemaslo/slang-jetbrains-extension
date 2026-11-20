package slanglsp.settings.codestyle

import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import slanglsp.server.client.ServerClient

internal class CodeStyleChangeListenerTest {
    private lateinit var fixture: IdeaProjectTestFixture

    @BeforeEach
    fun setUp() {
        fixture = IdeaTestFixtureFactory.getFixtureFactory()
            .createLightFixtureBuilder("CodeStyleChangeListener")
            .fixture

        runInEdtAndWait {
            fixture.setUp()
        }
    }

    @AfterEach
    fun tearDown() {
        runInEdtAndWait {
            fixture.tearDown()
        }
    }

    @Test
    fun onlyProjectWideEventsInvalidateServerAnalysis() {
        runInEdtAndWait {
            val project = fixture.project
            val settings = CodeStyleSettingsManager.getInstance(project)
            val initialGeneration = ServerClient.analysisGeneration(project)

            settings.fireCodeStyleSettingsChanged(LightVirtualFile("shader.slang"))
            assertEquals(initialGeneration, ServerClient.analysisGeneration(project))

            settings.fireCodeStyleSettingsChanged()
            assertEquals(initialGeneration + 1, ServerClient.analysisGeneration(project))
        }
    }
}
