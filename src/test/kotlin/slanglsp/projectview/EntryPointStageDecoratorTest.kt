package slanglsp.projectview

import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.SimpleTextAttributes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class EntryPointStageDecoratorTest {
    @Test
    fun registeredDecoratorAddsABoldBracketedSuffixAndUpdatesAfterEditing() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture = factory.createCodeInsightFixture(factory.createLightFixtureBuilder("EntryPointView").fixture)
        runInEdtAndWait { fixture.setUp() }

        try {
            lateinit var file: PsiFile
            runInEdtAndWait {
                file = fixture.addFileToProject("lighting.slang", """[shader("compute")] void main() {}""")
            }
            val node = runReadActionBlocking { PsiFileNode(fixture.project, file, ViewSettings.DEFAULT) }

            fun labels(): List<Pair<String, SimpleTextAttributes>> = runReadActionBlocking {
                node.update()
                assertEquals("lighting.slang", node.presentation.presentableText)
                node.presentation.coloredText.map { it.text to it.attributes }
            }

            fun edit(text: String) = runInEdtAndWait {
                val manager = PsiDocumentManager.getInstance(fixture.project)
                val document = checkNotNull(manager.getDocument(file))
                WriteCommandAction.runWriteCommandAction(fixture.project) { document.setText(text) }
                manager.commitDocument(document)
            }

            assertEquals(
                listOf(
                    "lighting.slang" to SimpleTextAttributes.REGULAR_ATTRIBUTES,
                    " [compute]" to SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES,
                ),
                labels(),
            )
            edit("""[shader("vertex")] float4 main() : SV_Position { return 0; }""")
            assertEquals(" [vertex]" to SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, labels().last())
            edit("void main() {}")
            assertTrue(labels().isEmpty(), "Unannotated files should retain their normal presentation")
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }
}
