package slanglsp.editor.navigation

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import slanglsp.TestDependencies
import slanglsp.editor.NativeServerSupport
import slanglsp.editor.navigation.OverloadMatcher.isFunctionDeclaration
import slanglsp.editor.navigation.OverloadMatcher.signature
import slanglsp.editor.navigation.UsageCandidates.find
import slanglsp.editor.navigation.UsageSearch.isUnused
import slanglsp.editor.navigation.UsageSearch.process
import slanglsp.editor.rename.RenamePlan.Companion.collect

internal class OverloadMatcherTest {
    @Test
    fun comparesOnlySingleFunctionSignatures() {
        val integer = "```\nfunc calculate(int x) -> float\n```\nDocumentation"
        val decimal = "```slang\nfunc calculate(float x) -> float\n```\nDocumentation"

        Assertions.assertNotNull(signature(integer, "calculate"))
        Assertions.assertNotEquals(signature(integer, "calculate"), signature(decimal, "calculate"))
        Assertions.assertEquals(
            signature(integer, "calculate"),
            signature("```\nfunc calculate( int  x )\n -> float\n```\nDifferent documentation", "calculate"))
        Assertions.assertNull(signature(integer + "\n" + decimal, "calculate"))
        Assertions.assertNull(signature("The call calculate(int x) returns a float.", "calculate"))
        Assertions.assertNull(signature(integer, "other"))
    }

    @Test
    @Throws(Exception::class)
    fun distinguishesDeclarationsFromCallsOnTheSameLine() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture =
            factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("SlangOverloadSyntax").getFixture(), TempDirTestFixtureImpl())
        runInEdtAndWait { fixture.setUp() }

        try {
            runInEdtAndWait {
                val code = "float f(int x) { return f(1.0); } float f(float x) { return x; }"
                val file = fixture.addFileToProject("syntax.slang", code)
                val candidates = find(code, "f")

                Assertions.assertEquals(3, candidates.size)
                Assertions.assertTrue(isFunctionDeclaration(file, range(0, candidates[0], 1)))
                Assertions.assertFalse(isFunctionDeclaration(file, range(0, candidates[1], 1)))
                Assertions.assertTrue(isFunctionDeclaration(file, range(0, candidates[2], 1)))
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    @Test
    @Timeout(90)
    @Throws(Exception::class)
    fun resolvesSameLineOverloadsDespiteIncorrectColumns() {
        val executable = TestDependencies.slangd()

        val fixture = NativeServerSupport.isolatedFixture("SlangOverloadResolution")
        runInEdtAndWait { fixture.setUp() }

        try {
            val files = arrayOfNulls<PsiFile>(2)
            val declarations =
                """
                    module overloads;
                    public float calculate(int x) { return float(x); } public float calculate(float x) { return x * 2; }
                    
                    """
                    .trimIndent()
            val calls =
                """
                    import overloads;
                    float both() { return calculate(2) + calculate(2.0); }
                    float converted(half x) { return calculate(x); }
                    
                    """
                    .trimIndent()

            runInEdtAndWait {
                NativeServerSupport.configure(fixture, executable)

                files[0] = fixture.addFileToProject("overloads.slang", declarations)
                files[1] = fixture.addFileToProject("consumer.slang", calls)
                FileDocumentManager.getInstance().saveAllDocuments()
            }

            ProgressManager.getInstance()
                .runProcess(
                    {
                        val documents = PsiDocumentManager.getInstance(fixture.project)
                        val document = runReadActionBlocking { documents.getDocument(files[1]!!) }
                        Assertions.assertNotNull(document)

                        val declarationsDocument = runReadActionBlocking { checkNotNull(documents.getDocument(files[0]!!)) }

                        val declarationOffsets = find(declarations, "calculate")
                        val callOffsets = find(calls, "calculate")
                        Assertions.assertEquals(2, declarationOffsets.size)
                        Assertions.assertEquals(3, callOffsets.size)

                        for (i in callOffsets.indices) {
                            val callOffset = callOffsets[i]
                            val chosen = if (i == 0) 0 else 1
                            val expectedOffset = declarationOffsets[chosen]
                            val expected = runReadActionBlocking {
                                Range(
                                    getLsp4jPosition(declarationsDocument, expectedOffset),
                                    getLsp4jPosition(declarationsDocument, expectedOffset + "calculate".length),
                                )
                            }

                            val locations = UsageSearch.definitions(files[1]!!, checkNotNull(document), callOffset, verify = true)
                            Assertions.assertEquals(1, locations.size)
                            Assertions.assertEquals(expected, locations.first().range)

                            // Test both a nonsense column and one landing exactly on the OTHER overload.
                            val otherColumn = declarationOffsets[1 - chosen] - declarations.indexOf('\n') - 1

                            for (wrongColumn in intArrayOf(0, otherColumn)) {
                                val corrupted = Location(locations.first().uri, range(1, wrongColumn, "calculate".length))
                                val corrected =
                                    DefinitionLocations.normalize(
                                        files[1]!!, callOffset, "calculate", listOf(corrupted), verify = true)

                                Assertions.assertEquals(1, corrected.size)
                                Assertions.assertEquals(expected, corrected.first().range)
                            }
                        }
                        for ((i, offset) in declarationOffsets.withIndex()) {
                            val declaration = runReadActionBlocking { files[0]!!.findElementAt(offset) }
                            val usages = ArrayList<UsageInfo?>()

                            Assertions.assertTrue(
                                process(
                                    declaration,
                                    GlobalSearchScope.projectScope(fixture.project),
                                    Processor { e: UsageInfo? -> usages.add(e) }))
                            Assertions.assertEquals(if (i == 0) 1 else 2, usages.size)
                            Assertions.assertFalse(isUnused(declaration, null, batch = true))

                            val rename = collect(declaration, if (i == 0) "calculateInt" else "calculateFloat")
                            Assertions.assertEquals(if (i == 0) 2 else 3, rename.usages().size)
                        }
                    },
                    EmptyProgressIndicator())
        } finally {
            try {
                NativeServerSupport.stop(fixture.project)
            } finally {
                runInEdtAndWait { fixture.tearDown() }
            }
        }
    }

    companion object {
        private fun range(line: Int, column: Int, length: Int): Range {
            return Range(Position(line, column), Position(line, column + length))
        }
    }
}
