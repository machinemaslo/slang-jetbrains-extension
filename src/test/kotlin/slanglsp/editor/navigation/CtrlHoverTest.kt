package slanglsp.editor.navigation

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.testFramework.runInEdtAndWait
import kotlin.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import slanglsp.analysis.AnalysisDeadline
import slanglsp.editor.NativeServerSupport

internal class CtrlHoverTest {
    @Test
    fun providesTheHoverTargetWithoutWaitingForLanguageServerAnalysis() {
        val fixture = NativeServerSupport.isolatedFixture("SlangCtrlHover")
        val text = "float use() { return sin(1.0); } // not a link\n"
        runInEdtAndWait { fixture.setUp() }
        var editor: Editor? = null
        try {
            lateinit var file: PsiFile
            runInEdtAndWait {
                file = fixture.addFileToProject("hover.slang", text)
                // A detached editor lets us exercise Ctrl-hover without starting slangd.
                editor = EditorFactory.getInstance().createEditor(file.fileDocument, fixture.project)
            }
            val sourceEditor = checkNotNull(editor)
            val handler = SymbolDeclarationHandler()
            val offset = text.indexOf("sin")
            runReadActionBlocking {
                // A zero analysis budget makes synchronous LSP resolution fail deterministically.
                AnalysisDeadline.open(Duration.ZERO).use {
                    val source = checkNotNull(file.findElementAt(offset + 1))
                    val targets = handler.getGotoDeclarationTargets(source, offset + 1, sourceEditor)
                    val target = assertInstanceOf(DeferredNavigationTarget::class.java, targets?.singleOrNull())
                    assertTrue(target.canNavigate())
                    assertEquals(TextRange(offset, offset + 3), target.textRange)

                    val commentOffset = text.indexOf("not a link")
                    val comment = checkNotNull(file.findElementAt(commentOffset))
                    assertNull(handler.getGotoDeclarationTargets(comment, commentOffset, sourceEditor))
                }
            }
        } finally {
            runInEdtAndWait {
                try {
                    editor?.let { EditorFactory.getInstance().releaseEditor(it) }
                } finally {
                    fixture.tearDown()
                }
            }
        }
    }
}
