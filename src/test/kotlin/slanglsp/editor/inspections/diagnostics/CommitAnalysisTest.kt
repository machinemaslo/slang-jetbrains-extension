package slanglsp.editor.inspections.diagnostics

import com.intellij.codeInsight.CodeSmellInfo
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import java.nio.file.Path
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import slanglsp.TestDependencies
import slanglsp.editor.NativeServerSupport
import slanglsp.editor.inspections.naming.InconsistentNamingInspection
import slanglsp.editor.inspections.unused.UnusedSymbolsInspection
import slanglsp.settings.project.ProjectSettings

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
            runInEdtAndWait {
                ModuleRootModificationUtil.addContentRoot(fixture.module, fixture.tempDirPath)
                fixture.enableInspections(
                    BatchDiagnosticsInspection(), InconsistentNamingInspection(), UnusedSymbolsInspection())
                val state = ProjectSettings.getInstance(fixture.project).getState()
                state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).parent.toString()
                state.useLocalVcpkgSlangd = false
                ProjectSettings.getInstance(fixture.project).loadState(state)
                NativeServerSupport.enable(fixture.project)
                files.add(fixture.addFileToProject("shared.slang", "public float sharedValue() { return 1; }\n"))
                for (i in 0..7) {
                    files.add(
                        fixture.addFileToProject(
                            "shader$i.slang", "import shared;\nfloat BadName() { return sharedValue(); }\n"))
                }
                FileDocumentManager.getInstance().saveAllDocuments()
            }
            for (phase in arrayOf("cold", "warm", "open editors", "restarted")) {
                if (phase == "restarted") {
                    NativeServerSupport.stop(fixture.project)
                    runInEdtAndWait {
                        NativeServerSupport.enable(fixture.project)
                    }
                }
                if (phase == "open editors") {
                    runInEdtAndWait {
                        for (file in files) {
                            fixture.configureFromExistingVirtualFile(file.virtualFile)
                        }
                    }
                }
                val start = System.nanoTime()
                ProgressManager.getInstance()
                    .runProcess(
                        {
                            val smells =
                                CodeSmellDetector.getInstance(fixture.project)
                                    .findCodeSmells(
                                        files
                                            .stream()
                                            .map<VirtualFile?> { obj: PsiFile? -> obj!!.virtualFile }
                                            .toList())
                            Assertions.assertTrue(
                                smells.stream().anyMatch { s: CodeSmellInfo? -> s!!.description.contains("BadName") },
                                "Naming/unused analysis must run on unopened files")
                            Assertions.assertFalse(
                                smells.stream().anyMatch { s: CodeSmellInfo? ->
                                    s!!.description.contains("could not complete")
                                },
                                smells.toString())
                        },
                        ProgressIndicatorBase())
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
                            CodeSmellDetector.getInstance(fixture.project)
                                .findCodeSmells(
                                    files.stream().map<VirtualFile?> { obj: PsiFile? -> obj!!.virtualFile }.toList())
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
