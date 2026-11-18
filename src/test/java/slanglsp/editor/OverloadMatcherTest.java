package slanglsp.editor;

import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.usages.LocationData;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import slanglsp.settings.ProjectSettings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OverloadMatcherTest {
    @Test
    void comparesOnlySingleFunctionSignatures() {
        String integer = "```\nfunc calculate(int x) -> float\n```\nDocumentation";
        String decimal = "```slang\nfunc calculate(float x) -> float\n```\nDocumentation";

        assertNotNull(OverloadMatcher.signature(integer, "calculate"));
        assertNotEquals(OverloadMatcher.signature(integer, "calculate"),
                OverloadMatcher.signature(decimal, "calculate"));
        assertEquals(OverloadMatcher.signature(integer, "calculate"),
                OverloadMatcher.signature(
                        "```\nfunc calculate( int  x )\n -> float\n```\nDifferent documentation", "calculate"));
        assertNull(OverloadMatcher.signature(integer + "\n" + decimal, "calculate"));
        assertNull(OverloadMatcher.signature("The call calculate(int x) returns a float.", "calculate"));
        assertNull(OverloadMatcher.signature(integer, "other"));
    }

    @Test
    void distinguishesDeclarationsFromCallsOnTheSameLine() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("SlangOverloadSyntax").getFixture(), new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(fixture::setUp);

        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                String code = "float f(int x) { return f(1.0); } float f(float x) { return x; }";
                var file = fixture.addFileToProject("syntax.slang", code);
                var candidates = UsageCandidates.find(code, "f");

                assertEquals(3, candidates.size());
                assertTrue(OverloadMatcher.isFunctionDeclaration(file, range(0, candidates.get(0), 1)));
                assertFalse(OverloadMatcher.isFunctionDeclaration(file, range(0, candidates.get(1), 1)));
                assertTrue(OverloadMatcher.isFunctionDeclaration(file, range(0, candidates.get(2), 1)));
            });
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }

    @Test
    @Timeout(90)
    void resolvesSameLineOverloadsDespiteIncorrectColumns() throws Exception {
        String executable = slanglsp.TestDependencies.slangd();

        var fixture = NativeServerSupport.isolatedFixture("SlangOverloadResolution");
        EdtTestUtil.runInEdtAndWait(fixture::setUp);

        try {
            PsiFile[] files = new PsiFile[2];
            String declarations = """
                    module overloads;
                    public float calculate(int x) { return float(x); } public float calculate(float x) { return x * 2; }
                    """;
            String calls = """
                    import overloads;
                    float both() { return calculate(2) + calculate(2.0); }
                    float converted(half x) { return calculate(x); }
                    """;

            EdtTestUtil.runInEdtAndWait(() -> {
                ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());

                var settings = ProjectSettings.getInstance(fixture.getProject()).getState();
                settings.explicitSlangdLocation = Path.of(executable).getParent().toString();
                settings.useLocalVcpkgSlangd = false;
                settings.additionalIncludePaths = List.of(fixture.getTempDirPath());
                ProjectSettings.getInstance(fixture.getProject()).loadState(settings);
                NativeServerSupport.enable(fixture.getProject());

                files[0] = fixture.addFileToProject("overloads.slang", declarations);
                files[1] = fixture.addFileToProject("consumer.slang", calls);
                FileDocumentManager.getInstance().saveAllDocuments();
            });

            ProgressManager.getInstance().runProcess(() -> {
                var document = ReadActions.compute(() -> LSPIJUtils.getDocument(files[1]));
                assertNotNull(document);

                var declarationsDocument = ReadActions.compute(() -> LSPIJUtils.getDocument(files[0]));
                assertNotNull(declarationsDocument);

                var declarationOffsets = UsageCandidates.find(declarations, "calculate");
                var callOffsets = UsageCandidates.find(calls, "calculate");
                assertEquals(2, declarationOffsets.size());
                assertEquals(3, callOffsets.size());

                for (int i = 0; i < callOffsets.size(); i++) {
                    int callOffset = callOffsets.get(i);
                    int chosen = i == 0 ? 0 : 1;
                    int expectedOffset = declarationOffsets.get(chosen);
                    Range expected = ReadActions.compute(() -> LSPIJUtils.toRange(
                            new com.intellij.openapi.util.TextRange(
                                    expectedOffset, expectedOffset + "calculate".length()),
                            declarationsDocument));

                    var locations = UsageSearch.definitions(files[1], document, callOffset);
                    assertEquals(1, locations.size());
                    assertEquals(expected, locations.getFirst().location().getRange());

                    // Test both a nonsense column and one landing exactly on the OTHER overload.
                    int otherColumn = declarationOffsets.get(1 - chosen) - declarations.indexOf('\n') - 1;

                    for (int wrongColumn : new int[] {0, otherColumn}) {
                        var original = locations.getFirst();
                        var corrupted = new LocationData(
                                new Location(original.location().getUri(), range(1, wrongColumn, "calculate".length())),
                                original.languageServer());
                        var corrected = DefinitionLocations.normalize(
                                files[1], callOffset, "calculate", List.of(corrupted));

                        assertEquals(1, corrected.size());
                        assertEquals(expected, corrected.getFirst().location().getRange());
                    }
                }

                for (int i = 0; i < declarationOffsets.size(); i++) {
                    int offset = declarationOffsets.get(i);
                    var declaration = ReadActions.compute(() -> files[0].findElementAt(offset));
                    var usages = new ArrayList<com.intellij.usageView.UsageInfo>();

                    assertTrue(UsageSearch.process(
                            declaration, GlobalSearchScope.projectScope(fixture.getProject()), usages::add));
                    assertEquals(i == 0 ? 1 : 2, usages.size());
                    assertFalse(UsageSearch.isUnused(declaration));

                    var rename = RenamePlan.collect(declaration, i == 0 ? "calculateInt" : "calculateFloat");
                    assertEquals(i == 0 ? 2 : 3, rename.usages().length);
                }
            }, new EmptyProgressIndicator());
        } finally {
            try {
                NativeServerSupport.stop(fixture.getProject());
            } finally {
                EdtTestUtil.runInEdtAndWait(fixture::tearDown);
            }
        }
    }

    private static Range range(int line, int column, int length) {
        return new Range(new Position(line, column), new Position(line, column + length));
    }
}
