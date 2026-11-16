package slanglsp.editor;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import slanglsp.settings.NamingRule;
import slanglsp.settings.ProjectSettings;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class NamingInspectionTest {
    @Test
    void parameterDiscoverySkipsUnnamedTypesAndHandlesGenerics() {
        String text = "<element_type, let Count : int>(const MyType, MyType namedValue, float scalarValue : TEXCOORD0)";
        var candidates = new java.util.LinkedHashMap<Integer, NamingAnnotator.Candidate>();
        NamingParameters.collect(text, 0, text.length(), candidates);
        assertEquals(java.util.List.of("element_type", "Count", "namedValue", "scalarValue"),
                candidates.values().stream().map(NamingAnnotator.Candidate::name).toList());
        assertEquals(NamingRule.Category.GENERIC_PARAMETERS, candidates.values().iterator().next().category());
    }

    @Test
    @Timeout(90)
    void serverDeclarationsHighlightsProfileAndRenameAction() throws Exception {
        String executable = slanglsp.TestDependencies.slangd();
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("NamingInspection").getFixture(), new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            String source = """
                    struct TestStruct { float fieldValue; };
                    float testFunction(float inputValue) {
                        float localValue = inputValue;
                        return localValue;
                    }
                    float useValue() { return testFunction(1); }
                    [shader("compute")]
                    [numthreads(1, 1, 1)]
                    void computeMain() {}
                    """;
            PsiFile[] files = new PsiFile[1];
            var annotator = new NamingAnnotator();
            NamingAnnotator.Input[] input = new NamingAnnotator.Input[1];
            EdtTestUtil.runInEdtAndWait(() -> {
                com.intellij.openapi.roots.ModuleRootModificationUtil.addContentRoot(
                        fixture.getModule(), fixture.getTempDirPath());
                var state = ProjectSettings.getInstance(fixture.getProject()).getState();
                state.explicitSlangdLocation = Path.of(executable).getParent().toString();
                state.useLocalVcpkgSlangd = false;
                ProjectSettings.getInstance(fixture.getProject()).loadState(state);
                files[0] = fixture.addFileToProject("naming.slang", source);
                fixture.configureFromExistingVirtualFile(files[0].getVirtualFile());
                NativeServerSupport.enable(fixture.getProject());
                fixture.enableInspections(new InconsistentNamingInspection());
                com.intellij.application.options.CodeStyle.getSettings(files[0])
                        .getCustomSettings(slanglsp.settings.SlangCodeStyleSettings.class)
                        .setNamingRules(NamingRule.defaults(false));
                input[0] = annotator.collectInformation(files[0]);
                assertNotNull(input[0]);
            });
            NamingAnnotator.Result[] result = new NamingAnnotator.Result[1];
            ProgressManager.getInstance().runProcess(() -> result[0] = annotator.doAnnotate(input[0]),
                    new EmptyProgressIndicator());
            assertNotNull(result[0]);
            for (var expected : java.util.Map.of("TestStruct", NamingRule.Category.TYPES,
                    "fieldValue", NamingRule.Category.FIELDS, "testFunction", NamingRule.Category.FUNCTIONS,
                    "inputValue", NamingRule.Category.PARAMETERS, "localValue", NamingRule.Category.LOCALS).entrySet()) {
                assertTrue(result[0].candidates().stream().anyMatch(candidate ->
                        candidate.name().equals(expected.getKey()) && candidate.category() == expected.getValue()),
                        expected + ": " + result[0].candidates());
            }
            assertFalse(result[0].candidates().stream().anyMatch(c -> c.name().equals("computeMain")));
            EdtTestUtil.runInEdtAndWait(() -> {
                var holder = new AnnotationRecorder();
                annotator.apply(files[0], result[0], holder.holder());
                String unusedMessage = result[0].unusedMessages().get(source.indexOf("TestStruct"));
                assertNotNull(unusedMessage);
                var unusedNaming = holder.stream()
                        .filter(a -> a.getStartOffset() == source.indexOf("TestStruct")).findFirst().orElseThrow();
                assertNull(unusedNaming.getMessage(), "Unused takes precedence only in hover text");
                assertNull(unusedNaming.getTooltip(), "Naming must not own the unused hover");
                assertEquals(com.intellij.lang.annotation.HighlightSeverity.INFORMATION, unusedNaming.severity);
                assertEquals(2, unusedNaming.getEndOffset() - unusedNaming.getStartOffset());
                var unusedRename = unusedNaming.getQuickFixes().getFirst();
                assertEquals("Rename to 'test_struct'", unusedRename.quickFix.getText());
                assertEquals("TestStruct".length(), unusedRename.textRange.getLength());
                assertTrue(unusedRename.quickFix.isAvailable(fixture.getProject(), fixture.getEditor(), files[0]));
                var warning = holder.stream().filter(a -> a.getStartOffset() == source.indexOf("testFunction"))
                        .findFirst().orElseThrow();
                assertEquals(2, warning.getEndOffset() - warning.getStartOffset());
                assertEquals("Name 'testFunction' does not match rule 'Global functions'. Suggested name is 'test_function'.",
                        warning.getMessage());
                var fix = warning.getQuickFixes().getFirst();
                assertEquals("Rename to 'test_function'", fix.quickFix.getText());
                assertEquals("testFunction".length(), fix.textRange.getLength());
                assertTrue(fix.quickFix.isAvailable(fixture.getProject(), fixture.getEditor(), files[0]));
                fix.quickFix.invoke(fixture.getProject(), fixture.getEditor(), files[0]);
                var views = com.intellij.usages.UsageViewManager.getInstance(fixture.getProject());
                PlatformTestUtil.waitWithEventsDispatching("Naming rename applied",
                        () -> fixture.getEditor().getDocument().getText().contains("test_function"), 30);
                assertNull(views.getSelectedUsageView(), "Ordinary naming fixes must not open a preview");
                assertEquals(source.replace("testFunction", "test_function"), fixture.getEditor().getDocument().getText());
                com.intellij.openapi.command.undo.UndoManager.getInstance(fixture.getProject()).undo(null);
                assertEquals(source, fixture.getEditor().getDocument().getText());

                var profile = com.intellij.profile.codeInspection.InspectionProfileManager
                        .getInstance(fixture.getProject()).getCurrentProfile();
                profile.setToolEnabled(InconsistentNamingInspection.ID, false, fixture.getProject());
                assertNull(annotator.collectInformation(files[0]));
                profile.setToolEnabled(InconsistentNamingInspection.ID, true, fixture.getProject());
            });
        } finally {
            com.redhat.devtools.lsp4ij.LanguageServerManager.getInstance(fixture.getProject())
                    .stop("slanglsp.SlangLanguageServer",
                            new com.redhat.devtools.lsp4ij.LanguageServerManager.StopOptions().setWillDisable(false));
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }
}
