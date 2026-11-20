package slanglsp.analysis

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.*
import slanglsp.TestDependencies
import slanglsp.editor.NativeServerSupport
import slanglsp.replaceAllText
import slanglsp.server.BatchConnection.wrapper
import slanglsp.settings.project.ProjectSettings

@Timeout(40)
internal class BatchAnalysisServiceTest {
    private var fixture: CodeInsightTestFixture? = null

    @BeforeEach
    @Throws(Exception::class)
    fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture =
            factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("BatchAnalysisService").getFixture(), TempDirTestFixtureImpl())
        runInEdtAndWait {
            fixture!!.setUp()
            ModuleRootModificationUtil.addContentRoot(fixture!!.module, fixture!!.tempDirPath)
            val state = ProjectSettings.getInstance(fixture!!.project).getState()
            state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).parent.toString()
            state.useLocalVcpkgSlangd = false
            ProjectSettings.getInstance(fixture!!.project).loadState(state)
            NativeServerSupport.enable(fixture!!.project)
        }
    }

    @AfterEach
    @Throws(Exception::class)
    fun tearDown() {
        try {
            NativeServerSupport.stop(fixture!!.project)
        } finally {
            runInEdtAndWait { fixture!!.tearDown() }
        }
    }

    @Test
    @Throws(Exception::class)
    fun batchAndEditorShareTheSynchronizerAcrossUnsavedChangesAndRestart() {
        val source = AtomicReference<PsiFile>()
        runInEdtAndWait {
            source.set(fixture!!.addFileToProject("shared.slang", "float first() { return 1; }\n"))
        }
        val file = source.get()
        val document = runReadActionBlocking { PsiDocumentManager.getInstance(fixture!!.project).getDocument(file) }
        Assertions.assertNotNull(document)
        val service = fixture!!.project.getService(BatchAnalysisService::class.java)
        Assertions.assertEquals(
            "first", service.symbols(file, document!!).get(10, TimeUnit.SECONDS)?.first()?.documentSymbol?.name)
        val wrapper = wrapper(fixture!!.project)
        val uri = wrapper.toUri(file.virtualFile)
        val synchronizer = Objects.requireNonNull(wrapper.getOpenedDocument(uri))!!.synchronizer
        Assertions.assertNotNull(synchronizer)
        Assertions.assertTrue(synchronizer!!.didOpenFuture.isDone)
        runInEdtAndWait {
            fixture!!.configureFromExistingVirtualFile(file.virtualFile)
            WriteCommandAction.runWriteCommandAction(fixture!!.project) {
                document.replaceAllText(
                    """
                    // é🙂
                    float second() { return 2; }
                    float caller() { return second(); }

                    """
                        .trimIndent())
            }
            PsiDocumentManager.getInstance(fixture!!.project).commitAllDocuments()
        }
        Assertions.assertEquals(
            "second", service.symbols(file, document).get(10, TimeUnit.SECONDS)?.first()?.documentSymbol?.name)
        Assertions.assertSame(synchronizer, Objects.requireNonNull(wrapper.getOpenedDocument(uri))!!.synchronizer)
        Assertions.assertEquals(1, wrapper.getOpenedDocuments().size)
        Assertions.assertTrue(
            runReadActionBlocking {
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().isDocumentUnsaved(document)
            })
        val text = document.text
        val references = service.references(file, document, text.indexOf("second")).get(10, TimeUnit.SECONDS)
        // Check against LSP4IJ directly rather than reusing the production capability adapter.
        @Suppress("UnstableApiUsage")
        val supportsReferences = runReadActionBlocking { wrapper.clientFeatures.referencesFeature.isSupported(file) }
        if (supportsReferences) {
            Assertions.assertNotNull(references)
            Assertions.assertFalse(references!!.isEmpty())
        } else {
            Assertions.assertNull(references, "Unsupported references must select the indexed definition fallback")
        }
        val definitions = service.definitions(file, document, text.lastIndexOf("second")).get(10, TimeUnit.SECONDS)
        Assertions.assertFalse(definitions!!.isEmpty())
        Assertions.assertEquals(1, definitions.first()?.location()?.range?.start?.line)

        NativeServerSupport.stop(fixture!!.project)
        runInEdtAndWait { NativeServerSupport.enable(fixture!!.project) }
        Assertions.assertEquals(
            "second", service.symbols(file, document).get(10, TimeUnit.SECONDS)?.first()?.documentSymbol?.name)
        val restarted = wrapper(fixture!!.project)
        Assertions.assertNotSame(synchronizer, Objects.requireNonNull(restarted.getOpenedDocument(uri))!!.synchronizer)
        Assertions.assertEquals(1, restarted.getOpenedDocuments().size)
    }

    @Test
    @Throws(Exception::class)
    fun responseMappingUsesPrivateExecutorAndCancellationReachesTheWireFuture() {
        val service = BatchAnalysisService(fixture!!.project)
        try {
            val wire = CompletableFuture<String?>()
            val mapped = service.mapResponse(wire) { Thread.currentThread().name }
            wire.complete("ready")
            Assertions.assertTrue(mapped.get(5, TimeUnit.SECONDS)!!.startsWith("Slang batch requests-"))

            val unresponsive = CompletableFuture<String?>()
            val cancelled = service.mapResponse(unresponsive) { it }
            cancelled.cancel(true)
            Assertions.assertTrue(unresponsive.isCancelled())
        } finally {
            service.dispose()
        }
    }

    @Test
    @Throws(Exception::class)
    fun disposalCancelsQueuedCallbacksAndRejectsNewWork() {
        val service = BatchAnalysisService(fixture!!.project)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val pending = service.callback {
                started.countDown()
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                "finished"
            }
            Assertions.assertTrue(started.await(5, TimeUnit.SECONDS))
            service.dispose()
            Assertions.assertTrue(pending.isCancelled())
            Assertions.assertTrue(service.callback { "must not run" }.isCompletedExceptionally())
        } finally {
            release.countDown()
            service.dispose()
        }
    }
}
