package slanglsp.editor;

import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import slanglsp.TestDependencies;
import slanglsp.language.SlangLanguage;
import slanglsp.settings.CodeStylePresets;
import slanglsp.settings.ProjectSettings;
import slanglsp.settings.SlangCodeStyleSettings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static slanglsp.FormattingFixtures.text;
import static slanglsp.FormattingFixtures.withoutFinalNewline;

/**
 * Tests-first acceptance contract. Formatting must go through the registered IDE service,
 * not ClangFormat.format() with manually constructed options or an expected service class.
 */
@Timeout(90)
@ResourceLock(Resources.SYSTEM_ERR)
class FormattingContractTest {
    enum Engine {
        NATIVE,
        SLANGD
    }

    private CodeInsightTestFixture fixture;
    private CodeStyleSettings settings;
    private final List<Notification> notifications = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        // Negative server/path cases must not poison the shared default light project used
        // by navigation and rename tests. A fresh descriptor gives each case its own project.
        fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder(
                        new com.intellij.testFramework.LightProjectDescriptor(), "FormattingContract").getFixture(),
                new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(() -> {
            fixture.setUp();
            NativeServerSupport.disable(fixture.getProject());
            ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());
            var manager = CodeStyleSettingsManager.getInstance(fixture.getProject());
            settings = manager.createSettings();
            new CodeStylePresets.Slang().apply(settings);
            manager.setTemporarySettings(settings);
            var listener = new Notifications() {
                @Override
                public void notify(@NotNull Notification notification) {
                    notifications.add(notification);
                }
            };
            ApplicationManager.getApplication().getMessageBus().connect(fixture.getTestRootDisposable())
                    .subscribe(Notifications.TOPIC, listener);
            fixture.getProject().getMessageBus().connect(fixture.getTestRootDisposable())
                    .subscribe(Notifications.TOPIC, listener);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            NativeServerSupport.stop(fixture.getProject());
        } finally {
            EdtTestUtil.runInEdtAndWait(() -> {
                CodeStyleSettingsManager.getInstance(fixture.getProject()).dropTemporarySettings();
                fixture.tearDown();
            });
        }
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void shaderGoldenOutputIsSlangAwareAndUndoable(Engine engine) {
        String original = text("shader/before.slang");
        String expected = text(engine == Engine.NATIVE ? "shader/after.native.slang" : "shader/after.slangd.slang");
        select(engine);
        open(original);
        reformat(expected);
        EdtTestUtil.runInEdtAndWait(() -> {
            var undo = UndoManager.getInstance(fixture.getProject());
            var editor = TextEditorProvider.getInstance().getTextEditor(fixture.getEditor());
            assertTrue(undo.isUndoAvailable(editor));
            undo.undo(editor);
            assertEquals(original, fixture.getEditor().getDocument().getText());
            undo.redo(editor);
            assertEquals(expected, fixture.getEditor().getDocument().getText());
        });
        reformat(expected);
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void selectionDoesNotFormatOtherStatementsOrSaveUnsavedText(Engine engine) {
        select(engine);
        String original = text("selection/before.slang");
        open(original);
        EdtTestUtil.runInEdtAndWait(() -> fixture.getEditor().getSelectionModel().setSelection(
                original.indexOf("float second"), original.indexOf("float third")));
        String expected = text("selection/after.slang");
        reformat(expected, true);
        EdtTestUtil.runInEdtAndWait(() -> {
            try {
                assertEquals(text("unsaved/on-disk.slang"),
                        Files.readString(Path.of(fixture.getFile().getVirtualFile().getPath())));
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void interfacesAndNestedGenericsKeepTheirDeclarationStructure(Engine engine) {
        select(engine);
        open(text("types/before.slang"));
        reformat(text("types/after.slang"));
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void spirvAssemblyRemainsByteForByteUnchanged(Engine engine) {
        select(engine);
        String assembly = withoutFinalNewline("assembly/preserved-block.slang");
        String source = text("assembly/before.slang");
        open(source);
        EdtTestUtil.runInEdtAndWait(() -> {
            new ReformatCodeProcessor(fixture.getProject(), fixture.getFile(), null, false).runWithoutProgress();
            UIUtil.dispatchAllInvocationEvents();
            assertTrue(fixture.getEditor().getDocument().getText().contains(assembly),
                    fixture.getEditor().getDocument().getText());
        });
    }

    @Test
    void noConfigurationUsesSlangSchemeNotHardcodedLlvm() {
        select(Engine.SLANGD);
        EdtTestUtil.runInEdtAndWait(() -> {
            settings.getIndentOptions(slanglsp.language.SlangFileType.INSTANCE).INDENT_SIZE = 3;
            settings.setRightMargin(SlangLanguage.INSTANCE, 90);
        });
        open(text("simple-function/before.slang"));
        reformat(text("simple-function/after.allman-three.slang"));
    }

    @Test
    void projectConfigurationTakesPrecedenceOverScheme() {
        select(Engine.SLANGD);
        EdtTestUtil.runInEdtAndWait(() -> configuration(".clang-format", text("configuration/external/.clang-format")));
        open(text("simple-function/before.slang"));
        reformat(text("simple-function/after.attach-two.slang"));
    }

    @Test
    void alreadyFormattedExternalDocumentDoesNotFallBackToNativeStyle() {
        select(Engine.SLANGD);
        EdtTestUtil.runInEdtAndWait(() -> configuration(".clang-format", text("configuration/external/.clang-format")));
        String formatted = text("no-op/before.slang");
        open(formatted);
        reformat(formatted);
        EdtTestUtil.runInEdtAndWait(() -> {
            var undo = UndoManager.getInstance(fixture.getProject());
            undo.undo(TextEditorProvider.getInstance().getTextEditor(fixture.getEditor()));
            assertEquals(text("unsaved/on-disk.slang"), fixture.getEditor().getDocument().getText(),
                    "An empty edit list must not create a format command or run the native formatter");
        });
    }

    @Test
    void explicitConfigurationOutsideShaderDirectoryIsRespected() {
        select(Engine.SLANGD);
        EdtTestUtil.runInEdtAndWait(() -> {
            var config = configuration("config with spaces/custom-format.yaml",
                    text("configuration/explicit/custom-format.yaml"));
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            state.clangFormatStyle = "file:" + config.getVirtualFile().getPath();
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
        });
        open(text("simple-function/before.slang"));
        reformat(text("simple-function/after.attach-three.slang"));
    }

    @Test
    void savedExplicitStyleAndFallbackSurviveTheNewEngineSelection() {
        select(Engine.SLANGD);
        EdtTestUtil.runInEdtAndWait(() -> {
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            state.clangFormatStyle = "file";
            state.clangFormatFallbackStyle = withoutFinalNewline("configuration/inline/three-spaces.yaml");
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
        });
        open(text("simple-function/before.slang"));
        reformat(text("simple-function/after.attach-three.slang"));
    }

    @Test
    void savedNamedStyleOverridesTheFallback() {
        select(Engine.SLANGD);
        EdtTestUtil.runInEdtAndWait(() -> {
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            state.clangFormatStyle = "Microsoft";
            state.clangFormatFallbackStyle = "LLVM";
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
        });
        open(text("simple-function/before.slang"));
        reformat(text("simple-function/after.allman-four.slang"));
    }

    @Test
    void customExecutablePathWithSpacesIsUsed() throws Exception {
        select(Engine.SLANGD);
        Path binary = Path.of(fixture.getTempDirPath(), "tools with spaces",
                Path.of(TestDependencies.clangFormat()).getFileName().toString());
        Files.createDirectories(binary.getParent());
        Files.copy(Path.of(TestDependencies.clangFormat()), binary, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(binary.toFile().setExecutable(true));
        EdtTestUtil.runInEdtAndWait(() ->
                settings.getCustomSettings(SlangCodeStyleSettings.class).CLANG_FORMAT_PATH = binary.toString());
        open(text("shader/before.slang"));
        reformat(text("shader/after.slangd.slang"));
    }

    @Test
    void bundledSelectionDoesNotSilentlyChangeExecutableSource() {
        select(Engine.SLANGD);
        EdtTestUtil.runInEdtAndWait(() -> {
            var custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
            custom.USE_CUSTOM_CLANG_FORMAT = false;
            custom.USE_BUNDLED_CLANG_FORMAT = true;
            custom.CLANG_FORMAT_PATH = fixture.getTempDirPath() + "/must-not-use-custom";
        });
        if (slanglsp.settings.BundledClangFormat.executable() == null) {
            assertFormattingFailsWithoutChangingText("clang");
        } else {
            open(text("shader/before.slang"));
            reformat(text("shader/after.slangd.slang"));
        }
    }

    @Test
    void missingCustomExecutableReportsFailureWithoutNativeFallback() {
        select(Engine.SLANGD);
        EdtTestUtil.runInEdtAndWait(() -> settings.getCustomSettings(SlangCodeStyleSettings.class)
                .CLANG_FORMAT_PATH = fixture.getTempDirPath() + "/missing-clang-format");
        assertFormattingFailsWithoutChangingText("missing-clang-format");
    }

    @Test
    void unavailableSlangdDoesNotFallBackToRawCppFormatting() {
        select(Engine.SLANGD);
        EdtTestUtil.runInEdtAndWait(() -> {
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            state.explicitSlangdLocation = fixture.getTempDirPath() + "/missing-server-directory";
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
            NativeServerSupport.disable(fixture.getProject());
        });
        assertFormattingFailsWithoutChangingText("server");
    }

    @Test
    void invalidConfigurationReportsFailureWithoutNativeFallback() {
        select(Engine.SLANGD);
        EdtTestUtil.runInEdtAndWait(() ->
                configuration(".clang-format", text("configuration/invalid/.clang-format")));
        assertFormattingFailsWithoutChangingText("NotAClangFormatOption");
    }

    private void assertFormattingFailsWithoutChangingText(String expectedProblem) {
        String source = text("simple-function/before.slang");
        open(source);
        EdtTestUtil.runInEdtAndWait(() -> {
            String stderr = captureFormattingErrors(() -> {
                new ReformatCodeProcessor(fixture.getProject(), fixture.getFile(), null, false).runWithoutProgress();
                UIUtil.dispatchAllInvocationEvents();
            });
            assertEquals(source, fixture.getEditor().getDocument().getText(),
                    "An unavailable selected formatter must not silently use a different engine");
            String reported = stderr + notifications.stream()
                    .map(notification -> notification.getTitle() + ": " + notification.getContent())
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(reported.toLowerCase(java.util.Locale.ROOT)
                            .contains(expectedProblem.toLowerCase(java.util.Locale.ROOT)),
                    "The specific failure must be reported, not treated as already formatted: " + reported);
        });
    }

    private void select(Engine engine) {
        EdtTestUtil.runInEdtAndWait(() -> {
            var state = new ProjectSettings.State();
            state.useLocalVcpkgSlangd = false;
            state.explicitSlangdLocation = engine == Engine.SLANGD
                    ? Path.of(TestDependencies.slangd()).getParent().toString()
                    : fixture.getTempDirPath() + "/no-server-required";
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
            var custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
            custom.USE_CLANG_FORMAT = engine == Engine.SLANGD;
            custom.USE_CUSTOM_CLANG_FORMAT = engine == Engine.SLANGD;
            custom.USE_BUNDLED_CLANG_FORMAT = false;
            custom.CLANG_FORMAT_PATH = engine == Engine.SLANGD
                    ? TestDependencies.clangFormat() : fixture.getTempDirPath() + "/no-executable-required";
            if (engine == Engine.SLANGD) {
                NativeServerSupport.enable(fixture.getProject());
            }
        });
    }

    private void open(String text) {
        EdtTestUtil.runInEdtAndWait(() -> {
            // Deliberately keep different text on disk: formatting must use the editor snapshot.
            var file = fixture.addFileToProject("nested/sample.slang", text("unsaved/on-disk.slang"));
            fixture.configureFromExistingVirtualFile(file.getVirtualFile());
            WriteCommandAction.runWriteCommandAction(fixture.getProject(),
                    () -> fixture.getEditor().getDocument().setText(text));
            PsiDocumentManager.getInstance(fixture.getProject()).commitAllDocuments();
            fixture.getEditor().getDocument().putUserData(
                    AsyncDocumentFormattingService.FORMAT_DOCUMENT_SYNCHRONOUSLY, true);
        });
    }

    private void reformat(String expected) {
        reformat(expected, false);
    }

    private void reformat(String expected, boolean selection) {
        EdtTestUtil.runInEdtAndWait(() -> {
            notifications.clear();
            String stderr = captureFormattingErrors(() -> {
                if (selection) {
                    new ReformatCodeProcessor(fixture.getFile(), fixture.getEditor().getSelectionModel()).runWithoutProgress();
                } else {
                    new ReformatCodeProcessor(fixture.getProject(), fixture.getFile(), null, false).runWithoutProgress();
                }
                // Force synchronous service execution via the document flag set in open(), then
                // drain the deferred undoable document update. No-op assertions also wait for completion.
                UIUtil.dispatchAllInvocationEvents();
            });
            assertEquals(expected, fixture.getEditor().getDocument().getText());
            assertTrue(stderr.isBlank(), stderr);
            var errors = notifications.stream()
                    .filter(notification -> notification.getType() != com.intellij.notification.NotificationType.INFORMATION)
                    .toList();
            assertTrue(errors.isEmpty(), errors.toString());
        });
    }

    private static String captureFormattingErrors(Runnable action) {
        // FormattingNotificationService routes errors straight to stderr in headless IDEA,
        // bypassing both project service replacements and Notifications.TOPIC.
        var previous = System.err;
        var output = new java.io.ByteArrayOutputStream();
        try (var stream = new java.io.PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(stream);
            action.run();
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            System.setErr(previous);
        }
    }

    private com.intellij.psi.PsiFile configuration(String path, String text) {
        var file = fixture.addFileToProject(path, text);
        var document = PsiDocumentManager.getInstance(fixture.getProject()).getDocument(file);
        assertNotNull(document);
        FileDocumentManager.getInstance().saveDocument(document);
        return file;
    }
}
