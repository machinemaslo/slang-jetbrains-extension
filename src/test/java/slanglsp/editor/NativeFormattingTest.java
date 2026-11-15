package slanglsp.editor;

import com.intellij.formatting.service.FormattingService;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.TokenType;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import slanglsp.language.SlangLanguage;
import slanglsp.language.SlangLexer;
import slanglsp.settings.ProjectSettings;
import slanglsp.settings.SlangCodeStylePresets;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeFormattingTest {
    private CodeInsightTestFixture fixture;
    private CodeStyleSettings settings;

    @BeforeEach
    void setUp() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        fixture = factory.createCodeInsightFixture(factory.createLightFixtureBuilder("NativeFormatting").getFixture(),
                new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(() -> {
            fixture.setUp();
            ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());
            var definition = LanguageServersRegistry.getInstance().getServerDefinition("slanglsp.SlangLanguageServer");
            assertNotNull(definition);
            definition.setEnabled(false, fixture.getProject());
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            state.clangFormatLocation = fixture.getTempDirPath() + "/missing-clang-format";
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
            var manager = CodeStyleSettingsManager.getInstance(fixture.getProject());
            settings = manager.createSettings();
            new SlangCodeStylePresets.Slang().apply(settings);
            manager.setTemporarySettings(settings);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        EdtTestUtil.runInEdtAndWait(() -> {
            CodeStyleSettingsManager.getInstance(fixture.getProject()).dropTemporarySettings();
            NativeServerSupport.enable(fixture.getProject());
            fixture.tearDown();
        });
    }

    @Test
    void presetsAndPlatformModelAgreeWithBackgroundEngine() {
        String input = "float f(float x){if(x!=0){return x+1;}else{return -x;}}\n";
        String allman = """
                float f(float x)
                {
                    if (x != 0)
                    {
                        return x + 1;
                    }
                    else
                    {
                        return -x;
                    }
                }
                """;
        assertFormat(input, allman);
        new SlangCodeStylePresets.Java().apply(settings);
        assertFormat(input, """
                float f(float x) {
                    if (x != 0) {
                        return x + 1;
                    } else {
                        return -x;
                    }
                }
                """);
    }

    @Test
    void preservesTokensDirectivesCommentsGenericsAndIncompleteCode() {
        for (String source : List.of(
                "#define BODY(x) { x; } \\\n    + 1\n#if SOMETHING\nfloat f(){return 1;}\n#endif\n",
                "[shader(\"compute\")]\n[numthreads(1,1,1)]\nvoid main(){// keep  spaces\n/* keep\n * comment */float x=1;}\n",
                "struct Box<T>{T value;};\nBox<vector<float,4>> b;\n",
                "void f(){for(int i=0;i<10;i++){a[i]+=i;} a = b + ++c; a = b + +c; a = b - -c;}\n",
                "void f(){switch(x){case 1: x=2; break;default: break;}}\n",
                "float f(float value,\nfloat other){return value+other;}\n",
                "void f(){const char* s=\"{ a  b }\";a = x << 2;int* p=&x;}\n",
                "void f(){if(a)\nreturn a;\nreturn 0;\n",
                "void f(){float values[]={1,2,3};}\n")) {
            String formatted = NativeFormatting.format(source, settings);
            assertEquals(tokens(source), tokens(formatted), formatted);
            assertEquals(formatted, NativeFormatting.format(formatted, settings), "Must be idempotent:\n" + formatted);
            if (source.startsWith("#define")) assertTrue(formatted.startsWith("#define BODY(x) { x; } \\\n    + 1\n"), formatted);
            assertFormat(source, formatted);
        }
    }

    @Test
    void nativeQuickFixWorksWithoutExecutablesAndCanBeUndone() {
        EdtTestUtil.runInEdtAndWait(() -> {
            var file = fixture.addFileToProject("fix.slang", "float x=1;\n");
            fixture.configureFromExistingVirtualFile(file.getVirtualFile());
            fixture.enableInspections(new SlangFormattingInspection());
            assertInstanceOf(NativeFormattingService.class, FormattingService.EP_NAME.getExtensionList().stream()
                    .filter(service -> service.canFormat(file)).findFirst().orElseThrow());
            var annotator = new SlangFormattingAnnotator();
            var input = annotator.collectInformation(file);
            assertNotNull(input);
            assertNotNull(annotator.doAnnotate(input));
            assertTrue(fixture.doHighlighting().stream().anyMatch(info ->
                    slanglsp.SlangBundle.message("inspection.formatting.message").equals(info.getDescription())));
            new SlangFormattingAnnotator.Reformat().invoke(fixture.getProject(), fixture.getEditor(), file);
            var document = fixture.getEditor().getDocument();
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
                    "Native quick fix", () -> document.getText().equals("float x = 1;\n"), 10);
            var formattedInput = annotator.collectInformation(file);
            assertNotNull(formattedInput);
            assertNull(annotator.doAnnotate(formattedInput));
            UndoManager.getInstance(fixture.getProject()).undo(
                    TextEditorProvider.getInstance().getTextEditor(fixture.getEditor()));
            assertEquals("float x=1;\n", document.getText());
        });
    }

    @Test
    void wrappingAndTabsUseSlangSettings() {
        settings.setRightMargin(SlangLanguage.INSTANCE, 40);
        var indent = settings.getCommonSettings(SlangLanguage.INSTANCE).getIndentOptions();
        assertNotNull(indent);
        indent.USE_TAB_CHARACTER = true;
        indent.TAB_SIZE = 4;
        String source = "void f(){call(firstArgument, secondArgument, thirdArgument, fourthArgument);}\n";
        String formatted = NativeFormatting.format(source, settings);
        assertTrue(formatted.contains("\n\tcall("), formatted);
        assertTrue(formatted.contains(",\n"), formatted);
        assertEquals(formatted, NativeFormatting.format(formatted, settings));
        assertFormat(source, formatted);
    }

    @Test
    void selectionFormattingLeavesOtherStatementsAlone() {
        EdtTestUtil.runInEdtAndWait(() -> {
            String source = "float first=1;\nfloat second=2;\nfloat third=3;\n";
            var file = fixture.addFileToProject("selection.slang", source);
            int start = source.indexOf("float second");
            int end = source.indexOf("float third");
            WriteCommandAction.runWriteCommandAction(fixture.getProject(),
                    () -> CodeStyleManager.getInstance(fixture.getProject()).reformatText(file, start, end));
            assertEquals("float first=1;\nfloat second = 2;\nfloat third=3;\n", file.getText());
        });
    }

    private void assertFormat(String source, String expected) {
        assertEquals(expected, NativeFormatting.format(source, settings));
        EdtTestUtil.runInEdtAndWait(() -> {
            var file = fixture.addFileToProject("sample" + System.nanoTime() + ".slang", source);
            WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> {
                CodeStyleManager.getInstance(fixture.getProject()).reformat(file);
            });
            var document = PsiDocumentManager.getInstance(fixture.getProject()).getDocument(file);
            assertNotNull(document);
            assertEquals(expected, document.getText());
        });
    }

    private static List<String> tokens(String text) {
        var result = new ArrayList<String>();
        var lexer = new SlangLexer();
        lexer.start(text);
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenType() != TokenType.WHITE_SPACE)
                result.add(text.substring(lexer.getTokenStart(), lexer.getTokenEnd()));
            lexer.advance();
        }
        return result;
    }
}
