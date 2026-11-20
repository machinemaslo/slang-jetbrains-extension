package slanglsp.editor.rename

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandlerRegistry
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.usages.UsageViewManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import slanglsp.TestDependencies
import slanglsp.editor.NativeServerSupport
import slanglsp.editor.rename.RenamePlan.Companion.collect
import slanglsp.editor.rename.RenamePlan.Companion.validName
import slanglsp.replaceAllText

internal class RenameTest {
    @Test
    fun rejectsKeywordsTypesAndNonIdentifiers() {
        for (name in mutableListOf<String?>("", "struct", "float4", "true", "1value", "a.b", "a b", "a\nb")) {
            Assertions.assertFalse(validName(name), name)
        }
        for (name in mutableListOf<String?>("renamedValue", "_value2", "MyBuffer")) {
            Assertions.assertTrue(validName(name))
        }
    }

    @Test
    @Timeout(90)
    @Throws(Exception::class)
    fun verifiedRenameIncludesUnsavedImportsPreservesShadowingAndSupportsUndo() {
        val executable = TestDependencies.slangd()
        val fixture = NativeServerSupport.isolatedFixture("SlangRenameTest")
        runInEdtAndWait { fixture.setUp() }

        try {
            val main =
                """
                    public float sharedValue = 1;
                    float shadow() { float sharedValue = 2; return sharedValue; }
                    float useGlobal() { return sharedValue + sin(1.0); }
                    // sharedValue must remain in comments
                    
                    """
                    .trimIndent()
            val imported = "import main;\nfloat imported() { return sharedValue; }\n"
            val files = arrayOfNulls<PsiFile>(3)
            val docs = arrayOfNulls<Document>(3)
            runInEdtAndWait {
                NativeServerSupport.configure(fixture, executable) {
                    val mainFile = fixture.addFileToProject("main.slang", main)
                    files[0] = mainFile
                    files[1] = fixture.addFileToProject("usage.slang", "import main;\nfloat imported() { return 0; }\n")
                    files[2] =
                        fixture.addFileToProject(
                            "other.slang",
                            """
                            float unrelated = 0;
                            struct UnusedType { float unusedField; };
                            float unusedFunction(float unusedParameter) {
                                float unusedLocal = 0;
                                return 0;
                            }

                            """
                                .trimIndent())
                    mainFile.virtualFile.parent.path
                }

                // Configuring the fixture writes VFS content, so do it before the unsaved edit.
                fixture.configureFromExistingVirtualFile(files[1]!!.virtualFile)
                for (i in docs.indices) {
                    docs[i] = PsiDocumentManager.getInstance(fixture.project).getDocument(files[i]!!)
                }
                FileDocumentManager.getInstance().saveAllDocuments()
            }
            // Let the opened editor's server initialize before sending an unsaved change.
            Assertions.assertEquals(2, collect(files[0], main.indexOf("sharedValue"), "renamedValue")!!.usages().size)
            runInEdtAndWait {
                WriteCommandAction.runWriteCommandAction(fixture.project) {
                    docs[1]!!.replaceAllText(imported)
                    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
                }
                Assertions.assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(docs[1]!!))
            }
            // Invoking on either the declaration or an imported reference produces the same plan.
            val fromDeclaration: RenamePlan? = collect(files[0], main.indexOf("sharedValue"), "renamedValue")
            Assertions.assertEquals(3, fromDeclaration!!.usages().size)
            val plan: RenamePlan? = collect(files[1], imported.indexOf("sharedValue"), "renamedValue")
            Assertions.assertEquals(3, plan!!.usages().size)
            runInEdtAndWait {
                Assertions.assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(docs[1]!!))
                fixture.editor.caretModel.moveToOffset(imported.indexOf("sharedValue"))
                val context =
                    SimpleDataContext.builder()
                        .add<Project?>(CommonDataKeys.PROJECT, fixture.project)
                        .add<Editor?>(CommonDataKeys.EDITOR, fixture.editor)
                        .add<PsiFile?>(CommonDataKeys.PSI_FILE, files[1])
                        .build()
                Assertions.assertTrue(
                    RenameHandlerRegistry.getInstance().getRenameHandlers(context).any { it is VerifiedRenameHandler })
                Assertions.assertFalse(
                    plan.requiresPreview(), "Cross-file code references alone do not require preview")
                VerifiedRenameHandler.RenameUi.queueRename(fixture.project, "renamedValue", previewRequested = true) {
                    files[1]!!.findElementAt(imported.indexOf("sharedValue"))
                }
                val views = UsageViewManager.getInstance(fixture.project)
                PlatformTestUtil.waitWithEventsDispatching(
                    "Rename preview opens after verification", { views.selectedUsageView != null }, 30)
                val preview = checkNotNull(views.selectedUsageView)
                Assertions.assertEquals(3, preview.usagesCount)
                Assertions.assertEquals(imported, docs[1]!!.text)
                preview.close()

                val disposable = Disposer.newDisposable()
                try {
                    GeneratedSourcesFilter.EP_NAME.point.registerExtension(
                        object : GeneratedSourcesFilter() {
                            override fun isGeneratedSource(file: VirtualFile, project: Project): Boolean {
                                return file == files[1]!!.virtualFile
                            }
                        },
                        disposable)
                    Assertions.assertTrue(plan.requiresPreview(), "Generated references require review")
                    VerifiedRenameHandler.RenameUi.finishRename(fixture.project, plan, previewRequested = false)
                    val generatedPreview = UsageViewManager.getInstance(fixture.project).selectedUsageView
                    Assertions.assertNotNull(generatedPreview)
                    Assertions.assertEquals(main, docs[0]!!.text)
                    generatedPreview!!.close()
                } finally {
                    Disposer.dispose(disposable)
                }
            }
            runInEdtAndWait {
                VerifiedRenameHandler.RenameUi.finishRename(fixture.project, plan, false)
            }
            Assertions.assertEquals(
                main
                    .replace("public float sharedValue", "public float renamedValue")
                    .replace("return sharedValue +", "return renamedValue +"),
                docs[0]!!.text)
            Assertions.assertEquals(imported.replace("sharedValue", "renamedValue"), docs[1]!!.text)
            runInEdtAndWait {
                val undo = UndoManager.getInstance(fixture.project)
                Assertions.assertTrue(undo.isUndoAvailable(null))
                undo.undo(null)
                PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
            }
            Assertions.assertEquals(main, docs[0]!!.text)
            Assertions.assertEquals(imported, docs[1]!!.text)

