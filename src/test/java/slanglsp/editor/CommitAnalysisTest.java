package slanglsp.editor;

import com.intellij.openapi.vcs.CodeSmellDetector;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import slanglsp.TestDependencies;
import slanglsp.settings.ProjectSettings;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class CommitAnalysisTest {
    @Test
    @Timeout(120)
    void coldWarmAndOpenEditorAnalysisWithStockServer() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("CommitAnalysis").getFixture(), new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            var files = new ArrayList<PsiFile>();
            EdtTestUtil.runInEdtAndWait(() -> {
                ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());
                fixture.enableInspections(new BatchDiagnosticsInspection(), new InconsistentNamingInspection(),
                        new UnusedSymbolsInspection());
                var state = ProjectSettings.getInstance(fixture.getProject()).getState();
                state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).getParent().toString();
                state.useLocalVcpkgSlangd = false;
                ProjectSettings.getInstance(fixture.getProject()).loadState(state);
                NativeServerSupport.enable(fixture.getProject());
                files.add(fixture.addFileToProject("shared.slang", "public float sharedValue() { return 1; }\n"));
                for (int i = 0; i < 8; i++) {
                    files.add(fixture.addFileToProject("shader" + i + ".slang",
                            "import shared;\nfloat BadName() { return sharedValue(); }\n"));
                }
                FileDocumentManager.getInstance().saveAllDocuments();
            });
            for (String phase : new String[] {"cold", "warm", "open editors", "restarted"}) {
                if (phase.equals("restarted")) {
                    NativeServerSupport.stop(fixture.getProject());
                    EdtTestUtil.runInEdtAndWait(() -> NativeServerSupport.enable(fixture.getProject()));
                }
                if (phase.equals("open editors")) {
                    EdtTestUtil.runInEdtAndWait(() -> {
                        for (var file : files) fixture.configureFromExistingVirtualFile(file.getVirtualFile());
                    });
                }
                long start = System.nanoTime();
                ProgressManager.getInstance().runProcess(() -> {
                    var smells = CodeSmellDetector.getInstance(fixture.getProject()).findCodeSmells(
                            files.stream().map(PsiFile::getVirtualFile).toList());
                    assertTrue(smells.stream().anyMatch(s -> s.getDescription().contains("BadName")),
                            "Naming/unused analysis must run on unopened files");
                    assertFalse(smells.stream().anyMatch(s -> s.getDescription().contains("could not complete")),
                            smells.toString());
                }, new ProgressIndicatorBase());
                System.out.println("COMMIT ANALYSIS " + phase + " files=" + files.size()
                        + " elapsedMs=" + (System.nanoTime() - start) / 1_000_000);
            }
            var cancelled = new ProgressIndicatorBase();
            assertThrows(com.intellij.openapi.progress.ProcessCanceledException.class,
                    () -> ProgressManager.getInstance().runProcess(() -> {
                        cancelled.cancel();
                        CodeSmellDetector.getInstance(fixture.getProject()).findCodeSmells(
                                files.stream().map(PsiFile::getVirtualFile).toList());
                    }, cancelled));
        } finally {
            try {
                NativeServerSupport.stop(fixture.getProject());
            } finally {
                EdtTestUtil.runInEdtAndWait(fixture::tearDown);
            }
        }
    }
}
