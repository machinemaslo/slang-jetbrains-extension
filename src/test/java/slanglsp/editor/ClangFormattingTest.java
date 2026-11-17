package slanglsp.editor;

import com.intellij.application.options.CodeStyle;
import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.formatting.service.FormattingService;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.util.TextRange;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.util.ui.UIUtil;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import org.junit.jupiter.api.Test;
import slanglsp.TestDependencies;
import slanglsp.settings.ProjectSettings;
import slanglsp.settings.ClangFormatStyle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static slanglsp.FormattingFixtures.text;

class ClangFormattingTest {
    @Test
    void manualFormattingAndUndoWorkWithoutLanguageServer() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var fixture = factory.createCodeInsightFixture(factory.createLightFixtureBuilder("ClangFormatting").getFixture(),
                new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());
                var definition = LanguageServersRegistry.getInstance().getServerDefinition("slanglsp.SlangLanguageServer");
                assertNotNull(definition, "Slang language server definition must be registered");
                definition.setEnabled(false, fixture.getProject());
                var settings = ProjectSettings.getInstance(fixture.getProject()).getState();
                settings.clangFormatLocation = TestDependencies.clangFormat();
                settings.clangFormatStyle = "file";
                settings.clangFormatFallbackStyle = "";
                ProjectSettings.getInstance(fixture.getProject()).loadState(settings);
                var styles = com.intellij.psi.codeStyle.CodeStyleSettingsManager.getInstance(fixture.getProject());
                var scheme = styles.createSettings();
                var formatter = scheme.getCustomSettings(slanglsp.settings.SlangCodeStyleSettings.class);
                formatter.USE_CLANG_FORMAT = true;
                formatter.USE_CUSTOM_CLANG_FORMAT = true;
                formatter.CLANG_FORMAT_PATH = TestDependencies.clangFormat();
                styles.setTemporarySettings(scheme);
                var file = fixture.addFileToProject("sample.slang", text("assignment/before.slang"));
                fixture.configureFromExistingVirtualFile(file.getVirtualFile());
            });
            EdtTestUtil.runInEdtAndWait(() -> {
                var file = fixture.getFile();
                var document = fixture.getEditor().getDocument();
                assertInstanceOf(ClangFormattingService.class, FormattingService.EP_NAME.getExtensionList().stream()
                        .filter(service -> service.canFormat(file)).findFirst().orElseThrow());
                WriteCommandAction.runWriteCommandAction(fixture.getProject(),
                        () -> document.setText(text("assignment/edited.slang")));
            });
            EdtTestUtil.runInEdtAndWait(() -> {
                var document = fixture.getEditor().getDocument();
                document.putUserData(AsyncDocumentFormattingService.FORMAT_DOCUMENT_SYNCHRONOUSLY, true);
                var service = new ClangFormattingService();
                WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> service.formatDocument(document,
                        List.of(new TextRange(0, document.getTextLength())),
                        FormattingContext.create(fixture.getFile(), CodeStyle.getSettings(fixture.getFile())), false, false));
                UIUtil.dispatchAllInvocationEvents();
                assertEquals(text("assignment/edited.after.slang"), document.getText());
                var undo = UndoManager.getInstance(fixture.getProject());
                undo.undo(TextEditorProvider.getInstance().getTextEditor(fixture.getEditor()));
                assertEquals(text("assignment/edited.slang"), document.getText());
                undo.redo(TextEditorProvider.getInstance().getTextEditor(fixture.getEditor()));
                assertEquals(text("assignment/edited.after.slang"), document.getText());
            });
            EdtTestUtil.runInEdtAndWait(() -> {
                var document = fixture.getEditor().getDocument();
                WriteCommandAction.runWriteCommandAction(fixture.getProject(),
                        () -> document.setText(text("assignment/third.slang")));
                com.intellij.psi.PsiDocumentManager.getInstance(fixture.getProject()).commitAllDocuments();
                new com.intellij.codeInsight.actions.ReformatCodeProcessor(
                        fixture.getProject(), fixture.getFile(), null, false).run();
                com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
                        "Manual formatting", () -> document.getText().equals(text("assignment/third.after.slang")), 10);
                UndoManager.getInstance(fixture.getProject()).undo(
                        TextEditorProvider.getInstance().getTextEditor(fixture.getEditor()));
                assertEquals(text("assignment/third.slang"), document.getText());
                var state = ProjectSettings.getInstance(fixture.getProject()).getState();
                state.clangFormatLocation = fixture.getTempDirPath() + "/missing-clang-format";
                ProjectSettings.getInstance(fixture.getProject()).loadState(state);
                CodeStyle.getSettings(fixture.getFile()).getCustomSettings(
                        slanglsp.settings.SlangCodeStyleSettings.class).USE_CLANG_FORMAT = false;
                assertFalse(new ClangFormattingService().canFormat(fixture.getFile()));
            });
        } finally {
            EdtTestUtil.runInEdtAndWait(() -> {
                NativeServerSupport.enable(fixture.getProject());
                com.intellij.psi.codeStyle.CodeStyleSettingsManager.getInstance(fixture.getProject()).dropTemporarySettings();
                fixture.tearDown();
            });
        }
    }

    @Test
    void respectsConfigurationFilesAndUtf8SelectionOffsets(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        // Process execution uses the platform's background executor.
        var fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("ClangFormatOptions").getFixture();
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            Path source = root.resolve("nested/sample.slang");
            Files.createDirectories(source.getParent());
            var options = new ClangFormat.Options(TestDependencies.clangFormat(), source, ClangFormatStyle.DEFAULT_STYLE);
            String body = text("direct-fallback/before.slang");
            assertTrue(ClangFormat.format(options, body, List.of(), handler -> {}).contains("f()\n{"));
            Files.writeString(root.resolve(".clang-format"), text("configuration/direct/.clang-format"));
            assertTrue(ClangFormat.format(options, body, List.of(), handler -> {}).contains("f() {"));
            String text = text("direct-selection/before.slang");
            int start = text.indexOf("float second");
            String output = ClangFormat.format(options, text, List.of(new TextRange(start, text.length())), handler -> {});
            assertTrue(output.contains("float first=1;"), output);
            assertTrue(output.contains("float second = 2;"), output);
            Files.writeString(root.resolve(".clang-format"), text("configuration/invalid/.clang-format"));
            assertThrows(java.io.IOException.class, () -> ClangFormat.format(options, body, List.of(), handler -> {}));
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }
}