            val stale: RenamePlan? = collect(files[1], imported.indexOf("sharedValue"), "renamedValue")
            runInEdtAndWait {
                WriteCommandAction.runWriteCommandAction(fixture.project) { docs[2]!!.insertString(0, "// edited\n") }
                Assertions.assertThrows<IllegalStateException?>(IllegalStateException::class.java) { stale!!.apply() }
            }
            Assertions.assertEquals(main, docs[0]!!.text, "A stale plan must not partially rename files")
            Assertions.assertEquals(imported, docs[1]!!.text)

            val readOnly: RenamePlan? = collect(files[1], imported.indexOf("sharedValue"), "renamedValue")
            runInEdtAndWait {
                docs[1]!!.setReadOnly(true)
                try {
                    Assertions.assertTrue(readOnly!!.requiresPreview())
                    VerifiedRenameHandler.RenameUi.finishRename(fixture.project, readOnly, previewRequested = false)
                    val preview = UsageViewManager.getInstance(fixture.project).selectedUsageView
                    Assertions.assertNotNull(preview)
                    preview!!.close()
                    Assertions.assertThrows<IllegalStateException?>(IllegalStateException::class.java) {
                        readOnly.apply()
                    }
                } finally {
                    docs[1]!!.setReadOnly(false)
                }
            }
            Assertions.assertEquals(main, docs[0]!!.text)
            Assertions.assertThrows<IllegalArgumentException?>(IllegalArgumentException::class.java) {
                collect(files[0], main.indexOf("sin("), "mySin")
            }
            val unused: RenamePlan? = collect(files[2], docs[2]!!.text.indexOf("unrelated"), "renamed")
            Assertions.assertEquals(
                1, unused!!.usages().size, "An unused declaration needs no back-reference to be renamed")
            runInEdtAndWait { unused.apply() }
            Assertions.assertTrue(docs[2]!!.text.contains("float renamed = 0;"))
            for (name in listOf("UnusedType", "unusedField", "unusedFunction", "unusedParameter", "unusedLocal")) {
                val unusedSymbol: RenamePlan? = collect(files[2], docs[2]!!.text.indexOf(name), name + "Renamed")
                Assertions.assertEquals(1, unusedSymbol!!.usages().size, name)
                runInEdtAndWait { unusedSymbol.apply() }
                Assertions.assertTrue(docs[2]!!.text.contains(name + "Renamed"))
            }
        } finally {
            try {
                NativeServerSupport.stop(fixture.project)
            } finally {
                runInEdtAndWait { fixture.tearDown() }
            }
        }
    }

    companion object {
        private fun collect(file: PsiFile?, offset: Int, newName: String): RenamePlan? {
            val element = runReadActionBlocking { file!!.findElementAt(offset) }
            val result = arrayOfNulls<RenamePlan>(1)
            ProgressManager.getInstance()
                .runProcess({ result[0] = collect(element, newName) }, EmptyProgressIndicator())

            return result[0]
        }
    }
}
