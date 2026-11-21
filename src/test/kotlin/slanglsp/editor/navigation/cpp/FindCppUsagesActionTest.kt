package slanglsp.editor.navigation.cpp

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class FindCppUsagesActionTest {
    @Test
    fun actionIsRegisteredAndRecognizesStructDeclarationsNotReferences() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture = factory.createCodeInsightFixture(factory.createLightFixtureBuilder("ReflectionAction").fixture)
        runInEdtAndWait { fixture.setUp() }
        try {
            runInEdtAndWait {
                val source = "struct /* heading */ Material {}; Material useMaterial;"
                val file = fixture.addFileToProject("material.slang", source)
                assertEquals("Material", structNameAt(file, source.indexOf("Material")))
                assertEquals("Material", structNameAt(file, 1))
                assertNull(structNameAt(file, source.indexOf("Material", source.indexOf(';'))))
                val actions = ActionManager.getInstance()
                assertInstanceOf(FindCppUsagesAction::class.java, actions.getAction("Slang.FindCppUsages"))
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }
}
