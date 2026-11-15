package slanglsp.editor;

import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefinitionLocationsTest {
    @Test
    void correctsShiftedColumnsWithoutGuessingBetweenSameLineNames() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("SlangDefinitionLocations").getFixture(), new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(fixture::setUp);

        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                var file = fixture.addFileToProject("grid_traversal.slang",
                        "module grid_traversal;\npublic bool intersect_box(\n\tfloat3 origin) { return true; }\n");
                var expected = new Range(new Position(1, 12), new Position(1, 25));

                // Exercise URI and column repair together, including on Linux CI.
                String encodedUri = UsageSearch.fileUri(file.getVirtualFile()).toString()
                        .replace("grid_traversal.slang", "%67rid_traversal.slang");
                var location = new com.redhat.devtools.lsp4ij.usages.LocationData(
                        new org.eclipse.lsp4j.Location(encodedUri,
                                new Range(new Position(1, 1), new Position(1, 2))), null);
                var repaired = DefinitionLocations.normalize(file, 0, "intersect_box", java.util.List.of(location));
                assertEquals(1, repaired.size());
                assertEquals(UsageSearch.fileUri(file.getVirtualFile()).toString(), repaired.getFirst().location().getUri());
                assertEquals(expected, repaired.getFirst().location().getRange());
                var missing = new com.redhat.devtools.lsp4ij.usages.LocationData(
                        new org.eclipse.lsp4j.Location(encodedUri + ".missing", expected), null);
                assertTrue(DefinitionLocations.normalize(file, 0, "intersect_box", java.util.List.of(missing)).isEmpty());

                for (int column : new int[] {1, 2, 12, 200}) {
                    assertEquals(expected, DefinitionLocations.identifierRange(file, "intersect_box",
                            new Range(new Position(1, column), new Position(1, column + 13))));
                }

                assertNull(DefinitionLocations.identifierRange(file, "other",
                        new Range(new Position(1, 1), new Position(1, 6))));
                assertNull(DefinitionLocations.identifierRange(file, "intersect_box",
                        new Range(new Position(20, 1), new Position(20, 14))));

                var ambiguous = fixture.addFileToProject("ambiguous.slang",
                        "float f(); float f(float x); float f(float x, float y);");
                assertNull(DefinitionLocations.identifierRange(ambiguous, "f",
                        new Range(new Position(0, 1), new Position(0, 2))));
                assertEquals(new Range(new Position(0, 6), new Position(0, 7)),
                        DefinitionLocations.identifierRange(ambiguous, "f",
                                new Range(new Position(0, 6), new Position(0, 7))));

                var nonCode = fixture.addFileToProject("nonCode.slang", "// intersect_box\n\"intersect_box\"");
                assertNull(DefinitionLocations.identifierRange(nonCode, "intersect_box",
                        new Range(new Position(0, 3), new Position(0, 16))));
            });
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }
}
