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

class UnusedSymbolsTest {
    @Test
    @Timeout(90)
    void findsUnusedDeclarationsUsingNativeServer() throws Exception {
        String executable = slanglsp.TestDependencies.slangd();
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
                        void accum(uint32_t* src, uint32_t src_count, uint32_t* dst)
                        {
                            uint32_t total = 0;
                            uint32_t unusedCounter;
                            for (uint32_t i = 0; i < src_count; i++)
                                total += src[i];
                            *dst = total;
                        }
                        [shader("compute")]
                        void pointerMain(uniform uint32_t* src, uniform uint32_t src_count, uniform uint32_t* dst)
                        {
                            uint32_t total = 0;
                            uint32_t unusedEntryCounter;
                            for (uint32_t i = 0; i < src_count; i++)
                                total += src[i];
                            *dst = total;
                        }
                        """);
                fixture.addFileToProject("consumer.slang", """
                        import unused;
                        ImportedType imported;
                        """);
                settings.additionalIncludePaths = List.of(file[0].getVirtualFile().getParent().getPath());
                ProjectSettings.getInstance(fixture.getProject()).loadState(settings);
                NativeServerSupport.enable(fixture.getProject());
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
                                    "unusedPublicGlobal", "unusedPublicFunction", "unusedCounter", "unusedEntryCounter")),
                        names.toString());

                for (String name :
                        List.of("UsedType", "ImportedType", "usedFunction", "usedLocal", "external", "main", "value",
                                "total", "i", "pointerMain")) {
                    assertFalse(names.contains(name), name + " in " + names);
                }
            }, new EmptyProgressIndicator());

            EdtTestUtil.runInEdtAndWait(() -> {
                fixture.configureFromExistingVirtualFile(file[0].getVirtualFile());

                var struct = fixture.doHighlighting().stream()
                        .filter(highlight -> highlight.getStartOffset() == input.text().indexOf("PrimaryHitGpu"))
                        .filter(highlight -> highlight.getDescription() != null
                                && highlight.getDescription().contains("PrimaryHitGpu"))
                        .findFirst()
                        .orElseThrow();

                assertEquals(com.intellij.openapi.editor.colors.CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES,
                        struct.forcedTextAttributesKey);
            });
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }
}
