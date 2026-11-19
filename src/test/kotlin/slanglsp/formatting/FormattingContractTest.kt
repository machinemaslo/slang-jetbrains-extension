package slanglsp.formatting

import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.Companion.getInstance
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.stream.Collectors
import org.junit.jupiter.api.*
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import slanglsp.FormattingFixtures
import slanglsp.TestDependencies
import slanglsp.editor.NativeServerSupport
import slanglsp.formatting.clang.BundledClangFormat.executable
import slanglsp.language.SlangFileType
import slanglsp.replaceAllText
import slanglsp.settings.codestyle.CodeStylePresets.Slang
import slanglsp.settings.codestyle.SlangCodeStyleSettings
import slanglsp.settings.project.ProjectSettings
import slanglsp.useTemporarySettings

/**
 * Public IDE contracts: native formatting needs no executables; external formatting must use slangd with the selected
 * clang-format executable. Expectations specify our routing, configuration and edit application, not upstream Slang
 * formatting correctness.
 */
@Timeout(90)
@ResourceLock(Resources.SYSTEM_ERR)
internal class FormattingContractTest {
    internal enum class Engine {
        NATIVE,
        SLANGD
    }

    private var fixture: CodeInsightTestFixture? = null
    private var settings: CodeStyleSettings? = null
    private var opened = false
    private val notifications: MutableList<Notification?> = CopyOnWriteArrayList()

