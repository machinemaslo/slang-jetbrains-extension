package slanglsp.editor.inspections.diagnostics

import com.intellij.codeInsight.CodeSmellInfo
import com.intellij.codeInspection.InspectionManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.*
import slanglsp.TestDependencies
import slanglsp.analysis.ReadActions.compute
import slanglsp.editor.NativeServerSupport
import slanglsp.editor.inspections.diagnostics.BatchDiagnostics.run
import slanglsp.editor.inspections.naming.InconsistentNamingInspection
import slanglsp.editor.inspections.naming.NamingAnnotator
import slanglsp.editor.inspections.unused.UnusedSymbolsAnnotator
import slanglsp.editor.inspections.unused.UnusedSymbolsInspection
import slanglsp.replaceAllText
import slanglsp.settings.project.ProjectSettings

@Timeout(40)
internal class BatchDiagnosticsTest {
    private var fixture: CodeInsightTestFixture? = null

    @BeforeEach
    @Throws(Exception::class)
    fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture =
            factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder(LightProjectDescriptor(), "SlangBatchDiagnostics").getFixture(),
                TempDirTestFixtureImpl())
        runInEdtAndWait {
            fixture!!.setUp()
            ModuleRootModificationUtil.addContentRoot(fixture!!.module, fixture!!.tempDirPath)
            NativeServerSupport.disable(fixture!!.project)
            val state = ProjectSettings.getInstance(fixture!!.project).getState()
            state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).parent.toString()
            state.useLocalVcpkgSlangd = false
            ProjectSettings.getInstance(fixture!!.project).loadState(state)
            fixture!!.enableInspections(BatchDiagnosticsInspection())
        }
    }

    @AfterEach
    @Throws(Exception::class)
    fun tearDown() {
        runInEdtAndWait { fixture!!.tearDown() }
    }

    @Test
    fun commitCheckReportsMissingBraceInUnopenedFileAndClearsItAfterCorrection() {
        val file = source("broken.slang", "int f(int a){if(a > 0)\nreturn a;\nreturn 0;\n")
        Assertions.assertTimeout(Duration.ofSeconds(15)) {
            val errors = analyze(file)
            Assertions.assertTrue(
                errors.any { error ->
                    error.severity === HighlightSeverity.ERROR && error.description.contains("expected '}'")
                },
                errors.toString())
        }
        edit(file, "int f(int a){if(a > 0)\nreturn a;\nreturn 0;\n}\n")
        Assertions.assertTrue(analyze(file).isEmpty(), "Corrected documents must not retain cached errors")
    }

    @Test
    @Throws(Exception::class)
    fun multipleFilesUseUnsavedTextAndReportUnicodePositionWithoutSaving() {
        val good = source("good.slang", "int good(){return 0;}\n")
        val bad = source("bad.slang", "int bad(){return 0;}\n")
        val edited = "int bad(){ /* é🙂 */ return missing; }\n"
        edit(bad, edited)
        val errors = analyze(good, bad)
        val missing = errors.first { it.description.contains("missing") }
        Assertions.assertEquals(HighlightSeverity.ERROR, missing.severity)
        Assertions.assertEquals(edited.indexOf("missing"), missing.textRange.startOffset)
        Assertions.assertEquals("int bad(){return 0;}\n", Files.readString(bad.virtualFile.toNioPath()))
        Assertions.assertFalse(errors.any { it.document.text.contains("int good") })
    }

    @Test
    fun inspectCodeUsesRegisteredPairedInspection() {
        val file = source("unknown.slang", "int f(){return unknown;}\n")
        val manager = InspectionManager.getInstance(fixture!!.project)
        val problems = BatchDiagnosticsInspection().checkFile(file, manager.createNewGlobalContext(), manager)
        Assertions.assertTrue(problems.any { it.descriptionTemplate.contains("unknown") })
    }

    @Test
    fun projectMacrosAndIncludePathsAreUsed() {
        val include = source("headers/common.slang", "public int helper(){return VALUE;}\n")
        runInEdtAndWait {
            val state = ProjectSettings.getInstance(fixture!!.project).getState()
            state.additionalIncludePaths = mutableListOf(include.virtualFile.parent.path)
            state.predefinedMacros = mutableListOf("VALUE=7")
            ProjectSettings.getInstance(fixture!!.project).loadState(state)
        }
        val file = source("nested/main.slang", "import common;\nint f(){return helper();}\n")
        Assertions.assertTrue(analyze(file).isEmpty())
    }

    @Test
    @Throws(Exception::class)
    fun missingCompilerIsReportedInsteadOfPassing() {
        val file = source("valid.slang", "int f(){return 0;}\n")
        val installation = Path.of(fixture!!.tempDirPath, "incomplete SDK")
        Files.createDirectories(installation)
        val server = installation.resolve(if (SystemInfo.isWindows) "slangd.exe" else "slangd")
        Files.writeString(server, "Discovery fixture; never executed.")
        Assertions.assertTrue(server.toFile().setExecutable(true))
        val settings = ProjectSettings.getInstance(file.project)
        val original = settings.getState()
        runInEdtAndWait {
            val state = settings.getState()
            state.explicitSlangdLocation = installation.toString()
            settings.loadState(state)
        }
        val errors = analyze(file)
        Assertions.assertTrue(
            errors.any { error ->
                error.severity === HighlightSeverity.ERROR && error.description.contains("Slang compiler not found")
            },
            errors.toString())

        val input = checkNotNull(compute { BatchDiagnosticsAnnotator().collectInformation(file) })
        settings.loadState(original)
        val failure = Assertions.assertThrows(IOException::class.java) { BatchDiagnostics.check(input) }
        Assertions.assertTrue(failure.message.orEmpty().contains("Slang compiler not found"), failure.message)
    }

    @Test
    fun importedFileErrorsAreNotDropped() {
        source("dependency.slang", "public int helper(){return missing;}\n")
        val file = source("main.slang", "import dependency;\nint f(){return helper();}\n")
        Assertions.assertTrue(
            analyze(file).any { error ->
                error.severity === HighlightSeverity.ERROR && error.description.contains("missing")
            })
    }

    @Test
    fun warningSeverityIsPreserved() {
        val file = source("warning.slang", "#warning review this\nint f(){return 0;}\n")
        val warnings = analyze(file)
        Assertions.assertTrue(
            warnings.any { warning ->
                warning.severity === HighlightSeverity.WARNING && warning.description.contains("review this")
            },
            warnings.toString())
    }

    @Test
    fun editorHighlightingDoesNotLaunchBatchCompiler() {
        val file = source("editor.slang", "int f(){return 0;}\n")
        runInEdtAndWait {
            fixture!!.configureFromExistingVirtualFile(file.virtualFile)
            Assertions.assertNull(BatchDiagnosticsAnnotator().collectInformation(file, fixture!!.editor, false))
        }
    }

    @Test
    fun unavailableServerIsNotReportedAsSuccessfulBatchAnalysis() {
        runInEdtAndWait {
            fixture!!.enableInspections(InconsistentNamingInspection(), UnusedSymbolsInspection())
        }
        val file = source("names.slang", "int BadName(int BadParameter){return BadParameter;}\n")
        val naming = NamingAnnotator()
        val unused = UnusedSymbolsAnnotator()
        val namingInput = compute { naming.collectInformation(file) }
        val unusedInput = compute { unused.collectInformation(file) }
        Assertions.assertNotNull(namingInput)
        Assertions.assertNotNull(unusedInput)
        Assertions.assertTimeout(Duration.ofSeconds(1)) {
            Assertions.assertNotNull(checkNotNull(naming.doAnnotate(checkNotNull(namingInput))).failure)
            Assertions.assertNotNull(checkNotNull(unused.doAnnotate(checkNotNull(unusedInput))).failure)
        }
    }

    @Test
    fun cancelledBatchDoesNotReportSuccess() {
        val file = source("cancel.slang", "int f(){return 0;}\n")
        val input = compute { BatchDiagnosticsAnnotator().collectInformation(file) }
        val indicator = ProgressIndicatorBase()
        indicator.cancel()
        Assertions.assertThrows<ProcessCanceledException?>(ProcessCanceledException::class.java) {
            ProgressManager.getInstance()
                .runProcess<BatchDiagnosticsAnnotator.Result>(
                    { BatchDiagnosticsAnnotator().doAnnotate(checkNotNull(input)) }, indicator)
        }
    }

    @Test
    @Throws(Exception::class)
    fun unresponsiveCompilerTimesOutAndIsTerminated() {
        val marker = Path.of(fixture!!.tempDirPath, "timeout.pid")
        val failure = Assertions.assertThrows(IOException::class.java) { run(unresponsiveCompiler(marker), "", 2000) }
        Assertions.assertTrue(failure.message!!.contains("timed out"), failure.message)
        assertProcessStopped(marker)
    }

    @Test
    @Throws(Exception::class)
    fun cancellingRunningCompilerTerminatesIt() {
        val marker = Path.of(fixture!!.tempDirPath, "cancel.pid")
        val command: GeneralCommandLine = unresponsiveCompiler(marker)
        val indicator = ProgressIndicatorBase()
        Executors.newSingleThreadExecutor().use { executor ->
            val task = executor.submit {
                ProgressManager.getInstance()
                    .runProcess(
                        {
                            try {
                                run(command, "", 10000)
                            } catch (cancelled: ProcessCanceledException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                throw RuntimeException(failure)
                            }
                        },
                        indicator)
            }
            try {
                Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                    // The child writes its PID to disk; wait only until it is safe to cancel it.
                    while (!Files.exists(marker) || Files.size(marker) == 0L) {
                        Thread.sleep(10)
                    }
                }
            } finally {
                indicator.cancel()
            }
            val failure = Assertions.assertThrows(ExecutionException::class.java) { task.get(5, TimeUnit.SECONDS) }
            Assertions.assertInstanceOf<ProcessCanceledException?>(ProcessCanceledException::class.java, failure.cause)
        }
        assertProcessStopped(marker)
    }

    private fun source(name: String, text: String): PsiFile {
        val result = AtomicReference<PsiFile>()
        runInEdtAndWait {
            result.set(fixture!!.addFileToProject(name, text))
        }
        return result.get()
    }

    private fun edit(file: PsiFile, text: String) {
        runInEdtAndWait {
            val manager = PsiDocumentManager.getInstance(fixture!!.project)
            val document = checkNotNull(manager.getDocument(file))
            WriteCommandAction.runWriteCommandAction(fixture!!.project) { document.replaceAllText(text) }
            manager.commitAllDocuments()
        }
    }

    private fun analyze(vararg files: PsiFile): MutableList<CodeSmellInfo> {
        val result = AtomicReference<MutableList<CodeSmellInfo>>()
        ProgressManager.getInstance()
            .runProcess(
                {
                    result.set(
                        CodeSmellDetector.getInstance(fixture!!.project).findCodeSmells(files.map { it.virtualFile }))
                },
                ProgressIndicatorBase())
        return result.get()
    }

    companion object {
        private fun unresponsiveCompiler(marker: Path): GeneralCommandLine {
            val java =
                Path.of(System.getProperty("java.home"), "bin", if (SystemInfo.isWindows) "java.exe" else "java")
                    .toString()
            val classpath =
                listOf(UnresponsiveDiagnosticProcess::class.java, Unit::class.java).joinToString(File.pathSeparator) {
                    type ->
                    checkNotNull(PathManager.getJarPathForClass(type)) { "No classpath entry for ${type.name}" }
                }
            return GeneralCommandLine(
                java, "-cp", classpath, UnresponsiveDiagnosticProcess::class.java.getName(), marker.toString())
        }

        @Throws(Exception::class)
        private fun assertProcessStopped(marker: Path) {
            Assertions.assertTrue(Files.exists(marker), "Test subprocess must actually start")
            val pid = Files.readString(marker).toLong()
            val process = ProcessHandle.of(pid)
            if (process.isPresent) {
                process.get().onExit().get(5, TimeUnit.SECONDS)
            }
        }
    }
}
