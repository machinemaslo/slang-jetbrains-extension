package slanglsp.editor;

import com.intellij.application.options.CodeStyle;
import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.formatting.service.FormattingService;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.util.ui.UIUtil;
import com.redhat.devtools.lsp4ij.LanguageServerManager;
import com.redhat.devtools.lsp4ij.features.formatting.LSPFormattingSupport;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import slanglsp.settings.ProjectSettings;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FormattingUndoTest {
    private CodeInsightTestFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("SlangFormattingUndo").getFixture(), new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(() -> {
            fixture.setUp();
            // Light fixtures reuse a project. Pending editor requests from an earlier test can
            // start Slang while undo tests drain the event queue, even for a plain-text file.
            var settings = ProjectSettings.getInstance(fixture.getProject()).getState();
            settings.explicitSlangdLocation = Path.of(slanglsp.TestDependencies.slangd()).getParent().toString();
            settings.useLocalVcpkgSlangd = false;
            ProjectSettings.getInstance(fixture.getProject()).loadState(settings);
            NativeServerSupport.enable(fixture.getProject());
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        LanguageServerManager.getInstance(fixture.getProject())
                .stop("slanglsp.SlangLanguageServer",
                        new LanguageServerManager.StopOptions().setWillDisable(false));
        EdtTestUtil.runInEdtAndWait(fixture::tearDown);
    }

    @Test
    void formattingHasItsOwnUndoAndRedoStep() {
        EdtTestUtil.runInEdtAndWait(() -> {
            Document document = open();
            edit("float value=2;");

            var result = result(document);
            result.onTextReady("float value = 2;\n");
            assertEquals("float value=2;", document.getText(), "Applying the result must leave the current action first");
            UIUtil.dispatchAllInvocationEvents();
            assertEquals("float value = 2;\n", document.getText());

            result(document).onTextReady("float value = 2;");
            UIUtil.dispatchAllInvocationEvents();
            assertEquals("float value = 2;", document.getText());

            undo();
            assertEquals("float value = 2;\n", document.getText(), "Consecutive formats must remain separate");
            undo();
            assertEquals("float value=2;", document.getText(), "Undo format must preserve the preceding edit");
            undo();
            assertEquals("float value=1;", document.getText());

            redo();
            assertEquals("float value=2;", document.getText());
            redo();
            assertEquals("float value = 2;\n", document.getText());
            redo();
            assertEquals("float value = 2;", document.getText());
        });
    }

    @Test
    void unchangedAndEmptyResultsDoNotAddUndoSteps() {
        EdtTestUtil.runInEdtAndWait(() -> {
            Document document = open();
            edit("float value=2;");

            result(document).onTextReady(document.getText());
            result(document).onTextReady(null);
            UIUtil.dispatchAllInvocationEvents();

            undo();
            assertEquals("float value=1;", document.getText());
        });
    }

    @Test
    void staleAndSupersededResultsDoNotOverwriteEdits() {
        EdtTestUtil.runInEdtAndWait(() -> {
            Document document = open();
            var stale = result(document);
            edit("float value=2;");
            stale.onTextReady("float value = 1;");
            UIUtil.dispatchAllInvocationEvents();
            assertEquals("float value=2;", document.getText());

            var older = result(document);
            var newer = result(document);
            older.onTextReady("float value = 2;");
            newer.onTextReady("float value = 2;\n");
            UIUtil.dispatchAllInvocationEvents();
            assertEquals("float value = 2;\n", document.getText());

            undo();
            assertEquals("float value=2;", document.getText());
            undo();
            assertEquals("float value=1;", document.getText());
        });
    }

    @Test
    @Timeout(90)
    void nativeFormattingHasSeparateUndoAndRedoSteps() {
        String executable = slanglsp.TestDependencies.slangd();
        String clangFormat = slanglsp.TestDependencies.clangFormat();

        String original = """
                float value=1;
                float other=3;
                """;
        String edited = """
                float value=2;
                float other=3;
                """;

        EdtTestUtil.runInEdtAndWait(() -> {
            ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());
            var settings = ProjectSettings.getInstance(fixture.getProject()).getState();
            settings.explicitSlangdLocation = Path.of(executable).getParent().toString();
            settings.useLocalVcpkgSlangd = false;
            settings.enableFormatOnType = false;
            settings.clangFormatLocation = clangFormat;
            settings.clangFormatStyle = "LLVM";
            ProjectSettings.getInstance(fixture.getProject()).loadState(settings);

            open("format.slang", original);
        });

        DumbService.getInstance(fixture.getProject()).waitForSmartMode();
        var source = ReadActions.compute(fixture::getFile);
        new FormattingServerSupport(source).awaitReady();

        EdtTestUtil.runInEdtAndWait(() -> {
            Document document = fixture.getEditor().getDocument();
            edit(edited);
            var file = fixture.getFile();
            var context = FormattingContext.create(file, CodeStyle.getSettings(file));
            var service = new SlangFormattingService();

            String before = document.getText();
            var ranges = List.of(new TextRange(0, document.getTextLength()));
            WriteCommandAction.writeCommandAction(fixture.getProject())
                    .withName("Reformat")
                    .run(() -> service.formatDocument(document, ranges, context, false, false));
            UIUtil.dispatchAllInvocationEvents();
            assertTrue(document.getText().contains("float value = 2;"), document.getText());

            assertSame(ClangFormattingService.class, FormattingService.EP_NAME.getExtensionList().stream()
                    .filter(candidate -> candidate.canFormat(file))
                    .findFirst().orElseThrow().getClass());

            undo();
            assertEquals(before, document.getText());
            redo();
            assertTrue(document.getText().contains("float value = 2;"));
            undo();

            undo();
            assertEquals(original, document.getText());
        });
    }

    private Document open() {
        return open("format.txt", "float value=1;");
    }

    private Document open(String name, String text) {
        PsiFile file = fixture.addFileToProject(name, text);
        fixture.configureFromExistingVirtualFile(file.getVirtualFile());
        FileDocumentManager.getInstance().saveAllDocuments();
        return fixture.getEditor().getDocument();
    }

    private void edit(String text) {
        WriteCommandAction.writeCommandAction(fixture.getProject())
                .withName("Edit")
                .withGroupId(UUID.randomUUID().toString())
                .run(() -> fixture.getEditor().getDocument().setText(text));
    }

    private SlangFormattingService.Result result(Document document) {
        var file = fixture.getFile();
        var context = FormattingContext.create(file, CodeStyle.getSettings(file));
        return new SlangFormattingService.Result(new Request(context, document.getText()), document);
    }

    private void undo() {
        var editor = TextEditorProvider.getInstance().getTextEditor(fixture.getEditor());
        var undo = UndoManager.getInstance(fixture.getProject());
        assertTrue(undo.isUndoAvailable(editor));
        undo.undo(editor);
    }

    private void redo() {
        var editor = TextEditorProvider.getInstance().getTextEditor(fixture.getEditor());
        var undo = UndoManager.getInstance(fixture.getProject());
        assertTrue(undo.isRedoAvailable(editor));
        undo.redo(editor);
    }

    private static final class FormattingServerSupport extends LSPFormattingSupport {
        FormattingServerSupport(PsiFile file) {
            super(file);
        }

        void awaitReady() {
            var file = getFile();
            var servers = ReadActions.compute(() -> getLanguageServers(
                    file,
                    features -> features.getFormattingFeature().isEnabled(file),
                    features -> features.getFormattingFeature().isFormattingSupported(file)));

            assertFalse(ProgressIndicatorUtils.awaitWithCheckCanceled(servers).isEmpty());
        }
    }

    private record Request(FormattingContext context, String text) implements AsyncFormattingRequest {
        @Override
        public @NotNull String getDocumentText() {
            return text;
        }

        @Override
        public File getIOFile() {
            return null;
        }

        @Override
        public @NotNull List<TextRange> getFormattingRanges() {
            return List.of(new TextRange(0, text.length()));
        }

        @Override
        public boolean canChangeWhitespaceOnly() {
            return true;
        }

        @Override
        public boolean isQuickFormat() {
            return false;
        }

        @Override
        public @NotNull FormattingContext getContext() {
            return context;
        }

        @Override
        public void onTextReady(String updatedText) {
            assertNull(updatedText, "The platform must not also apply an undo-transparent update");
        }

        @Override
        public void onError(@NotNull String title, @NotNull String message) {
            fail(message);
        }

        @Override
        public void onError(@NotNull String title, @NotNull String message, int offset) {
            fail(message);
        }
    }
}
