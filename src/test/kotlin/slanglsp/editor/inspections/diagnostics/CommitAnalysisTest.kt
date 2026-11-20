package slanglsp.editor.inspections.diagnostics

import com.intellij.codeInsight.CodeSmellInfo
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Isolated
import slanglsp.TestDependencies
import slanglsp.editor.NativeServerSupport
import slanglsp.editor.inspections.naming.InconsistentNamingInspection
import slanglsp.editor.inspections.unused.UnusedSymbolsAnnotator
import slanglsp.editor.inspections.unused.UnusedSymbolsInspection
import slanglsp.settings.project.ProjectSettings

@Isolated
internal class CommitAnalysisTest {
    @Test
    @Timeout(120)
    @Throws(Exception::class)
    fun coldWarmAndOpenEditorAnalysisWithStockServer() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture =
            factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("CommitAnalysis").getFixture(), TempDirTestFixtureImpl())
        runInEdtAndWait { fixture.setUp() }
        try {
            val files = ArrayList<PsiFile>()
            lateinit var edited: PsiFile
            runInEdtAndWait {
                ModuleRootModificationUtil.addContentRoot(fixture.module, fixture.tempDirPath)
                fixture.enableInspections(
                    BatchDiagnosticsInspection(), InconsistentNamingInspection(), UnusedSymbolsInspection())
                val state = ProjectSettings.getInstance(fixture.project).getState()
                state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).parent.toString()
                state.useLocalVcpkgSlangd = false
                ProjectSettings.getInstance(fixture.project).loadState(state)
                NativeServerSupport.enable(fixture.project)
                files.add(
                    fixture.addFileToProject(
                        "shared.slang",
                        "public float sharedValue() { return 1; }\npublic float usedByUnsaved() { return 2; }\n",
                    ),
                )
                for (i in 0..7) {
                    files.add(
                        fixture.addFileToProject(
                            "shader$i.slang", "import shared;\nfloat BadName() { return sharedValue(); }\n"))
                }
                edited = fixture.addFileToProject("edited.slang", "float original() { return 0; }\n")
                files.add(edited)
                files.add(fixture.addFileToProject("broken.slang", "float broken() { return missingValue; }\n"))
                FileDocumentManager.getInstance().saveAllDocuments()
                val documents = PsiDocumentManager.getInstance(fixture.project)
                val document = checkNotNull(documents.getDocument(edited))
                WriteCommandAction.runWriteCommandAction(fixture.project) {
                    document.setText("import shared;\nfloat UnsavedName() { float unusedLocal; return usedByUnsaved(); }\n")
                }
                documents.commitDocument(document)
                Assertions.assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
                Assertions.assertFalse(FileEditorManager.getInstance(fixture.project).isFileOpen(edited.virtualFile))
            }
            val toInspect = files.map { it.virtualFile }
            for (phase in arrayOf("cold", "warm", "open editors", "restarted")) {
                if (phase == "restarted") {
                    NativeServerSupport.stop(fixture.project)
                }
                if (phase == "open editors") {
                    runInEdtAndWait {
                        for (file in files.filter { it !== edited && it.name != "broken.slang" }) {
                            FileEditorManager.getInstance(fixture.project).openFile(file.virtualFile, false)
                        }
                    }
                }
                val start = System.nanoTime()
                val checkCommit = Runnable {
                    val descriptions = CodeSmellDetector.getInstance(fixture.project)
                        .findCodeSmells(toInspect)
                        .map(CodeSmellInfo::getDescription)
                    Assertions.assertTrue(
                        descriptions.any { "BadName" in it },
                        "$phase: naming must run on unopened files: $descriptions")
                    Assertions.assertTrue(descriptions.any { "UnsavedName" in it }, "$phase: $descriptions")
                    Assertions.assertTrue(descriptions.any { "missingValue" in it }, "$phase: $descriptions")
                    Assertions.assertFalse(descriptions.any { "could not complete" in it }, "$phase: $descriptions")

                    // The commit UI filters weak warnings, so also verify the batch inspection result.
                    val unused = UnusedSymbolsAnnotator()
                    fun unusedNames(file: PsiFile): List<String> {
                        val input = checkNotNull(runReadActionBlocking { unused.collectInformation(file) })
                        val result = checkNotNull(unused.doAnnotate(input))
                        Assertions.assertNull(result.failure)
                        return result.unused.map { it.name }
                    }

                    Assertions.assertTrue("unusedLocal" in unusedNames(edited))
                    val moduleWarnings = unusedNames(files[0])
                    Assertions.assertFalse("sharedValue" in moduleWarnings, moduleWarnings.toString())
                    Assertions.assertFalse("usedByUnsaved" in moduleWarnings, moduleWarnings.toString())
                }
                val progress = ProgressManager.getInstance()
                if (phase == "cold") {
                    runInEdtAndWait {
                        Assertions.assertTrue(
                            progress.runProcessWithProgressSynchronously(
                                checkCommit, "Commit inspections", true, fixture.project,
                            ),
                        )
                    }
                } else {
                    progress.runProcess(checkCommit, ProgressIndicatorBase())
                }
                if (phase == "cold") {
                    Assertions.assertEquals(
                        "float original() { return 0; }\n",
                        Files.readString(edited.virtualFile.toNioPath()),
                        "Commit analysis must not save the unsaved document",
                    )
                }
                println(
                    ("COMMIT ANALYSIS " +
                        phase +
                        " files=" +
                        files.size +
                        " elapsedMs=" +
                        (System.nanoTime() - start) / 1000000))
            }
            val cancelled = ProgressIndicatorBase()
            Assertions.assertThrows<ProcessCanceledException?>(ProcessCanceledException::class.java) {
                ProgressManager.getInstance()
                    .runProcess(
                        {
                            cancelled.cancel()
                            CodeSmellDetector.getInstance(fixture.project).findCodeSmells(toInspect)
                        },
                        cancelled)
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