    @BeforeEach
    @Throws(Exception::class)
    fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture =
            factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder(LightProjectDescriptor(), "FormattingContract").getFixture(),
                TempDirTestFixtureImpl())
        runInEdtAndWait {
            fixture!!.setUp()
            NativeServerSupport.disable(fixture!!.project)
            ModuleRootModificationUtil.addContentRoot(fixture!!.module, fixture!!.tempDirPath)
            val state = ProjectSettings.getInstance(fixture!!.project).getState()
            state.useLocalVcpkgSlangd = false
            state.explicitSlangdLocation = fixture!!.tempDirPath + "/no-server"
            ProjectSettings.getInstance(fixture!!.project).loadState(state)
            val manager = CodeStyleSettingsManager.getInstance(fixture!!.project)
            settings = manager.createSettings()
            Slang().apply(settings!!)
            manager.useTemporarySettings(settings!!)
            ApplicationManager.getApplication()
                .messageBus
                .connect(fixture!!.testRootDisposable)
                .subscribe(
                    Notifications.TOPIC,
                    object : Notifications {
                        override fun notify(notification: Notification) {
                            notifications.add(notification)
                        }
                    })
        }
    }

    @AfterEach
    @Throws(Exception::class)
    fun tearDown() {
        try {
            NativeServerSupport.stop(fixture!!.project)
        } finally {
            runInEdtAndWait {
                CodeStyleSettingsManager.getInstance(fixture!!.project).dropTemporarySettings()
                fixture!!.tearDown()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Engine::class)
    fun synchronousFormattingUsesCurrentTextAndSupportsUndoRedo(engine: Engine) {
        select(engine)
        // Explicit external configuration makes this an integration assertion, not an
        // assertion about clang-format's default style or Slang syntax support.
        if (engine == Engine.SLANGD) {
            configuration(".clang-format", FormattingFixtures.text("configuration/external/.clang-format"))
        }
        val source = FormattingFixtures.text("simple-function/before.slang")
        val expected =
            FormattingFixtures.text(
                if (engine == Engine.NATIVE) "simple-function/after.allman-four.slang"
                else "simple-function/after.attach-two.slang")
        open(source)
        reformat(expected, false)
        runInEdtAndWait {
            val undo = UndoManager.getInstance(fixture!!.project)
            val editor = getInstance().getTextEditor(fixture!!.editor)
            undo.undo(editor)
            Assertions.assertEquals(source, fixture!!.editor.document.text)
            undo.redo(editor)
            Assertions.assertEquals(expected, fixture!!.editor.document.text)
        }
        reformat(expected, false)
        runInEdtAndWait {
            UndoManager.getInstance(fixture!!.project).undo(getInstance().getTextEditor(fixture!!.editor))
            Assertions.assertEquals(
                source, fixture!!.editor.document.text, "A no-op formatting pass must not create an undo step")
        }
        assertDiskText(if (engine == Engine.NATIVE) FormattingFixtures.text("unsaved/on-disk.slang") else expected)
    }

    @ParameterizedTest
    @EnumSource(Engine::class)
    fun selectionPreservesNeighborsAndUsesUnicodeDocumentOffsets(engine: Engine) {
        select(engine)
        val source = FormattingFixtures.text("selection/before.slang")
        open(source)
        runInEdtAndWait {
            fixture!!
                .editor
                .selectionModel
                .setSelection(source.indexOf("static float second"), source.indexOf("static float third"))
        }
        reformat(FormattingFixtures.text("selection/after.slang"), true)
        assertDiskText(if (engine == Engine.NATIVE) FormattingFixtures.text("unsaved/on-disk.slang") else source)
    }

    @Test
    @Throws(Exception::class)
    fun customExecutablePathWithSpacesIsUsed() {
        select(Engine.SLANGD)
        val binary =
            Path.of(
                fixture!!.tempDirPath, "tools with spaces", Path.of(TestDependencies.clangFormat()).fileName.toString())
        Files.createDirectories(binary.parent)
        Files.copy(Path.of(TestDependencies.clangFormat()), binary, StandardCopyOption.REPLACE_EXISTING)
        Assertions.assertTrue(binary.toFile().setExecutable(true))
        runInEdtAndWait {
            settings!!
                .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                .CLANG_FORMAT_PATH = binary.toString()
        }
        configuration(".clang-format", FormattingFixtures.text("configuration/external/.clang-format"))
        open(FormattingFixtures.text("simple-function/before.slang"))
        reformat(FormattingFixtures.text("simple-function/after.attach-two.slang"), false)
    }

    @Test
    fun bundledSelectionDoesNotSearchPathWhenNoBundledExecutableIsAvailable() {
        select(Engine.SLANGD)
        runInEdtAndWait {
            val custom = settings!!.getCustomSettings(SlangCodeStyleSettings::class.java)
            custom.USE_CUSTOM_CLANG_FORMAT = false
            custom.CLANG_FORMAT_PATH = "clang-format"
        }
        val bundledExecutable = executable()
        Assertions.assertEquals(
            bundledExecutable,
            settings!!
                .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                .clangFormatExecutable())
        open(FormattingFixtures.text("simple-function/before.slang"))
        if (bundledExecutable != null) {
            configuration(".clang-format", FormattingFixtures.text("configuration/external/.clang-format"))
            reformat(FormattingFixtures.text("simple-function/after.attach-two.slang"), false)
        } else {
            assertFormattingFailsWithoutChangingText("clang-format")
        }
    }

    @Test
    fun missingCustomExecutableReportsFailureWithoutNativeFallback() {
        select(Engine.SLANGD)
        runInEdtAndWait {
            settings!!
                .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                .CLANG_FORMAT_PATH = fixture!!.tempDirPath + "/missing-clang-format"
        }
        assertFormattingFailsWithoutChangingText("missing-clang-format")
    }

    @Test
    fun invalidConfigurationReportsFailureWithoutNativeFallback() {
        select(Engine.SLANGD)
        configuration(".clang-format", FormattingFixtures.text("configuration/invalid/.clang-format"))
        assertFormattingFailsWithoutChangingText("NotAClangFormatOption")
    }

    @ParameterizedTest(name = "clang-format configuration: {0}")
    @ValueSource(strings = ["parent", "underscore", "nearest", "inherit"])
    fun externalConfigurationDiscoveryAndPrecedence(scenario: String) {
        select(Engine.SLANGD)
        var expected = "simple-function/after.attach-two.slang"
        configuration(
            if (scenario == "underscore") "_clang-format" else ".clang-format",
            FormattingFixtures.text("configuration/external/.clang-format"))
        if (scenario == "nearest") {
            configuration(
                "nested/.clang-format", FormattingFixtures.text("configuration/external-nearest/.clang-format"))
            expected = "simple-function/after.allman-four.slang"
        } else if (scenario == "inherit") {
            configuration("nested/.clang-format", FormattingFixtures.text("configuration/inherit/.clang-format"))
            expected = "simple-function/after.attach-four.slang"
        }
        open(FormattingFixtures.text("simple-function/before.slang"))
        reformat(FormattingFixtures.text(expected), false)
    }

    @Test
    fun unavailableSlangdReportsFailureInsteadOfInvokingClangFormatDirectly() {
        select(Engine.SLANGD)
        runInEdtAndWait {
            val state = ProjectSettings.getInstance(fixture!!.project).getState()
            state.explicitSlangdLocation = fixture!!.tempDirPath + "/missing-server-directory"
            ProjectSettings.getInstance(fixture!!.project).loadState(state)
            NativeServerSupport.disable(fixture!!.project)
        }
        assertFormattingFailsWithoutChangingText("server")
    }

    @Test
    fun absentConfigurationUsesIdeScheme() {
        select(Engine.SLANGD)
        runInEdtAndWait {
            settings!!.getIndentOptions(SlangFileType).INDENT_SIZE = 3
        }
        open(FormattingFixtures.text("simple-function/before.slang"))
        reformat(FormattingFixtures.text("simple-function/after.allman-three.slang"), false)
    }

    @Test
    fun explicitConfigurationOutsideSourceDirectoryIsRespected() {
        select(Engine.SLANGD)
        val relative = "config with spaces/custom-format.yaml"
        configuration(relative, FormattingFixtures.text("configuration/explicit/custom-format.yaml"))
        runInEdtAndWait {
            val state = ProjectSettings.getInstance(fixture!!.project).getState()
            state.clangFormatStyle = "file:" + Path.of(fixture!!.tempDirPath, relative)
            ProjectSettings.getInstance(fixture!!.project).loadState(state)
        }
        open(FormattingFixtures.text("simple-function/before.slang"))
        reformat(FormattingFixtures.text("simple-function/after.attach-three.slang"), false)
    }

    @RepeatedTest(12)
    fun startupConfigurationKeepsUnderscoreStyle() {
        externalConfigurationDiscoveryAndPrecedence("underscore")
    }

    @ParameterizedTest(name = "server style: {0}")
    @ValueSource(strings = ["inline", "fallback", "named"])
    fun configuredStylesAreForwardedToSlangd(source: String) {
        select(Engine.SLANGD)
        runInEdtAndWait {
            val state = ProjectSettings.getInstance(fixture!!.project).getState()
            val style = FormattingFixtures.withoutFinalNewline("configuration/inline/three-spaces.yaml")
            state.clangFormatStyle =
                when (source) {
                    "inline" -> style
                    "named" -> "Microsoft"
                    else -> "file"
                }
            state.clangFormatFallbackStyle = style
            ProjectSettings.getInstance(fixture!!.project).loadState(state)
        }
        open(FormattingFixtures.text("simple-function/before.slang"))
        reformat(
            FormattingFixtures.text(
                if (source == "named") "simple-function/after.allman-four.slang"
                else "simple-function/after.attach-three.slang"),
            false)
    }

    private fun assertFormattingFailsWithoutChangingText(problem: String) {
        val source = FormattingFixtures.text("simple-function/before.slang")
        if (!opened) {
            open(source)
        }
        runInEdtAndWait {
            val stderr = runFormatting(false)
            Assertions.assertEquals(source, fixture!!.editor.document.text)
            val reported =
                stderr +
                    notifications
                        .stream()
                        .map<String?> { notification: Notification? ->
                            notification!!.title + ": " + notification.content
                        }
                        .collect(Collectors.joining("\n"))
            Assertions.assertTrue(reported.lowercase().contains(problem.lowercase()), reported)
        }
    }

    private fun select(engine: Engine) {
        runInEdtAndWait {
            val custom = settings!!.getCustomSettings(SlangCodeStyleSettings::class.java)
            custom.USE_CLANG_FORMAT = engine == Engine.SLANGD
            custom.USE_CUSTOM_CLANG_FORMAT = engine == Engine.SLANGD
            custom.CLANG_FORMAT_PATH =
                if (engine == Engine.SLANGD) TestDependencies.clangFormat()
                else fixture!!.tempDirPath + "/no-executable"
            val state = ProjectSettings.getInstance(fixture!!.project).getState()
            state.explicitSlangdLocation =
                if (engine == Engine.SLANGD) Path.of(TestDependencies.slangd()).parent.toString()
                else fixture!!.tempDirPath + "/no-server"
            ProjectSettings.getInstance(fixture!!.project).loadState(state)
            if (engine == Engine.SLANGD) {
                NativeServerSupport.enable(fixture!!.project)
            }
        }
    }

    private fun open(source: String) {
        runInEdtAndWait {
            val file =
                fixture!!.addFileToProject("nested/sample.slang", FormattingFixtures.text("unsaved/on-disk.slang"))
            fixture!!.configureFromExistingVirtualFile(file.virtualFile)
            opened = true
            WriteCommandAction.writeCommandAction(fixture!!.project)
                .withName("Edit")
                .withGroupId(UUID.randomUUID().toString())
                .run<RuntimeException?> { fixture!!.editor.document.replaceAllText(source) }
            PsiDocumentManager.getInstance(fixture!!.project).commitAllDocuments()
            fixture!!
                .editor
                .document
                .putUserData<Boolean?>(AsyncDocumentFormattingService.FORMAT_DOCUMENT_SYNCHRONOUSLY, true)
        }
    }

    private fun assertDiskText(expected: String) {
        runInEdtAndWait {
            try {
                Assertions.assertEquals(expected, Files.readString(fixture!!.file.virtualFile.toNioPath()))
            } catch (failure: IOException) {
                throw AssertionError(failure)
            }
        }
    }

    private fun reformat(expected: String, selection: Boolean) {
        runInEdtAndWait {
            val stderr = runFormatting(selection)
            Assertions.assertEquals(expected, fixture!!.editor.document.text) { stderr + notifications }
            Assertions.assertTrue(stderr.isBlank(), stderr)
            Assertions.assertTrue(
                notifications.stream().noneMatch { notification: Notification? ->
                    notification!!.type == NotificationType.ERROR
                },
                notifications.toString())
        }
    }

    private fun runFormatting(selection: Boolean): String {
        notifications.clear()
        val previous = System.err
        val output = ByteArrayOutputStream()
        try {
            PrintStream(output, true, StandardCharsets.UTF_8).use { stream ->
                System.setErr(stream)
                if (selection) {
                    ReformatCodeProcessor(fixture!!.file, fixture!!.editor.selectionModel).runWithoutProgress()
                } else {
                    ReformatCodeProcessor(fixture!!.project, fixture!!.file, null, false).runWithoutProgress()
                }
                // Synchronous callers must see the edits when the processor returns.
                return output.toString(StandardCharsets.UTF_8)
            }
        } finally {
            System.setErr(previous)
        }
    }

    private fun configuration(path: String, source: String) {
        runInEdtAndWait {
            val file = fixture!!.addFileToProject(path, source)
            val document = PsiDocumentManager.getInstance(fixture!!.project).getDocument(file)
            Assertions.assertNotNull(document)
            FileDocumentManager.getInstance().saveDocument(document!!)
        }
    }
}
