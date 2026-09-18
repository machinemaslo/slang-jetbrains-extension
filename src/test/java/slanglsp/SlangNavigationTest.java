package slanglsp;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.usageView.UsageInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SlangNavigationTest {
    @Test @Timeout(90)
    void builtinNavigationAndIndexedUsagesIncludeUnsavedEditsButExcludeShadowedNames() throws Exception {
        String executable = System.getenv("SLANGD_TEST_EXECUTABLE");
        assumeTrue(executable != null, "Set SLANGD_TEST_EXECUTABLE for native navigation verification");
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        CodeInsightTestFixture fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("SlangNavigationTest").getFixture(), new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(() -> fixture.setUp());
        try {
            String code = "public float sharedValue = 1;\n"
                    + "float shadow() { float sharedValue = 2; return sharedValue; }\n"
                    + "float useGlobal() { return sharedValue + sin(1.0); }\n";
            PsiFile[] files = new PsiFile[2];
            EdtTestUtil.runInEdtAndWait(() -> {
                com.intellij.openapi.roots.ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());
                var settings = SlangPersistentStateConfig.getInstance(fixture.getProject()).getState();
                settings.explicitSlangdLocation = Path.of(executable).getParent().toString();
                settings.useLocalVcpkgSlangd = false;
                files[0] = fixture.addFileToProject("main.slang", code);
                files[1] = fixture.addFileToProject("edited.slang", "import main;\nfloat edited() { return 0; }\n");
                for (int i = 0; i < 25; i++) fixture.addFileToProject("unrelated" + i + ".slang", "// sharedValue\nfloat other = 0;\n");
                settings.additionalIncludePaths = List.of(files[0].getVirtualFile().getParent().getPath());
                FileDocumentManager.getInstance().saveAllDocuments();
                var document = PsiDocumentManager.getInstance(fixture.getProject()).getDocument(files[1]);
                WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> {
                    int zero = document.getText().indexOf("0;");
                    document.replaceString(zero, zero + 1, "sharedValue");
                    PsiDocumentManager.getInstance(fixture.getProject()).commitDocument(document);
                });
            });
            var scope = GlobalSearchScope.projectScope(fixture.getProject());
            var candidates = SlangUsageSearch.candidateFiles(fixture.getProject(), scope, "sharedValue");
            assertEquals(2, candidates.size(), "Comment-only files must not become search candidates");
            assertTrue(candidates.contains(files[1].getVirtualFile()), "Unsaved occurrence must be included");
            PsiElement declaration = ReadAction.compute(() -> files[0].findElementAt(code.indexOf("sharedValue")));
            var usages = new ArrayList<UsageInfo>();
            ProgressManager.getInstance().runProcess(() -> assertTrue(SlangUsageSearch.process(declaration, scope, usage -> {
                usages.add(usage);
                return true;
            })), new EmptyProgressIndicator());
            assertEquals(2, usages.size(), "Only the global use and the unsaved imported use should match");
            assertTrue(usages.stream().anyMatch(usage -> usage.getFile() == files[1]));
            assertTrue(usages.stream().anyMatch(usage -> usage.getFile() == files[0]
                    && usage.getNavigationOffset() == code.lastIndexOf("sharedValue")));

            PsiElement[] builtin = ReadAction.compute(() -> new SlangGotoDeclarationHandler().definitions(
                    files[0].findElementAt(code.indexOf("sin(")), code.indexOf("sin(")));
            assertTrue(builtin.length > 0, "sin must navigate into the standard library");
            var file = builtin[0].getContainingFile().getVirtualFile();
            assertInstanceOf(SlangBuiltinFiles.BuiltinFile.class, file);
            assertFalse(file.isWritable());
            assertFalse(new SlangClientFeatures().isEnabled(file));
            var service = fixture.getProject().getService(SlangBuiltinFiles.class);
            assertSame(file, service.resolve(((SlangBuiltinFiles.BuiltinFile) file).uri().toString()));
            assertTrue(ReadAction.compute(() -> builtin[0].getText().contains("sin")));
        } finally {
            com.redhat.devtools.lsp4ij.LanguageServerManager.getInstance(fixture.getProject()).stop("slanglsp.SlangLanguageServer");
            EdtTestUtil.runInEdtAndWait(() -> fixture.tearDown());
        }
    }
}
