package slanglsp.editor.navigation

import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import slanglsp.editor.navigation.DefinitionLocations.identifierRange
import slanglsp.editor.navigation.DefinitionLocations.normalize
import slanglsp.server.protocol.DocumentUris.fileUri

internal class DefinitionLocationsTest {
    @Test
    @Throws(Exception::class)
    fun correctsShiftedColumnsWithoutGuessingBetweenSameLineNames() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture =
            factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("SlangDefinitionLocations").getFixture(), TempDirTestFixtureImpl())
        runInEdtAndWait { fixture.setUp() }

        try {
            runInEdtAndWait {
                val file =
                    fixture.addFileToProject(
                        "grid_traversal.slang",
                        "module grid_traversal;\npublic bool intersect_box(\n\tfloat3 origin) { return true; }\n")
                val expected = Range(Position(1, 12), Position(1, 25))

                // Exercise URI and column repair together, including on Linux CI.
                val encodedUri =
                    fileUri(file.virtualFile).toString().replace("grid_traversal.slang", "%67rid_traversal.slang")
                val location = Location(encodedUri, Range(Position(1, 1), Position(1, 2)))
                val repaired = normalize(file, 0, "intersect_box", listOf(location))
                Assertions.assertEquals(1, repaired.size)
                Assertions.assertEquals(fileUri(file.virtualFile).toString(), repaired.first().uri)
                Assertions.assertEquals(expected, repaired.first().range)
                val missing = Location("$encodedUri.missing", expected)
                Assertions.assertTrue(normalize(file, 0, "intersect_box", listOf(missing)).isEmpty())

                for (column in intArrayOf(1, 2, 12, 200)) {
                    Assertions.assertEquals(
                        expected,
                        identifierRange(file, "intersect_box", Range(Position(1, column), Position(1, column + 13))))
                }

                Assertions.assertNull(identifierRange(file, "other", Range(Position(1, 1), Position(1, 6))))
                Assertions.assertNull(identifierRange(file, "intersect_box", Range(Position(20, 1), Position(20, 14))))

                val ambiguous =
                    fixture.addFileToProject(
                        "ambiguous.slang", "float f(); float f(float x); float f(float x, float y);")
                Assertions.assertNull(identifierRange(ambiguous, "f", Range(Position(0, 1), Position(0, 2))))
                Assertions.assertEquals(
                    Range(Position(0, 6), Position(0, 7)),
                    identifierRange(ambiguous, "f", Range(Position(0, 6), Position(0, 7))))

                val nonCode = fixture.addFileToProject("nonCode.slang", "// intersect_box\n\"intersect_box\"")
                Assertions.assertNull(identifierRange(nonCode, "intersect_box", Range(Position(0, 3), Position(0, 16))))
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }
}
