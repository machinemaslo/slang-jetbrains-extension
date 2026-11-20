package slanglsp.editor.spellchecking

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.spellchecker.ApplicationDictionaryLayer
import com.intellij.spellchecker.ProjectDictionaryLayer
import com.intellij.spellchecker.SpellCheckerManager
import com.intellij.spellchecker.SpellCheckerManager.Companion.bundledDictionaries
import com.intellij.spellchecker.inspections.SpellCheckingInspection
import com.intellij.spellchecker.inspections.SpellCheckingInspection.SpellCheckingScope
import com.intellij.spellchecker.inspections.Splitter
import com.intellij.spellchecker.quickfixes.SaveTo
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.TokenConsumer
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import slanglsp.editor.NativeServerSupport

internal class SpellcheckingTest {
    @Test
    @Throws(Exception::class)
    fun checksCommentsAndStringsWithStandardDictionariesAndFixes() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture = factory.createCodeInsightFixture(factory.createLightFixtureBuilder("SlangSpelling").getFixture())
        runInEdtAndWait { fixture.setUp() }
        try {
            runInEdtAndWait {
                NativeServerSupport.disable(fixture.project)
                val source =
                    """
                        // mispellled slangd autodiff bindless
                        // StructuredBuffer RWStructuredBuffer ByteAddressBuffer RWByteAddressBuffer
                        // ConstantBuffer Texture2D SamplerState std430 std140 row_major column_major
                        /* mispellled */
                        string message = "hello\
                        mispellled";
                        float mispellled = 1;
                        
                        """
                        .trimIndent()
                fixture.configureByText("spelling.slang", source)
                val manager = SpellCheckerManager.getInstance(fixture.project)
                Assertions.assertTrue(bundledDictionaries.contains("/dictionaries/slang.dic"))
                for (word in
                    listOf(
                        "slangc",
                        "slangd",
                        "autodiff",
                        "bindless",
                        "spirv",
                        "structuredbuffer",
                        "rwstructuredbuffer",
                        "byteaddressbuffer",
                        "rwbyteaddressbuffer",
                        "constantbuffer",
                        "samplerstate",
                        "std")) {
                    Assertions.assertFalse(manager.hasProblem(word), word)
                }
                Assertions.assertTrue(manager.hasProblem("mispellled"))

                val comment = checkNotNull(fixture.file.findElementAt(source.indexOf("mispellled")))
                val strategy =
                    Assertions.assertInstanceOf(
                        TokenSpellcheckingStrategy::class.java, SpellcheckingStrategy.getSpellcheckingStrategy(comment))
                val typos =
                    typos(fixture.file, strategy, manager, SpellCheckingScope.Comments, SpellCheckingScope.Literals)
                Assertions.assertEquals(3, typos.size, typos.toString())
                for (typo in typos) {
                    Assertions.assertEquals("mispellled", typo.substring(source))
                }
                val fixes = strategy.getRegularFixes(comment, TextRange(3, 13), false, "mispellled")
                Assertions.assertTrue(fixes.any { it is SaveTo })

                val literalTypos = typos(fixture.file, strategy, manager, SpellCheckingScope.Literals)
                Assertions.assertEquals(1, literalTypos.size, literalTypos.toString())
                Assertions.assertTrue(typos(fixture.file, strategy, manager, SpellCheckingScope.Code).isEmpty())

                // Exercise the public SaveTo quick fix in both dictionary scopes.
                val projectLayer = ProjectDictionaryLayer(fixture.project)
                val appLayer = ApplicationDictionaryLayer
                for (layer in listOf(projectLayer, appLayer)) {
                    val word = if (layer === projectLayer) "slangprojectvocabulary" else "slangapplicationvocabulary"
                    Assertions.assertTrue(manager.hasProblem(word))
                    try {
                        fixture.configureByText("dictionary.slang", "// $word\n")
                        SaveTo.applyFix(fixture.project, fixture.file, TextRange.from(3, word.length), word, layer)
                        Assertions.assertFalse(manager.hasProblem(word))
                        Assertions.assertTrue(layer.dictionary.editableWords.contains(word))
                    } finally {
                        layer.dictionary.removeFromDictionary(word)
                        manager.fullConfigurationReload()
                    }
                }
            }
        } finally {
            runInEdtAndWait {
                NativeServerSupport.enable(fixture.project)
                fixture.tearDown()
            }
        }
    }

    private fun typos(
        file: PsiElement,
        strategy: SpellcheckingStrategy,
        manager: SpellCheckerManager,
        vararg scopes: SpellCheckingScope
    ): List<TextRange> = buildList {
        val scope = mutableSetOf<SpellCheckingScope?>(*scopes)
        val consumer =
            object : TokenConsumer() {
                override fun consumeToken(
                    element: PsiElement,
                    text: String,
                    useRename: Boolean,
                    offset: Int,
                    range: TextRange,
                    splitter: Splitter
                ) {
                    splitter.split(text, range) { word ->
                        if (manager.hasProblem(word.substring(text))) {
                            add(word.shiftRight(element.textOffset + offset))
                        }
                    }
                }
            }
        val leaves = PsiTreeUtil.collectElements(file) { it.firstChild == null }
        for (leaf in leaves) {
            SpellCheckingInspection.tokenize(strategy, leaf, consumer, scope)
        }
    }

    @Test
    @Throws(Exception::class)
    fun dictionaryIsSortedUniqueAndPackaged() {
        val resource = checkNotNull(BundledDictionary::class.java.getResourceAsStream("/dictionaries/slang.dic"))
        resource.bufferedReader().use { reader ->
            val words = reader.readLines()
            val wordPattern = Regex("[a-z]+")
            Assertions.assertTrue(words.isNotEmpty())
            Assertions.assertEquals(words.distinct().sorted(), words)
            Assertions.assertTrue(words.all(wordPattern::matches))
        }
    }
}
