package slanglsp.editor;

import com.intellij.codeInsight.CodeSmellInfo;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vcs.CodeSmellDetector;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import slanglsp.TestDependencies;
import slanglsp.settings.ProjectSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class BatchDiagnosticsTest {
    private CodeInsightTestFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        fixture = factory.createCodeInsightFixture(factory.createLightFixtureBuilder(
                new com.intellij.testFramework.LightProjectDescriptor(), "SlangBatchDiagnostics").getFixture(),
                new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(() -> {
            fixture.setUp();
            ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());
            NativeServerSupport.disable(fixture.getProject());
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).getParent().toString();
            state.useLocalVcpkgSlangd = false;
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
            fixture.enableInspections(new BatchDiagnosticsInspection());
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        EdtTestUtil.runInEdtAndWait(fixture::tearDown);
    }

    @Test
    void commitCheckReportsMissingBraceInUnopenedFileAndClearsItAfterCorrection() {
        var file = source("broken.slang", "int f(int a){if(a > 0)\nreturn a;\nreturn 0;\n");
        assertTimeout(Duration.ofSeconds(15), () -> {
            var errors = analyze(file);
            assertTrue(errors.stream().anyMatch(error -> error.getSeverity() == HighlightSeverity.ERROR
                    && error.getDescription().contains("expected '}'")), errors.toString());
        });
        edit(file, "int f(int a){if(a > 0)\nreturn a;\nreturn 0;\n}\n");
        assertTrue(analyze(file).isEmpty(), "Corrected documents must not retain cached errors");
    }

    @Test
    void multipleFilesUseUnsavedTextAndReportUnicodePositionWithoutSaving() throws Exception {
        var good = source("good.slang", "int good(){return 0;}\n");
        var bad = source("bad.slang", "int bad(){return 0;}\n");
        String edited = "int bad(){ /* é🙂 */ return missing; }\n";
        edit(bad, edited);
        var errors = analyze(good, bad);
        var missing = errors.stream().filter(error -> error.getDescription().contains("missing")).findFirst().orElseThrow();
        assertEquals(HighlightSeverity.ERROR, missing.getSeverity());
        assertEquals(edited.indexOf("missing"), missing.getTextRange().getStartOffset());
        assertEquals("int bad(){return 0;}\n", Files.readString(bad.getVirtualFile().toNioPath()));
        assertFalse(errors.stream().anyMatch(error -> error.getDocument().getText().contains("int good")));
    }

    @Test
    void inspectCodeUsesRegisteredPairedInspection() {
        var file = source("unknown.slang", "int f(){return unknown;}\n");
        var manager = InspectionManager.getInstance(fixture.getProject());
        var problems = new BatchDiagnosticsInspection().checkFile(file, manager.createNewGlobalContext(false), manager);
        assertTrue(java.util.Arrays.stream(problems).anyMatch(problem ->
                problem.getDescriptionTemplate().contains("unknown")));
    }

    @Test
    void projectMacrosAndIncludePathsAreUsed() {
        var include = source("headers/common.slang", "public int helper(){return VALUE;}\n");
        EdtTestUtil.runInEdtAndWait(() -> {
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            state.additionalIncludePaths = List.of(include.getVirtualFile().getParent().getPath());
            state.predefinedMacros = List.of("VALUE=7");
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
        });
        var file = source("nested/main.slang", "import common;\nint f(){return helper();}\n");
        assertTrue(analyze(file).isEmpty());
    }

    @Test
    void missingCompilerIsReportedInsteadOfPassing() throws Exception {
        var file = source("valid.slang", "int f(){return 0;}\n");
        Path installation = Path.of(fixture.getTempDirPath(), "incomplete SDK");
        Files.createDirectories(installation);
        Path server = installation.resolve(com.intellij.openapi.util.SystemInfo.isWindows ? "slangd.exe" : "slangd");
        Files.writeString(server, "Discovery fixture; never executed.");
        assertTrue(server.toFile().setExecutable(true));
        EdtTestUtil.runInEdtAndWait(() -> {
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            state.explicitSlangdLocation = installation.toString();
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
        });
        var errors = analyze(file);
        assertTrue(errors.stream().anyMatch(error -> error.getSeverity() == HighlightSeverity.ERROR
                && error.getDescription().contains("Slang compiler not found")), errors.toString());
    }

    @Test
    void importedFileErrorsAreNotDropped() {
        source("dependency.slang", "public int helper(){return missing;}\n");
        var file = source("main.slang", "import dependency;\nint f(){return helper();}\n");
        assertTrue(analyze(file).stream().anyMatch(error -> error.getSeverity() == HighlightSeverity.ERROR
                && error.getDescription().contains("missing")));
    }

    @Test
    void warningSeverityIsPreserved() {
        var file = source("warning.slang", "#warning review this\nint f(){return 0;}\n");
        var warnings = analyze(file);
        assertTrue(warnings.stream().anyMatch(warning -> warning.getSeverity() == HighlightSeverity.WARNING
                && warning.getDescription().contains("review this")), warnings.toString());
    }

    @Test
    void editorHighlightingDoesNotLaunchBatchCompiler() {
        var file = source("editor.slang", "int f(){return 0;}\n");
        EdtTestUtil.runInEdtAndWait(() -> {
            fixture.configureFromExistingVirtualFile(file.getVirtualFile());
            assertNull(new BatchDiagnosticsAnnotator().collectInformation(file, fixture.getEditor(), false));
        });
    }

    @Test
    void unavailableServerIsNotReportedAsSuccessfulBatchAnalysis() {
        EdtTestUtil.runInEdtAndWait(() -> fixture.enableInspections(
                new InconsistentNamingInspection(), new UnusedSymbolsInspection()));
        var file = source("names.slang", "int BadName(int BadParameter){return BadParameter;}\n");
        var naming = new NamingAnnotator();
        var unused = new UnusedSymbolsAnnotator();
        var namingInput = ReadActions.compute(() -> naming.collectInformation(file));
        var unusedInput = ReadActions.compute(() -> unused.collectInformation(file));
        assertNotNull(namingInput);
        assertNotNull(unusedInput);
        assertTimeout(Duration.ofSeconds(1), () -> {
            assertNotNull(naming.doAnnotate(namingInput).failure());
            assertNotNull(unused.doAnnotate(unusedInput).failure());
        });
    }

    @Test
    void cancelledBatchDoesNotReportSuccess() {
        var file = source("cancel.slang", "int f(){return 0;}\n");
        var input = ReadActions.compute(() -> new BatchDiagnosticsAnnotator().collectInformation(file));
        var indicator = new ProgressIndicatorBase();
        indicator.cancel();
        assertThrows(ProcessCanceledException.class, () -> ProgressManager.getInstance().runProcess(
                () -> new BatchDiagnosticsAnnotator().doAnnotate(input), indicator));
    }

    @Test
    void unresponsiveCompilerTimesOutAndIsTerminated() throws Exception {
        Path marker = Path.of(fixture.getTempDirPath(), "timeout.pid");
        var failure = assertThrows(java.io.IOException.class,
                () -> BatchDiagnostics.run(unresponsiveCompiler(marker), "", 2_000));
        assertTrue(failure.getMessage().contains("timed out"), failure.getMessage());
        assertProcessStopped(marker);
    }

    @Test
    void cancellingRunningCompilerTerminatesIt() throws Exception {
        Path marker = Path.of(fixture.getTempDirPath(), "cancel.pid");
        var command = unresponsiveCompiler(marker);
        var indicator = new ProgressIndicatorBase();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> ProgressManager.getInstance().runProcess(
                    () -> {
                        try {
                            BatchDiagnostics.run(command, "", 10_000);
                        } catch (ProcessCanceledException cancelled) {
                            throw cancelled;
                        } catch (Exception failure) {
                            throw new RuntimeException(failure);
                        }
                    }, indicator));
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (!Files.exists(marker) || Files.size(marker) == 0) Thread.sleep(10);
                });
            } finally {
                indicator.cancel();
            }
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> task.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(ProcessCanceledException.class, failure.getCause());
        }
        assertProcessStopped(marker);
    }

    private static com.intellij.execution.configurations.GeneralCommandLine unresponsiveCompiler(Path marker)
            throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                com.intellij.openapi.util.SystemInfo.isWindows ? "java.exe" : "java").toString();
        String classes = com.intellij.openapi.application.PathManager
                .getJarPathForClass(UnresponsiveDiagnosticProcess.class);
        return new com.intellij.execution.configurations.GeneralCommandLine(java, "-cp", classes,
                UnresponsiveDiagnosticProcess.class.getName(), marker.toString());
    }

    private static void assertProcessStopped(Path marker) throws Exception {
        assertTrue(Files.exists(marker), "Test subprocess must actually start");
        long pid = Long.parseLong(Files.readString(marker));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) Thread.sleep(10);
        });
    }

    private PsiFile source(String name, String text) {
        var result = new AtomicReference<PsiFile>();
        EdtTestUtil.runInEdtAndWait(() -> result.set(fixture.addFileToProject(name, text)));
        return result.get();
    }

    private void edit(PsiFile file, String text) {
        EdtTestUtil.runInEdtAndWait(() -> {
            var manager = PsiDocumentManager.getInstance(fixture.getProject());
            WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> manager.getDocument(file).setText(text));
            manager.commitAllDocuments();
        });
    }

    private List<CodeSmellInfo> analyze(PsiFile... files) {
        var result = new AtomicReference<List<CodeSmellInfo>>();
        ProgressManager.getInstance().runProcess(() -> result.set(CodeSmellDetector.getInstance(fixture.getProject())
                .findCodeSmells(java.util.Arrays.stream(files).map(PsiFile::getVirtualFile).toList())),
                new ProgressIndicatorBase());
        return result.get();
    }
}
