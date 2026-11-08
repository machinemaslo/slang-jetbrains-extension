package slanglsp.editor;

import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import slanglsp.settings.ProjectSettings;

import java.nio.file.Path;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class UnusedSymbolsTest {
    @SuppressWarnings("deprecation") // Platform test adapter for checking the emitted annotation attributes.
    @Test
    @Timeout(90)
    void findsUnusedDeclarationsUsingNativeServer() throws Exception {
        String executable = System.getenv("SLANGD_TEST_EXECUTABLE");
        assumeTrue(executable != null);
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("UnusedSymbols").getFixture(), new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(fixture::setUp);

        try {
            com.intellij.psi.PsiFile[] file = new com.intellij.psi.PsiFile[1];
            EdtTestUtil.runInEdtAndWait(() -> {
                ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());
                var settings = ProjectSettings.getInstance(fixture.getProject()).getState();
                settings.explicitSlangdLocation = Path.of(executable).getParent().toString();
                settings.useLocalVcpkgSlangd = false;
                file[0] = fixture.addFileToProject("unused.slang", """
                        public struct PrimaryHitGpu { public uint flags; }
                        struct UsedType { float field; }
                        float unusedGlobal;
                        public float unusedPublicGlobal;
                        extern float external;
                        public float unusedPublicFunction() { return 0; }
                        public struct ImportedType { float field; }
                        float unusedFunction() { float unusedLocal; float usedLocal = 1; return usedLocal; }
                        UsedType usedFunction() { UsedType value; return value; }
                        [shader("compute")]
                        [numthreads(1,1,1)]
                        void main() { usedFunction(); }
                        """);
                fixture.addFileToProject("consumer.slang", "import unused;\nImportedType imported;\n");
                settings.additionalIncludePaths = List.of(file[0].getVirtualFile().getParent().getPath());
                ProjectSettings.getInstance(fixture.getProject()).loadState(settings);
                FileDocumentManager.getInstance().saveAllDocuments();
            });

            var annotator = new UnusedSymbolsAnnotator();
            var input = ReadActions.compute(() -> annotator.collectInformation(file[0]));
            assertNotNull(input);

            ProgressManager.getInstance().runProcess(() -> {
                var result = annotator.doAnnotate(input);
                assertNotNull(result);
                var names = result.unused().stream().map(UnusedSymbolsAnnotator.Candidate::name).toList();
                assertTrue(names.containsAll(List.of("PrimaryHitGpu", "unusedGlobal", "unusedFunction", "unusedLocal",
                                   "unusedPublicGlobal", "unusedPublicFunction")),
                        names.toString());

                for (String name :
                        List.of("UsedType", "ImportedType", "usedFunction", "usedLocal", "external", "main", "value"))
                    assertFalse(names.contains(name), name + " in " + names);
                ReadActions.run(() -> {
                    var holder = new com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl(
                            new com.intellij.lang.annotation.AnnotationSession(file[0]), false);
                    holder.runAnnotatorWithContext(
                            file[0], (element, annotations) -> annotator.apply(file[0], result, annotations));
                    holder.assertAllAnnotationsCreated();
                    var struct =
                            holder.stream()
                                    .filter(annotation
                                            -> annotation.getStartOffset() == input.text().indexOf("PrimaryHitGpu"))
                                    .findFirst()
                                    .orElseThrow();
                    assertEquals(com.intellij.openapi.editor.colors.CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES,
                            struct.getTextAttributes());
                    assertTrue(struct.getMessage().contains("PrimaryHitGpu"));
                });
            }, new EmptyProgressIndicator());
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }
}
