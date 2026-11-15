package slanglsp.editor;

import com.intellij.spellchecker.ApplicationDictionaryLayer;
import com.intellij.spellchecker.ProjectDictionaryLayer;
import com.intellij.spellchecker.SpellCheckerManager;
import com.intellij.spellchecker.inspections.SpellCheckingInspection;
import com.intellij.spellchecker.quickfixes.SaveTo;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlangSpellcheckingTest {
    @Test
    void checksCommentsAndStringsWithStandardDictionariesAndFixes() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var fixture = factory.createCodeInsightFixture(factory.createLightFixtureBuilder("SlangSpelling").getFixture());
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                var definition = LanguageServersRegistry.getInstance().getServerDefinition("slanglsp.SlangLanguageServer");
                assertNotNull(definition);
                definition.setEnabled(false, fixture.getProject());
                var inspection = new SpellCheckingInspection();
                fixture.enableInspections(inspection);
                String source = """
                        // mispellled slangd autodiff bindless
                        // StructuredBuffer RWStructuredBuffer ByteAddressBuffer RWByteAddressBuffer
                        // ConstantBuffer Texture2D SamplerState std430 std140 row_major column_major
                        /* mispellled */
                        string message = "hello\\nmispellled";
                        float mispellled = 1;
                        """;
                fixture.configureByText("spelling.slang", source);
                var manager = SpellCheckerManager.getInstance(fixture.getProject());
                assertTrue(SpellCheckerManager.getBundledDictionaries().contains("/dictionaries/slang.dic"));
                for (String word : List.of("slangc", "slangd", "autodiff", "bindless", "spirv",
                        "structuredbuffer", "rwstructuredbuffer", "byteaddressbuffer",
                        "rwbyteaddressbuffer", "constantbuffer", "samplerstate", "std"))
                    assertFalse(manager.hasProblem(word), word);
                assertTrue(manager.hasProblem("mispellled"));

                var typos = fixture.doHighlighting().stream()
                        .filter(info -> info.getDescription() != null && info.getDescription().startsWith("Typo:"))
                        .toList();
                assertEquals(3, typos.size(), typos.toString());
                for (var typo : typos) {
                    assertEquals("mispellled", source.substring(typo.getStartOffset(), typo.getEndOffset()));
                }
                var comment = fixture.getFile().findElementAt(source.indexOf("mispellled"));
                assertNotNull(comment);
                var fixes = new SlangSpellcheckingStrategy().getRegularFixes(comment,
                        new com.intellij.openapi.util.TextRange(3, 13), false, "mispellled");
                assertTrue(Arrays.stream(fixes).anyMatch(SaveTo.class::isInstance));

                inspection.processComments = false;
                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(fixture.getProject()).restart();
                var literalTypos = fixture.doHighlighting().stream()
                        .filter(info -> info.getDescription() != null && info.getDescription().startsWith("Typo:")).toList();
                assertEquals(1, literalTypos.size(), literalTypos.toString());
                inspection.processLiterals = false;
                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(fixture.getProject()).restart();
                assertTrue(fixture.doHighlighting().stream()
                        .noneMatch(info -> info.getDescription() != null && info.getDescription().startsWith("Typo:")));

                // Exercise the same platform save path used by SaveTo, in both dictionary scopes.
                var projectLayer = new ProjectDictionaryLayer(fixture.getProject());
                var appLayer = ApplicationDictionaryLayer.INSTANCE;
                for (var layer : List.of(projectLayer, appLayer)) {
                    String word = layer == projectLayer ? "slangprojectvocabulary" : "slangapplicationvocabulary";
                    assertTrue(manager.hasProblem(word));
                    try {
                        manager.acceptWordAsCorrect$intellij_spellchecker(word, null, fixture.getProject(), layer);
                        assertFalse(manager.hasProblem(word));
                        assertTrue(layer.getDictionary().getEditableWords().contains(word));
                    } finally {
                        layer.getDictionary().removeFromDictionary(word);
                        manager.fullConfigurationReload();
                    }
                }
            });
        } finally {
            EdtTestUtil.runInEdtAndWait(() -> {
                NativeServerSupport.enable(fixture.getProject());
                fixture.tearDown();
            });
        }
    }

    @Test
    void dictionaryIsSortedUniqueAndPackaged() throws Exception {
        try (var resource = SlangDictionaryProvider.class.getResourceAsStream("/dictionaries/slang.dic")) {
            assertNotNull(resource);
            var words = new String(resource.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            assertFalse(words.isEmpty());
            assertEquals(words.stream().distinct().sorted().toList(), words);
            assertTrue(words.stream().allMatch(word -> word.matches("[a-z]+")));
        }
    }
}
