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
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
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

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static slanglsp.FormattingFixtures.text;
import static slanglsp.FormattingFixtures.withoutFinalNewline;

class FormattingUndoTest {
    private static final String ORIGINAL = withoutFinalNewline("assignment/before.slang");
    private static final String EDITED = withoutFinalNewline("assignment/edited.slang");
    private static final String FORMATTED = text("assignment/edited.after.slang");
    private static final String FORMATTED_UNTERMINATED = withoutFinalNewline("assignment/edited.after.slang");

    private CodeInsightTestFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("SlangFormattingUndo").getFixture(), new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(() -> {
            fixture.setUp();
            // Undo and direct clang-format formatting do not use slangd. Keep pending editor
            // requests from starting a server whose asynchronous shutdown can outlive this fixture.
            NativeServerSupport.disable(fixture.getProject());
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        EdtTestUtil.runInEdtAndWait(() -> {
            com.intellij.psi.codeStyle.CodeStyleSettingsManager.getInstance(fixture.getProject()).dropTemporarySettings();
            fixture.tearDown();
        });
    }

    @Test
    void formattingHasItsOwnUndoAndRedoStep() {
        EdtTestUtil.runInEdtAndWait(() -> {
            Document document = open();
            edit(EDITED);

            var result = result(document);
            result.onTextReady(FORMATTED);
            assertEquals(EDITED, document.getText(), "Applying the result must leave the current action first");
            UIUtil.dispatchAllInvocationEvents();
            assertEquals(FORMATTED, document.getText());

            result(document).onTextReady(FORMATTED_UNTERMINATED);
            UIUtil.dispatchAllInvocationEvents();
            assertEquals(FORMATTED_UNTERMINATED, document.getText());

            undo();
            assertEquals(FORMATTED, document.getText(), "Consecutive formats must remain separate");
            undo();
            assertEquals(EDITED, document.getText(), "Undo format must preserve the preceding edit");
            undo();
            assertEquals(ORIGINAL, document.getText());

            redo();
            assertEquals(EDITED, document.getText());
            redo();
            assertEquals(FORMATTED, document.getText());
            redo();
            assertEquals(FORMATTED_UNTERMINATED, document.getText());
        });
    }

    @Test
    void unchangedAndEmptyResultsDoNotAddUndoSteps() {
        EdtTestUtil.runInEdtAndWait(() -> {
            Document document = open();
            edit(EDITED);

            result(document).onTextReady(document.getText());
            result(document).onTextReady(null);
            UIUtil.dispatchAllInvocationEvents();

            undo();
            assertEquals(ORIGINAL, document.getText());
        });
    }

    @Test
    void staleAndSupersededResultsDoNotOverwriteEdits() {
        EdtTestUtil.runInEdtAndWait(() -> {
            Document document = open();
            var stale = result(document);
            edit(EDITED);
            stale.onTextReady(withoutFinalNewline("assignment/after.slang"));
            UIUtil.dispatchAllInvocationEvents();
            assertEquals(EDITED, document.getText());

            var older = result(document);
            var newer = result(document);
            older.onTextReady(FORMATTED_UNTERMINATED);
            newer.onTextReady(FORMATTED);
            UIUtil.dispatchAllInvocationEvents();
            assertEquals(FORMATTED, document.getText());

            undo();
            assertEquals(EDITED, document.getText());
            undo();
            assertEquals(ORIGINAL, document.getText());
        });
    }

    @Test
    void cancellationBeforeOrAfterTheResponseDoesNotChangeTextOrAddUndoSteps() {
        EdtTestUtil.runInEdtAndWait(() -> {
            Document document = open();
            edit(EDITED);

            var cancelledBeforeResponse = result(document);
            cancelledBeforeResponse.cancel();
            cancelledBeforeResponse.onTextReady(FORMATTED);
            UIUtil.dispatchAllInvocationEvents();
            assertEquals(EDITED, document.getText());

            var cancelledBeforeApply = result(document);
            cancelledBeforeApply.onTextReady(FORMATTED);
            cancelledBeforeApply.cancel();
            UIUtil.dispatchAllInvocationEvents();
            assertEquals(EDITED, document.getText());

            undo();
            assertEquals(ORIGINAL, document.getText(), "Cancellation must not create an undo command");
        });
    }

    @Test
    @Timeout(90)
    void nativeFormattingHasSeparateUndoAndRedoSteps() {
        String clangFormat = slanglsp.TestDependencies.clangFormat();

        String original = text("undo/before.slang");
        String edited = text("undo/edited.slang");

        EdtTestUtil.runInEdtAndWait(() -> {
            ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());
            var styles = com.intellij.psi.codeStyle.CodeStyleSettingsManager.getInstance(fixture.getProject());
            var scheme = styles.createSettings();
            var formatter = scheme.getCustomSettings(slanglsp.settings.SlangCodeStyleSettings.class);
            formatter.USE_CLANG_FORMAT = true;
            formatter.USE_CUSTOM_CLANG_FORMAT = true;
            formatter.CLANG_FORMAT_PATH = clangFormat;
            styles.setTemporarySettings(scheme);

            open("format.slang", original);
        });

        EdtTestUtil.runInEdtAndWait(() -> {
            Document document = fixture.getEditor().getDocument();
            document.putUserData(
                    com.intellij.formatting.service.AsyncDocumentFormattingService.FORMAT_DOCUMENT_SYNCHRONOUSLY, true);
            edit(edited);
            var file = fixture.getFile();
            var context = FormattingContext.create(file, CodeStyle.getSettings(file));
            var service = new ClangFormattingService();

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
        return open("format.txt", ORIGINAL);
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

    private UndoableFormattingService.Result result(Document document) {
        var file = fixture.getFile();
        var context = FormattingContext.create(file, CodeStyle.getSettings(file));
        return new UndoableFormattingService.Result(new Request(context, document.getText()), document);
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
