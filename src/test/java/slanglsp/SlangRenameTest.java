package slanglsp;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SlangRenameTest {
    @Test void rejectsKeywordsTypesAndNonIdentifiers() {
        for (String name : List.of("", "struct", "float4", "true", "1value", "a.b", "a b", "a\nb")) {
            assertFalse(SlangRenamePlan.validName(name), name);
        }
        for (String name : List.of("renamedValue", "_value2", "MyBuffer")) assertTrue(SlangRenamePlan.validName(name));
    }

    @Test @Timeout(90)
    void verifiedRenameIncludesUnsavedImportsPreservesShadowingAndSupportsUndo() throws Exception {
        String executable = System.getenv("SLANGD_TEST_EXECUTABLE");
        assumeTrue(executable != null, "Set SLANGD_TEST_EXECUTABLE for native rename verification");
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var fixture = factory.createCodeInsightFixture(factory.createLightFixtureBuilder("SlangRenameTest").getFixture(),
                new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            String main = """
                    public float sharedValue = 1;
                    float shadow() { float sharedValue = 2; return sharedValue; }
                    float useGlobal() { return sharedValue + sin(1.0); }
                    // sharedValue must remain in comments
                    """;
            String imported = "import main;\nfloat imported() { return sharedValue; }\n";
            PsiFile[] files = new PsiFile[3];
            Document[] docs = new Document[3];
            EdtTestUtil.runInEdtAndWait(() -> {
                com.intellij.openapi.roots.ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());
                var settings = SlangPersistentStateConfig.getInstance(fixture.getProject()).getState();
                settings.explicitSlangdLocation = Path.of(executable).getParent().toString();
                settings.useLocalVcpkgSlangd = false;
                files[0] = fixture.addFileToProject("main.slang", main);
                files[1] = fixture.addFileToProject("usage.slang", "import main;\nfloat imported() { return 0; }\n");
                files[2] = fixture.addFileToProject("other.slang", "float unrelated = 0;\n");
                settings.additionalIncludePaths = List.of(files[0].getVirtualFile().getParent().getPath());
                for (int i = 0; i < docs.length; i++) docs[i] = PsiDocumentManager.getInstance(fixture.getProject()).getDocument(files[i]);
                FileDocumentManager.getInstance().saveAllDocuments();
                WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> {
                    docs[1].setText(imported);
                    PsiDocumentManager.getInstance(fixture.getProject()).commitAllDocuments();
                });
            });
            // Invoking on either the declaration or an imported reference produces the same plan.
            var fromDeclaration = collect(files[0], main.indexOf("sharedValue"), "renamedValue");
            assertEquals(3, fromDeclaration.usages().length);
            var plan = collect(files[1], imported.indexOf("sharedValue"), "renamedValue");
            assertEquals(3, plan.usages().length);
            EdtTestUtil.runInEdtAndWait(() -> {
                fixture.configureFromExistingVirtualFile(files[1].getVirtualFile());
                fixture.getEditor().getCaretModel().moveToOffset(imported.indexOf("sharedValue"));
                var context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                        .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, fixture.getProject())
                        .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, fixture.getEditor())
                        .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, files[1]).build();
                assertTrue(com.intellij.refactoring.rename.RenameHandlerRegistry.getInstance().getRenameHandlers(context)
                        .stream().anyMatch(SlangRenameHandler.class::isInstance));
                var preview = SlangRenameHandler.showPreview(fixture.getProject(), plan);
                assertEquals(3, preview.getUsagesCount());
                preview.close();
            });
            EdtTestUtil.runInEdtAndWait(plan::apply);
            assertEquals(main.replace("public float sharedValue", "public float renamedValue")
                    .replace("return sharedValue +", "return renamedValue +"), docs[0].getText());
            assertEquals(imported.replace("sharedValue", "renamedValue"), docs[1].getText());
            EdtTestUtil.runInEdtAndWait(() -> {
                var undo = UndoManager.getInstance(fixture.getProject());
                assertTrue(undo.isUndoAvailable(null));
                undo.undo(null);
                PsiDocumentManager.getInstance(fixture.getProject()).commitAllDocuments();
            });
            assertEquals(main, docs[0].getText());
            assertEquals(imported, docs[1].getText());

            var stale = collect(files[1], imported.indexOf("sharedValue"), "renamedValue");
            EdtTestUtil.runInEdtAndWait(() -> {
                WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> docs[2].insertString(0, "// edited\n"));
                assertThrows(IllegalStateException.class, stale::apply);
            });
            assertEquals(main, docs[0].getText(), "A stale plan must not partially rename files");
            assertEquals(imported, docs[1].getText());

            var readOnly = collect(files[1], imported.indexOf("sharedValue"), "renamedValue");
            EdtTestUtil.runInEdtAndWait(() -> {
                docs[1].setReadOnly(true);
                try { assertThrows(IllegalStateException.class, readOnly::apply); }
                finally { docs[1].setReadOnly(false); }
            });
            assertEquals(main, docs[0].getText());
            assertThrows(IllegalArgumentException.class, () -> collect(files[0], main.indexOf("sin("), "mySin"));
            assertThrows(IllegalArgumentException.class, () -> collect(files[2], docs[2].getText().indexOf("unrelated"), "renamed"));
        } finally {
            com.redhat.devtools.lsp4ij.LanguageServerManager.getInstance(fixture.getProject()).stop("slanglsp.SlangLanguageServer",
                    new com.redhat.devtools.lsp4ij.LanguageServerManager.StopOptions().setWillDisable(false));
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }

    private static SlangRenamePlan collect(PsiFile file, int offset, String newName) {
        PsiElement element = SlangReadAction.compute(() -> file.findElementAt(offset));
        SlangRenamePlan[] result = new SlangRenamePlan[1];
        ProgressManager.getInstance().runProcess(() -> result[0] = SlangRenamePlan.collect(element, newName), new EmptyProgressIndicator());
        return result[0];
    }
}
