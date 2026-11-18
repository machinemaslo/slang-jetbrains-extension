package slanglsp.editor;

import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.testFramework.EdtTestUtil;
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
        var fixture = NativeServerSupport.isolatedFixture("UnusedSymbols");
        EdtTestUtil.runInEdtAndWait(fixture::setUp);

        try {
            com.intellij.psi.PsiFile[] file = new com.intellij.psi.PsiFile[1];
            EdtTestUtil.runInEdtAndWait(() -> {
                fixture.enableInspections(new UnusedSymbolsInspection(), new InconsistentNamingInspection());
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
                assertNull(result.failure(), "Batch analysis must work before the file is opened");
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
            var removalInput = ReadActions.compute(() -> annotator.collectForEditor(file[0]));
            UnusedSymbolsAnnotator.Result[] removal = new UnusedSymbolsAnnotator.Result[1];
            ProgressManager.getInstance().runProcess(() -> removal[0] = annotator.doAnnotate(removalInput),
                    new EmptyProgressIndicator());
            assertNotNull(removal[0]);
            EdtTestUtil.runInEdtAndWait(() -> {
                var holder = new AnnotationRecorder();
                annotator.apply(file[0], removal[0], holder.holder());
                int offset = removalInput.text().indexOf("unusedFunction");
                var warning = holder.stream().filter(a -> a.getStartOffset() == offset).findFirst().orElseThrow();
                assertEquals("Function 'unusedFunction' is never used", warning.getMessage());
                var fix = warning.getQuickFixes().getFirst().quickFix;
                assertEquals("Remove unused function", fix.getText());
                assertTrue(fix.isAvailable(fixture.getProject(), fixture.getEditor(), file[0]));
                fix.invoke(fixture.getProject(), fixture.getEditor(), file[0]);
                com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Remove unused function",
                        () -> !fixture.getEditor().getDocument().getText().contains("float unusedFunction()"), 30);
                assertFalse(fixture.getEditor().getDocument().getText().contains("float unusedLocal;"));
                assertTrue(fixture.getEditor().getDocument().getText().contains("UsedType usedFunction()"));
                com.intellij.openapi.command.undo.UndoManager.getInstance(fixture.getProject())
                        .undo(com.intellij.openapi.fileEditor.FileEditorManager.getInstance(fixture.getProject())
                                .getSelectedEditor(file[0].getVirtualFile()));
                assertEquals(removalInput.text(), fixture.getEditor().getDocument().getText());
                assertFalse(fix.isAvailable(fixture.getProject(), fixture.getEditor(), file[0]),
                        "A stale removal fix must not mutate a changed document");
            });
        } finally {
            try {
                NativeServerSupport.stop(fixture.getProject());
            } finally {
                EdtTestUtil.runInEdtAndWait(fixture::tearDown);
            }
        }
    }
}
