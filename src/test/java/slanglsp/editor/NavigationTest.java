package slanglsp.editor;

import slanglsp.server.BuiltinFiles;
import slanglsp.server.ServerFactory;
import slanglsp.settings.ProjectSettings;

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

// Integration checks exercise LSP4IJ's experimental extension points.
class NavigationTest {
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
                var settings = ProjectSettings.getInstance(fixture.getProject()).getState();
                settings.explicitSlangdLocation = Path.of(executable).getParent().toString();
                settings.useLocalVcpkgSlangd = false;
                files[0] = fixture.addFileToProject("main.slang", code);
                files[1] = fixture.addFileToProject("edited.slang", "import main;\nfloat edited() { return 0; }\n");
                for (int i = 0; i < 25; i++) fixture.addFileToProject("unrelated" + i + ".slang", "// sharedValue\nfloat other = 0;\n");
                settings.additionalIncludePaths = List.of(files[0].getVirtualFile().getParent().getPath());
                ProjectSettings.getInstance(fixture.getProject()).loadState(settings);
                FileDocumentManager.getInstance().saveAllDocuments();
                var document = PsiDocumentManager.getInstance(fixture.getProject()).getDocument(files[1]);
                assertNotNull(document);
                WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> {
                    int zero = document.getText().indexOf("0;");
                    document.replaceString(zero, zero + 1, "sharedValue");
                    PsiDocumentManager.getInstance(fixture.getProject()).commitDocument(document);
                });
            });
            String firstHover = ReadActions.compute(() -> new SlangDocumentationProvider().generateDoc(
                    files[0].findElementAt(code.indexOf("sharedValue")), null));
            assertNotNull(firstHover, "Documentation must connect the file before any navigation request");
            assertTrue(firstHover.contains("sharedValue"), firstHover);
            var scope = GlobalSearchScope.projectScope(fixture.getProject());
            var candidates = UsageSearch.candidateFiles(fixture.getProject(), scope, "sharedValue");
            assertEquals(2, candidates.size(), "Comment-only files must not become search candidates");
            assertTrue(candidates.contains(files[1].getVirtualFile()), "Unsaved occurrence must be included");
            PsiElement declaration = ReadActions.compute(() -> files[0].findElementAt(code.indexOf("sharedValue")));
            var usages = new ArrayList<UsageInfo>();
            ProgressManager.getInstance().runProcess(() -> assertTrue(UsageSearch.process(declaration, scope, usage -> {
                usages.add(usage);
                return true;
            })), new EmptyProgressIndicator());
            assertEquals(2, usages.size(), "Only the global use and the unsaved imported use should match");
            assertTrue(usages.stream().anyMatch(usage -> usage.getFile() == files[1]));
            assertTrue(usages.stream().anyMatch(usage -> usage.getFile() == files[0]
                    && usage.getNavigationOffset() == code.lastIndexOf("sharedValue")));

            // Exercise more than one request window and downstream early termination.
            var doc = ReadActions.compute(() -> PsiDocumentManager.getInstance(fixture.getProject()).getDocument(files[0]));
            var offsets = java.util.Collections.nCopies(19, code.lastIndexOf("sharedValue"));
            var verified = new java.util.concurrent.atomic.AtomicInteger();
            ProgressManager.getInstance().runProcess(() -> assertTrue(UsageSearch.processDefinitions(
                    files[0], doc, offsets, () -> { }, (offset, locations) -> {
                        assertFalse(locations.isEmpty());
                        verified.incrementAndGet();
                        return true;
                    })), new EmptyProgressIndicator());
            assertEquals(19, verified.get());
            verified.set(0);
            ProgressManager.getInstance().runProcess(() -> assertFalse(UsageSearch.processDefinitions(
                    files[0], doc, offsets, () -> { }, (offset, locations) -> {
                        verified.incrementAndGet();
                        return false;
                    })), new EmptyProgressIndicator());
            assertEquals(1, verified.get());

            // A local with one reference should navigate directly, without a usage chooser.
            int localDeclaration = code.indexOf("sharedValue = 2");
            int localUsage = code.indexOf("return sharedValue") + "return ".length();
            var navigated = new java.util.concurrent.CountDownLatch(1);
            var caretListener = new com.intellij.openapi.editor.event.CaretListener() {
                @Override
                public void caretPositionChanged(@org.jetbrains.annotations.NotNull com.intellij.openapi.editor.event.CaretEvent event) {
                    if (fixture.getEditor().getCaretModel().getOffset() == localUsage) {
                        assertFalse(com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().isPopupActive(),
                                "Single-usage navigation must not open a chooser");
                        navigated.countDown();
                    }
                }
            };

            EdtTestUtil.runInEdtAndWait(() -> {
                fixture.configureFromExistingVirtualFile(files[0].getVirtualFile());
                fixture.getEditor().getCaretModel().moveToOffset(localDeclaration);
                fixture.getEditor().getCaretModel().addCaretListener(caretListener);
                ReadActions.run(() -> {
                    var local = files[0].findElementAt(localDeclaration);
                    assertNotNull(local);
                    new UsageNavigationTarget(local, fixture.getEditor()).navigate(true);
                });
            });

            try {
                assertTrue(navigated.await(10, java.util.concurrent.TimeUnit.SECONDS),
                        "Single usage should be opened directly");
            } finally {
                EdtTestUtil.runInEdtAndWait(() ->
                        fixture.getEditor().getCaretModel().removeCaretListener(caretListener));
            }

            PsiElement[] builtin = ReadActions.compute(() -> {
                PsiElement source = files[0].findElementAt(code.indexOf("sin("));
                assertNotNull(source);
                return new SlangGotoDeclarationHandler().definitions(source, code.indexOf("sin("));
            });
            assertTrue(builtin.length > 0, "sin must navigate into the standard library");
            var file = builtin[0].getContainingFile().getVirtualFile();
            assertInstanceOf(BuiltinFiles.BuiltinFile.class, file);
            assertFalse(file.isWritable());
            assertFalse(new ServerFactory().createClientFeatures().isEnabled(file));
            var service = fixture.getProject().getService(BuiltinFiles.class);
            assertSame(file, service.resolve(((BuiltinFiles.BuiltinFile) file).uri().toString()));
            assertTrue(ReadActions.compute(() -> builtin[0].getText().contains("sin")));

            // Exercise the same rendering hook used by both LSP hover and Ctrl-hover.
            String bufferDocs = ReadActions.compute(() -> new SlangDocumentationProvider()
                    .generateDoc(files[0].findElementAt(code.indexOf("StructuredBuffer")), null));
            assertNotNull(bufferDocs);
            assertTrue(bufferDocs.contains("read-only structured buffer"), bufferDocs);
            assertTrue(bufferDocs.contains("The element type of the buffer"), bufferDocs);
            String sineDocs = ReadActions.compute(() -> new SlangDocumentationProvider()
                    .generateDoc(files[0].findElementAt(code.indexOf("sin(")), null));
            assertNotNull(sineDocs);
            assertTrue(sineDocs.contains("The angle in radians"), sineDocs);

            var documented = new org.eclipse.lsp4j.MarkupContent(org.eclipse.lsp4j.MarkupKind.MARKDOWN,
                    "```\nstruct StructuredBuffer<T>\n```\n\nServer-provided explanation.\n\nDefined in core(21016)\n");
            String suppliedDocs = ReadActions.compute(() -> new ServerFactory().createClientFeatures().getHoverFeature().getContent(documented, files[0]));
            assertNotNull(suppliedDocs);
            assertTrue(suppliedDocs.contains("Server-provided explanation."));
            assertFalse(suppliedDocs.contains("read-only structured buffer"), "Do not duplicate newer server documentation");
        } finally {
            com.redhat.devtools.lsp4ij.LanguageServerManager.getInstance(fixture.getProject()).stop("slanglsp.SlangLanguageServer",
                    new com.redhat.devtools.lsp4ij.LanguageServerManager.StopOptions().setWillDisable(false));
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }
}
