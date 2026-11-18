package slanglsp.editor;

import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import slanglsp.TestDependencies;
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
 * Public IDE contracts: native formatting needs no executables; external formatting must
 * use slangd with the selected clang-format executable. Expectations specify our routing,
 * configuration and edit application, not upstream Slang formatting correctness.
 */
@Timeout(90)
@ResourceLock(Resources.SYSTEM_ERR)
class FormattingContractTest {
    enum Engine { NATIVE, SLANGD }

    private CodeInsightTestFixture fixture;
    private CodeStyleSettings settings;
    private boolean opened;
    private final List<Notification> notifications = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder(
                        new com.intellij.testFramework.LightProjectDescriptor(), "FormattingContract").getFixture(),
                new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(() -> {
            fixture.setUp();
            NativeServerSupport.disable(fixture.getProject());
            ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            state.useLocalVcpkgSlangd = false;
            state.explicitSlangdLocation = fixture.getTempDirPath() + "/no-server";
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
            var manager = CodeStyleSettingsManager.getInstance(fixture.getProject());
            settings = manager.createSettings();
            new CodeStylePresets.Slang().apply(settings);
            manager.setTemporarySettings(settings);
            ApplicationManager.getApplication().getMessageBus().connect(fixture.getTestRootDisposable())
                    .subscribe(Notifications.TOPIC, new Notifications() {
                        @Override
                        public void notify(@NotNull Notification notification) {
                            notifications.add(notification);
                        }
                    });
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
    void synchronousFormattingUsesCurrentTextAndSupportsUndoRedo(Engine engine) {
        select(engine);
        // Explicit external configuration makes this an integration assertion, not an
        // assertion about clang-format's default style or Slang syntax support.
        if (engine == Engine.SLANGD) {
            configuration(".clang-format", text("configuration/external/.clang-format"));
        }
        String source = text("simple-function/before.slang");
        String expected = text(engine == Engine.NATIVE
                ? "simple-function/after.allman-four.slang" : "simple-function/after.attach-two.slang");
        open(source);
        reformat(expected, false);
        EdtTestUtil.runInEdtAndWait(() -> {
            var undo = UndoManager.getInstance(fixture.getProject());
            var editor = TextEditorProvider.getInstance().getTextEditor(fixture.getEditor());
            undo.undo(editor);
            assertEquals(source, fixture.getEditor().getDocument().getText());
            undo.redo(editor);
            assertEquals(expected, fixture.getEditor().getDocument().getText());
        });
        reformat(expected, false);
        EdtTestUtil.runInEdtAndWait(() -> {
            UndoManager.getInstance(fixture.getProject()).undo(
                    TextEditorProvider.getInstance().getTextEditor(fixture.getEditor()));
            assertEquals(source, fixture.getEditor().getDocument().getText(),
                    "A no-op formatting pass must not create an undo step");
        });
        assertDiskText(engine == Engine.NATIVE ? text("unsaved/on-disk.slang") : expected);
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void selectionPreservesNeighborsAndUsesUnicodeDocumentOffsets(Engine engine) {
        select(engine);
        String source = text("selection/before.slang");
        open(source);
        EdtTestUtil.runInEdtAndWait(() -> fixture.getEditor().getSelectionModel().setSelection(
                source.indexOf("static float second"), source.indexOf("static float third")));
        reformat(text("selection/after.slang"), true);
        assertDiskText(engine == Engine.NATIVE ? text("unsaved/on-disk.slang") : source);
    }

    @Test
    void customExecutablePathWithSpacesIsUsed() throws Exception {
        select(Engine.SLANGD);
        Path binary = Path.of(fixture.getTempDirPath(), "tools with spaces",
                Path.of(TestDependencies.clangFormat()).getFileName().toString());
        Files.createDirectories(binary.getParent());
        Files.copy(Path.of(TestDependencies.clangFormat()), binary, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(binary.toFile().setExecutable(true));
        EdtTestUtil.runInEdtAndWait(() -> settings.getCustomSettings(SlangCodeStyleSettings.class)
                .CLANG_FORMAT_PATH = binary.toString());
        configuration(".clang-format", text("configuration/external/.clang-format"));
        open(text("simple-function/before.slang"));
        reformat(text("simple-function/after.attach-two.slang"), false);
    }

    @Test
    void bundledSelectionDoesNotSearchPathWhenNoBundledExecutableIsAvailable() {
        select(Engine.SLANGD);
        EdtTestUtil.runInEdtAndWait(() -> {
            var custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
            custom.USE_CUSTOM_CLANG_FORMAT = false;
            custom.CLANG_FORMAT_PATH = "clang-format";
        });
        String bundledExecutable = slanglsp.settings.BundledClangFormat.executable();
        assertEquals(bundledExecutable, settings.getCustomSettings(SlangCodeStyleSettings.class).clangFormatExecutable());
        open(text("simple-function/before.slang"));
        if (bundledExecutable != null) {
            configuration(".clang-format", text("configuration/external/.clang-format"));
            reformat(text("simple-function/after.attach-two.slang"), false);
        } else {
            assertFormattingFailsWithoutChangingText("clang-format");
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
    void invalidConfigurationReportsFailureWithoutNativeFallback() {
        select(Engine.SLANGD);
        configuration(".clang-format", text("configuration/invalid/.clang-format"));
        assertFormattingFailsWithoutChangingText("NotAClangFormatOption");
    }

    @ParameterizedTest(name = "clang-format configuration: {0}")
    @ValueSource(strings = {"parent", "underscore", "nearest", "inherit"})
    void externalConfigurationDiscoveryAndPrecedence(String scenario) {
        select(Engine.SLANGD);
        String expected = "simple-function/after.attach-two.slang";
        configuration(scenario.equals("underscore") ? "_clang-format" : ".clang-format",
                text("configuration/external/.clang-format"));
        if (scenario.equals("nearest")) {
            configuration("nested/.clang-format", text("configuration/external-nearest/.clang-format"));
            expected = "simple-function/after.allman-four.slang";
        } else if (scenario.equals("inherit")) {
            configuration("nested/.clang-format", text("configuration/inherit/.clang-format"));
            expected = "simple-function/after.attach-four.slang";
        }
        open(text("simple-function/before.slang"));
        reformat(text(expected), false);
    }

    @Test
    void unavailableSlangdReportsFailureInsteadOfInvokingClangFormatDirectly() {
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
    void absentConfigurationUsesIdeScheme() {
        select(Engine.SLANGD);
        EdtTestUtil.runInEdtAndWait(() -> settings.getIndentOptions(
                slanglsp.language.SlangFileType.INSTANCE).INDENT_SIZE = 3);
        open(text("simple-function/before.slang"));
        reformat(text("simple-function/after.allman-three.slang"), false);
    }

    @Test
    void explicitConfigurationOutsideSourceDirectoryIsRespected() {
        select(Engine.SLANGD);
        String relative = "config with spaces/custom-format.yaml";
        configuration(relative, text("configuration/explicit/custom-format.yaml"));
        EdtTestUtil.runInEdtAndWait(() -> {
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            state.clangFormatStyle = "file:" + Path.of(fixture.getTempDirPath(), relative);
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
        });
        open(text("simple-function/before.slang"));
        reformat(text("simple-function/after.attach-three.slang"), false);
    }

    @org.junit.jupiter.api.RepeatedTest(12)
    void startupConfigurationKeepsUnderscoreStyle() {
        externalConfigurationDiscoveryAndPrecedence("underscore");
    }

    @ParameterizedTest(name = "server style: {0}")
    @ValueSource(strings = {"inline", "fallback", "named"})
    void configuredStylesAreForwardedToSlangd(String source) {
        select(Engine.SLANGD);
        EdtTestUtil.runInEdtAndWait(() -> {
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            String style = withoutFinalNewline("configuration/inline/three-spaces.yaml");
            state.clangFormatStyle = source.equals("inline") ? style
                    : source.equals("named") ? "Microsoft" : "file";
            state.clangFormatFallbackStyle = style;
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
        });
        open(text("simple-function/before.slang"));
        reformat(text(source.equals("named")
                ? "simple-function/after.allman-four.slang" : "simple-function/after.attach-three.slang"), false);
    }

    private void assertFormattingFailsWithoutChangingText(String problem) {
        String source = text("simple-function/before.slang");
        if (!opened) open(source);
        EdtTestUtil.runInEdtAndWait(() -> {
            String stderr = runFormatting(false);
            assertEquals(source, fixture.getEditor().getDocument().getText());
            String reported = stderr + notifications.stream()
                    .map(notification -> notification.getTitle() + ": " + notification.getContent())
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(reported.toLowerCase(java.util.Locale.ROOT)
                    .contains(problem.toLowerCase(java.util.Locale.ROOT)), reported);
        });
    }

    private void select(Engine engine) {
        EdtTestUtil.runInEdtAndWait(() -> {
            var custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
            custom.USE_CLANG_FORMAT = engine == Engine.SLANGD;
            custom.USE_CUSTOM_CLANG_FORMAT = engine == Engine.SLANGD;
            custom.CLANG_FORMAT_PATH = engine == Engine.SLANGD
                    ? TestDependencies.clangFormat() : fixture.getTempDirPath() + "/no-executable";
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            state.explicitSlangdLocation = engine == Engine.SLANGD
                    ? Path.of(TestDependencies.slangd()).getParent().toString()
                    : fixture.getTempDirPath() + "/no-server";
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
            if (engine == Engine.SLANGD) NativeServerSupport.enable(fixture.getProject());
        });
    }

    private void open(String source) {
        EdtTestUtil.runInEdtAndWait(() -> {
            var file = fixture.addFileToProject("nested/sample.slang", text("unsaved/on-disk.slang"));
            fixture.configureFromExistingVirtualFile(file.getVirtualFile());
            opened = true;
            WriteCommandAction.writeCommandAction(fixture.getProject()).withName("Edit")
                    .withGroupId(java.util.UUID.randomUUID().toString())
                    .run(() -> fixture.getEditor().getDocument().setText(source));
            PsiDocumentManager.getInstance(fixture.getProject()).commitAllDocuments();
            fixture.getEditor().getDocument().putUserData(
                    AsyncDocumentFormattingService.FORMAT_DOCUMENT_SYNCHRONOUSLY, true);
        });
    }

    private void assertDiskText(String expected) {
        EdtTestUtil.runInEdtAndWait(() -> {
            try {
                assertEquals(expected,
                        Files.readString(fixture.getFile().getVirtualFile().toNioPath()));
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    private void reformat(String expected, boolean selection) {
        EdtTestUtil.runInEdtAndWait(() -> {
            String stderr = runFormatting(selection);
            assertEquals(expected, fixture.getEditor().getDocument().getText(),
                    () -> stderr + notifications);
            assertTrue(stderr.isBlank(), stderr);
            assertTrue(notifications.stream().noneMatch(notification ->
                    notification.getType() == com.intellij.notification.NotificationType.ERROR),
                    notifications.toString());
        });
    }

    private String runFormatting(boolean selection) {
        notifications.clear();
        var previous = System.err;
        var output = new java.io.ByteArrayOutputStream();
        try (var stream = new java.io.PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(stream);
            if (selection) {
                new ReformatCodeProcessor(fixture.getFile(), fixture.getEditor().getSelectionModel()).runWithoutProgress();
            } else {
                new ReformatCodeProcessor(fixture.getProject(), fixture.getFile(), null, false).runWithoutProgress();
            }
            // Synchronous callers must see the edits when the processor returns.
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            System.setErr(previous);
        }
    }

    private void configuration(String path, String source) {
        EdtTestUtil.runInEdtAndWait(() -> {
            var file = fixture.addFileToProject(path, source);
            var document = PsiDocumentManager.getInstance(fixture.getProject()).getDocument(file);
            assertNotNull(document);
            FileDocumentManager.getInstance().saveDocument(document);
        });
    }
}
