package slanglsp.editor.inspections.unused

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndWait
import java.nio.file.Path
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import slanglsp.TestDependencies
import slanglsp.analysis.ReadActions.compute
import slanglsp.editor.AnnotationRecorder
import slanglsp.editor.NativeServerSupport
import slanglsp.editor.inspections.naming.InconsistentNamingInspection
import slanglsp.settings.project.ProjectSettings

internal class UnusedSymbolsTest {
    @Test
    @Timeout(90)
    @Throws(Exception::class)
    fun findsUnusedDeclarationsUsingNativeServer() {
        val executable = TestDependencies.slangd()
        val fixture = NativeServerSupport.isolatedFixture("UnusedSymbols")
        runInEdtAndWait { fixture.setUp() }

        try {
            val file = arrayOfNulls<PsiFile>(1)
            runInEdtAndWait {
                fixture.enableInspections(UnusedSymbolsInspection(), InconsistentNamingInspection())
                ModuleRootModificationUtil.addContentRoot(fixture.module, fixture.tempDirPath)
                val settings = ProjectSettings.getInstance(fixture.project).getState()
                settings.explicitSlangdLocation = Path.of(executable).parent.toString()
                settings.useLocalVcpkgSlangd = false
                file[0] =
                    fixture.addFileToProject(
                        "unused.slang",
                        """
                        public struct PrimaryHitGpu { public uint flags; }
                        struct UsedType { float field; }
                        float unusedGlobal;
                        public float unusedPublicGlobal;
                        extern float external;
                        public float unusedPublicFunction() { return 0; }
                        public struct ImportedType { float field; }
                        float unusedFunction() { float unusedLocal; float usedLocal = 1; return usedLocal; }
                        UsedType usedFunction() { UsedType value; return value; }
                        [shader("compute")]
                        [numthreads(1,1,1)]
                        void main() { usedFunction(); }
                        void accum(uint32_t* src, uint32_t src_count, uint32_t* dst)
                        {
                            uint32_t total = 0;
                            uint32_t unusedCounter;
                            for (uint32_t i = 0; i < src_count; i++)
                                total += src[i];
                            *dst = total;
                        }
                        [shader("compute")]
                        void pointerMain(uniform uint32_t* src, uniform uint32_t src_count, uniform uint32_t* dst)
                        {
                            uint32_t total = 0;
                            uint32_t unusedEntryCounter;
                            for (uint32_t i = 0; i < src_count; i++)
                                total += src[i];
                            *dst = total;
                        }
                        
                        """
                            .trimIndent())
                fixture.addFileToProject(
                    "consumer.slang",
                    """
                        import unused;
                        ImportedType imported;
                        
                        """
                        .trimIndent())
                settings.additionalIncludePaths = mutableListOf(file[0]!!.virtualFile.parent.path)
                ProjectSettings.getInstance(fixture.project).loadState(settings)
                NativeServerSupport.enable(fixture.project)
                FileDocumentManager.getInstance().saveAllDocuments()
            }

            val annotator = UnusedSymbolsAnnotator()
            val input = compute { annotator.collectInformation(file[0]!!) }
            Assertions.assertNotNull(input)

            ProgressManager.getInstance()
                .runProcess(
                    {
                        val result = annotator.doAnnotate(checkNotNull(input))
                        Assertions.assertNotNull(result)
                        Assertions.assertNull(result!!.failure, "Batch analysis must work before the file is opened")
                        val names = result.unused.map(UnusedSymbolsAnnotator.Candidate::name)
                        Assertions.assertTrue(
                            names.containsAll(
                                mutableListOf<String?>(
                                    "PrimaryHitGpu",
                                    "unusedGlobal",
                                    "unusedFunction",
                                    "unusedLocal",
                                    "unusedPublicGlobal",
                                    "unusedPublicFunction",
                                    "unusedCounter",
                                    "unusedEntryCounter")),
                            names.toString())
                        for (name in
                            mutableListOf<String?>(
                                "UsedType",
                                "ImportedType",
                                "usedFunction",
                                "usedLocal",
                                "external",
                                "main",
                                "value",
                                "total",
                                "i",
                                "pointerMain")) {
                            Assertions.assertFalse(names.contains(name), "$name in $names")
                        }
                    },
                    EmptyProgressIndicator())

            runInEdtAndWait {
                fixture.configureFromExistingVirtualFile(file[0]!!.virtualFile)
                val offset = checkNotNull(input).text.indexOf("PrimaryHitGpu")
                val struct =
                    fixture.doHighlighting().first { highlight ->
                        highlight.startOffset == offset && highlight.description?.contains("PrimaryHitGpu") == true
                    }
                Assertions.assertEquals(CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES, struct.forcedTextAttributesKey)
            }
            val removalInput = compute { annotator.collectForEditor(file[0]!!) }
            val removal = arrayOfNulls<UnusedSymbolsAnnotator.Result>(1)
            ProgressManager.getInstance()
                .runProcess({ removal[0] = annotator.doAnnotate(checkNotNull(removalInput)) }, EmptyProgressIndicator())
            Assertions.assertNotNull(removal[0])
            runInEdtAndWait {
                val holder = AnnotationRecorder()
                annotator.apply(file[0]!!, removal[0], holder.holder())
                val offset = removalInput!!.text.indexOf("unusedFunction")
                val warning = holder.first { it.startOffset == offset }
                Assertions.assertEquals("Function 'unusedFunction' is never used", warning.message)
                val fix = warning.quickFixes.first().quickFix
                Assertions.assertEquals("Remove unused function", fix.text)
                Assertions.assertEquals("Remove Unused Declaration", fix.familyName)
                Assertions.assertTrue(fix.isAvailable(fixture.project, fixture.editor, file[0]))
                fix.invoke(fixture.project, fixture.editor, file[0])
                PlatformTestUtil.waitWithEventsDispatching(
                    "Remove unused function", { !fixture.editor.document.text.contains("float unusedFunction()") }, 30)
                Assertions.assertFalse(fixture.editor.document.text.contains("float unusedLocal;"))
                Assertions.assertTrue(fixture.editor.document.text.contains("UsedType usedFunction()"))
                UndoManager.getInstance(fixture.project)
                    .undo(FileEditorManager.getInstance(fixture.project).getSelectedEditor(file[0]!!.virtualFile))
                Assertions.assertEquals(removalInput.text, fixture.editor.document.text)
                Assertions.assertFalse(
                    fix.isAvailable(fixture.project, fixture.editor, file[0]),
                    "A stale removal fix must not mutate a changed document")
            }
        } finally {
            try {
                NativeServerSupport.stop(fixture.project)
            } finally {
                runInEdtAndWait { fixture.tearDown() }
            }
        }
    }
}
