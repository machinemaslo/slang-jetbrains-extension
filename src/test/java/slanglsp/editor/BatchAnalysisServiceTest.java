package slanglsp.editor;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import slanglsp.TestDependencies;
import slanglsp.server.BatchConnection;
import slanglsp.settings.ProjectSettings;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class BatchAnalysisServiceTest {
    private CodeInsightTestFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("BatchAnalysisService").getFixture(), new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(() -> {
            fixture.setUp();
            ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).getParent().toString();
            state.useLocalVcpkgSlangd = false;
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
            NativeServerSupport.enable(fixture.getProject());
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            NativeServerSupport.stop(fixture.getProject());
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }

    @Test
    void batchAndEditorShareTheSynchronizerAcrossUnsavedChangesAndRestart() throws Exception {
        var source = new java.util.concurrent.atomic.AtomicReference<com.intellij.psi.PsiFile>();
        EdtTestUtil.runInEdtAndWait(() -> source.set(
                fixture.addFileToProject("shared.slang", "float first() { return 1; }\n")));
        var file = source.get();
        var document = ReadActions.compute(() -> PsiDocumentManager.getInstance(fixture.getProject()).getDocument(file));
        assertNotNull(document);
        var service = fixture.getProject().getService(BatchAnalysisService.class);
        assertEquals("first", service.symbols(file, document).get(10, TimeUnit.SECONDS)
                .getFirst().getDocumentSymbol().getName());
        var wrapper = BatchConnection.wrapper(fixture.getProject());
        var uri = wrapper.toUri(file.getVirtualFile());
        var synchronizer = wrapper.getOpenedDocument(uri).getSynchronizer();
        assertNotNull(synchronizer);
        assertTrue(synchronizer.getDidOpenFuture().isDone());
        EdtTestUtil.runInEdtAndWait(() -> {
            fixture.configureFromExistingVirtualFile(file.getVirtualFile());
            WriteCommandAction.runWriteCommandAction(fixture.getProject(),
                    () -> document.setText("// é🙂\nfloat second() { return 2; }\n"
                            + "float caller() { return second(); }\n"));
            PsiDocumentManager.getInstance(fixture.getProject()).commitAllDocuments();
        });
        assertEquals("second", service.symbols(file, document).get(10, TimeUnit.SECONDS)
                .getFirst().getDocumentSymbol().getName());
        assertSame(synchronizer, wrapper.getOpenedDocument(uri).getSynchronizer());
        assertEquals(1, wrapper.getOpenedDocuments().size());
        assertTrue(ReadActions.compute(() -> FileDocumentManager.getInstance().isDocumentUnsaved(document)));
        String text = document.getText();
        var references = service.references(file, document, text.indexOf("second")).get(10, TimeUnit.SECONDS);
        if (ReadActions.compute(() -> wrapper.getClientFeatures().getReferencesFeature().isSupported(file))) {
            assertNotNull(references);
            assertFalse(references.isEmpty());
        } else {
            assertNull(references, "Unsupported references must select the indexed definition fallback");
        }
        var definitions = service.definitions(file, document, text.lastIndexOf("second")).get(10, TimeUnit.SECONDS);
        assertFalse(definitions.isEmpty());
        assertEquals(1, definitions.getFirst().location().getRange().getStart().getLine());

        NativeServerSupport.stop(fixture.getProject());
        EdtTestUtil.runInEdtAndWait(() -> NativeServerSupport.enable(fixture.getProject()));
        assertEquals("second", service.symbols(file, document).get(10, TimeUnit.SECONDS)
                .getFirst().getDocumentSymbol().getName());
        var restarted = BatchConnection.wrapper(fixture.getProject());
        assertNotSame(synchronizer, restarted.getOpenedDocument(uri).getSynchronizer());
        assertEquals(1, restarted.getOpenedDocuments().size());
    }

    @Test
    void responseMappingUsesPrivateExecutorAndCancellationReachesTheWireFuture() throws Exception {
        var service = new BatchAnalysisService(fixture.getProject());
        try {
            var wire = new java.util.concurrent.CompletableFuture<String>();
            var mapped = service.mapResponse(wire, value -> Thread.currentThread().getName());
            wire.complete("ready");
            assertTrue(mapped.get(5, TimeUnit.SECONDS).startsWith("Slang batch requests-"));

            var unresponsive = new java.util.concurrent.CompletableFuture<String>();
            var cancelled = service.mapResponse(unresponsive, value -> value);
            cancelled.cancel(true);
            assertTrue(unresponsive.isCancelled());
        } finally {
            service.dispose();
        }
    }

    @Test
    void disposalCancelsQueuedCallbacksAndRejectsNewWork() throws Exception {
        var service = new BatchAnalysisService(fixture.getProject());
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var pending = service.callback(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return "finished";
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            service.dispose();
            assertTrue(pending.isCancelled());
            assertTrue(service.callback(() -> "must not run").isCompletedExceptionally());
        } finally {
            release.countDown();
            service.dispose();
        }
    }
}
