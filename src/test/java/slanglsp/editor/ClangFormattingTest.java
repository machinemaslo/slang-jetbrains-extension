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
import slanglsp.settings.SlangCodeStyle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClangFormattingTest {
    @Test
    void liveChecksAndUndoWorkWithoutLanguageServer() throws Exception {
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
                fixture.enableInspections(new SlangFormattingInspection());
                var file = fixture.addFileToProject("sample.slang", "float value=1;\n");
                fixture.configureFromExistingVirtualFile(file.getVirtualFile());
            });
            var annotator = new SlangFormattingAnnotator();
            var input = ReadActions.compute(() -> annotator.collectInformation(fixture.getFile()));
            assertNotNull(input);
            assertNotNull(annotator.doAnnotate(input));
            EdtTestUtil.runInEdtAndWait(() -> {
                var file = fixture.getFile();
                var document = fixture.getEditor().getDocument();
                assertInstanceOf(ClangFormattingService.class, FormattingService.EP_NAME.getExtensionList().stream()
                        .filter(service -> service.canFormat(file)).findFirst().orElseThrow());
                WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> document.setText("float value=2;\n"));
            });
            assertNull(annotator.doAnnotate(input), "Do not diagnose a stale document snapshot");
            EdtTestUtil.runInEdtAndWait(() -> {
                var document = fixture.getEditor().getDocument();
                document.putUserData(AsyncDocumentFormattingService.FORMAT_DOCUMENT_SYNCHRONOUSLY, true);
                var service = new ClangFormattingService();
                WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> service.formatDocument(document,
                        List.of(new TextRange(0, document.getTextLength())),
                        FormattingContext.create(fixture.getFile(), CodeStyle.getSettings(fixture.getFile())), false, false));
                UIUtil.dispatchAllInvocationEvents();
                assertEquals("float value = 2;\n", document.getText());
                var undo = UndoManager.getInstance(fixture.getProject());
                undo.undo(TextEditorProvider.getInstance().getTextEditor(fixture.getEditor()));
                assertEquals("float value=2;\n", document.getText());
                undo.redo(TextEditorProvider.getInstance().getTextEditor(fixture.getEditor()));
                assertEquals("float value = 2;\n", document.getText());
            });
            var formatted = ReadActions.compute(() -> annotator.collectInformation(fixture.getFile()));
            assertNotNull(formatted);
            assertNull(annotator.doAnnotate(formatted));
            EdtTestUtil.runInEdtAndWait(() -> {
                var document = fixture.getEditor().getDocument();
                WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> document.setText("float value=3;\n"));
                com.intellij.psi.PsiDocumentManager.getInstance(fixture.getProject()).commitAllDocuments();
                var diagnostics = fixture.doHighlighting();
                assertTrue(diagnostics.stream().anyMatch(info ->
                        slanglsp.SlangBundle.message("inspection.formatting.message").equals(info.getDescription())));
                new SlangFormattingAnnotator.Reformat().invoke(fixture.getProject(), fixture.getEditor(), fixture.getFile());
                com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
                        "Formatting quick fix", () -> document.getText().equals("float value = 3;\n"), 10);
                UndoManager.getInstance(fixture.getProject()).undo(
                        TextEditorProvider.getInstance().getTextEditor(fixture.getEditor()));
                assertEquals("float value=3;\n", document.getText());
                fixture.disableInspections(new SlangFormattingInspection());
                assertNull(annotator.collectInformation(fixture.getFile()), "Respect the inspection profile");
                var state = ProjectSettings.getInstance(fixture.getProject()).getState();
                state.clangFormatLocation = fixture.getTempDirPath() + "/missing-clang-format";
                ProjectSettings.getInstance(fixture.getProject()).loadState(state);
                assertFalse(new ClangFormattingService().canFormat(fixture.getFile()));
                assertNull(annotator.collectInformation(fixture.getFile()));
            });
        } finally {
            EdtTestUtil.runInEdtAndWait(() -> {
                NativeServerSupport.enable(fixture.getProject());
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
            var options = new ClangFormat.Options(TestDependencies.clangFormat(), source, "file", SlangCodeStyle.DEFAULT_STYLE);
            String body = "float f(){return 0;}\n";
            assertTrue(ClangFormat.format(options, body, List.of(), handler -> {}).contains("f()\n{"));
            Files.writeString(root.resolve(".clang-format"), "BasedOnStyle: LLVM\nBreakBeforeBraces: Attach\n");
            assertTrue(ClangFormat.format(options, body, List.of(), handler -> {}).contains("f() {"));
            String text = "// é🙂\nfloat first=1;\nfloat second=2;\n";
            int start = text.indexOf("float second");
            String output = ClangFormat.format(options, text, List.of(new TextRange(start, text.length())), handler -> {});
            assertTrue(output.contains("float first=1;"), output);
            assertTrue(output.contains("float second = 2;"), output);
            Files.writeString(root.resolve(".clang-format"), "NotAClangFormatOption: true\n");
            assertThrows(java.io.IOException.class, () -> ClangFormat.format(options, body, List.of(), handler -> {}));
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }
}
