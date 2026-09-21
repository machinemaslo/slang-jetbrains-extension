package slanglsp;

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
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            String code = """
                    public float sharedValue = 1;
                    float shadow() { float sharedValue = 2; return sharedValue; }
                    float useGlobal() { return sharedValue + sin(1.0); }
                    StructuredBuffer<float> values;
                    """;
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
                assertNotNull(document);
                WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> {
                    int zero = document.getText().indexOf("0;");
                    document.replaceString(zero, zero + 1, "sharedValue");
                    PsiDocumentManager.getInstance(fixture.getProject()).commitDocument(document);
                });
            });
            String firstHover = SlangReadAction.compute(() -> new SlangDocumentationProvider().generateDoc(
                    files[0].findElementAt(code.indexOf("sharedValue")), null));
            assertNotNull(firstHover, "Documentation must connect the file before any navigation request");
            assertTrue(firstHover.contains("sharedValue"), firstHover);
            var scope = GlobalSearchScope.projectScope(fixture.getProject());
            var candidates = SlangUsageSearch.candidateFiles(fixture.getProject(), scope, "sharedValue");
            assertEquals(2, candidates.size(), "Comment-only files must not become search candidates");
            assertTrue(candidates.contains(files[1].getVirtualFile()), "Unsaved occurrence must be included");
            PsiElement declaration = SlangReadAction.compute(() -> files[0].findElementAt(code.indexOf("sharedValue")));
            var usages = new ArrayList<UsageInfo>();
            ProgressManager.getInstance().runProcess(() -> assertTrue(SlangUsageSearch.process(declaration, scope, usage -> {
                usages.add(usage);
                return true;
            })), new EmptyProgressIndicator());
            assertEquals(2, usages.size(), "Only the global use and the unsaved imported use should match");
            assertTrue(usages.stream().anyMatch(usage -> usage.getFile() == files[1]));
            assertTrue(usages.stream().anyMatch(usage -> usage.getFile() == files[0]
                    && usage.getNavigationOffset() == code.lastIndexOf("sharedValue")));

            PsiElement[] builtin = SlangReadAction.compute(() -> {
                PsiElement source = files[0].findElementAt(code.indexOf("sin("));
                assertNotNull(source);
                return new SlangGotoDeclarationHandler().definitions(source, code.indexOf("sin("));
            });
            assertTrue(builtin.length > 0, "sin must navigate into the standard library");
            var file = builtin[0].getContainingFile().getVirtualFile();
            assertInstanceOf(SlangBuiltinFiles.BuiltinFile.class, file);
            assertFalse(file.isWritable());
            assertFalse(new SlangClientFeatures().isEnabled(file));
            var service = fixture.getProject().getService(SlangBuiltinFiles.class);
            assertSame(file, service.resolve(((SlangBuiltinFiles.BuiltinFile) file).uri().toString()));
            assertTrue(SlangReadAction.compute(() -> builtin[0].getText().contains("sin")));

            // Exercise the same rendering hook used by both LSP hover and Ctrl-hover.
            String bufferDocs = SlangReadAction.compute(() -> new SlangDocumentationProvider()
                    .generateDoc(files[0].findElementAt(code.indexOf("StructuredBuffer")), null));
            assertNotNull(bufferDocs);
            assertTrue(bufferDocs.contains("read-only structured buffer"), bufferDocs);
            assertTrue(bufferDocs.contains("The element type of the buffer"), bufferDocs);
            String sineDocs = SlangReadAction.compute(() -> new SlangDocumentationProvider()
                    .generateDoc(files[0].findElementAt(code.indexOf("sin(")), null));
            assertNotNull(sineDocs);
            assertTrue(sineDocs.contains("The angle in radians"), sineDocs);

            var documented = new org.eclipse.lsp4j.MarkupContent(org.eclipse.lsp4j.MarkupKind.MARKDOWN,
                    "```\nstruct StructuredBuffer<T>\n```\n\nServer-provided explanation.\n\nDefined in core(21016)\n");
            String suppliedDocs = SlangReadAction.compute(() -> new SlangHoverFeature().getContent(documented, files[0]));
            assertTrue(suppliedDocs.contains("Server-provided explanation."));
            assertFalse(suppliedDocs.contains("read-only structured buffer"), "Do not duplicate newer server documentation");
        } finally {
            com.redhat.devtools.lsp4ij.LanguageServerManager.getInstance(fixture.getProject()).stop("slanglsp.SlangLanguageServer",
                    new com.redhat.devtools.lsp4ij.LanguageServerManager.StopOptions().setWillDisable(false));
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }
}
