package slanglsp.editor;

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
    void manualFormattingWorksWithoutExecutablesAndCanBeUndone() {
        EdtTestUtil.runInEdtAndWait(() -> {
            var file = fixture.addFileToProject("fix.slang", "float x=1;\n");
            fixture.configureFromExistingVirtualFile(file.getVirtualFile());
            assertInstanceOf(com.intellij.formatting.service.CoreFormattingService.class,
                    com.intellij.formatting.service.FormattingServiceUtil.findService(file, true, false));
            new com.intellij.codeInsight.actions.ReformatCodeProcessor(fixture.getProject(), file, null, false).run();
            var document = fixture.getEditor().getDocument();
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
                    "Manual formatting", () -> document.getText().equals("float x = 1;\n"), 10);
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
    void braceAndSpacingOptionsControlActualFormatting() {
        var custom = settings.getCustomSettings(slanglsp.settings.SlangCodeStyleSettings.class);
        custom.TYPE_BRACES = 1;
        custom.FUNCTION_BRACES = 2;
        custom.CONTROL_BRACES = 1;
        var common = settings.getCommonSettings(SlangLanguage.INSTANCE);
        common.ELSE_ON_NEW_LINE = false;
        common.SPACE_BEFORE_IF_PARENTHESES = false;
        common.SPACE_AROUND_ADDITIVE_OPERATORS = false;
        common.SPACE_AROUND_EQUALITY_OPERATORS = false;
        common.SPACE_AROUND_ASSIGNMENT_OPERATORS = false;
        assertFormat("struct Data{float value;};\nfloat f(float a,float b){float sum=a+b;if(a==b){return sum;}else{return a-b;}}\n",
                """
                struct Data {
                    float value;
                };
                float f(float a, float b)
                {
                    float sum=a+b;
                    if(a==b) {
                        return sum;
                    } else {
                        return a-b;
                    }
                }
                """);
        String adjacentOperators = "void f(){a = b + +c; a = b - -c;}\n";
        String formatted = NativeFormatting.format(adjacentOperators, settings);
        assertEquals(tokens(adjacentOperators), tokens(formatted), "Spacing options must not create ++ or --");
    }

    @Test
    void blankLineAndWrappingOptionsAreApplied() {
        var common = settings.getCommonSettings(SlangLanguage.INSTANCE);
        common.BLANK_LINES_AROUND_METHOD = 1;
        common.KEEP_BLANK_LINES_IN_CODE = 0;
        common.KEEP_BLANK_LINES_IN_DECLARATIONS = 1;
        common.KEEP_BLANK_LINES_BEFORE_RBRACE = 0;
        assertFormat("void first(){\n\nreturn;\n\n}\n\n\nvoid second(){return;}\n", """
                void first()
                {
                    return;
                }

                void second()
                {
                    return;
                }
                """);
        common.CALL_PARAMETERS_WRAP = 0;
        common.ASSIGNMENT_WRAP = 0;
        common.KEEP_LINE_BREAKS = false;
        settings.setRightMargin(SlangLanguage.INSTANCE, 20);
        String call = "void f(){call(firstArgument,\nsecondArgument,thirdArgument);}\n";
        String unwrapped = NativeFormatting.format(call, settings);
        assertTrue(unwrapped.contains("call(firstArgument, secondArgument, thirdArgument);"), unwrapped);
        common.CALL_PARAMETERS_WRAP = com.intellij.psi.codeStyle.CommonCodeStyleSettings.WRAP_ALWAYS;
        String wrapped = NativeFormatting.format(call, settings);
        assertTrue(wrapped.contains(",\n        secondArgument,\n        thirdArgument"), wrapped);
        assertEquals(wrapped, NativeFormatting.format(wrapped, settings));
    }

    @Test
    void multilineAlignmentAndSwitchIndentationFollowSettings() {
        var common = settings.getCommonSettings(SlangLanguage.INSTANCE);
        common.ALIGN_MULTILINE_PARAMETERS_IN_CALLS = true;
        common.CALL_PARAMETERS_WRAP = 0;
        String source = "void f(){call(firstArgument,\nsecondArgument,\nthirdArgument);}\n";
        String formatted = NativeFormatting.format(source, settings);
        var lines = formatted.lines().toList();
        int firstColumn = lines.stream().filter(line -> line.contains("firstArgument")).findFirst().orElseThrow().indexOf("firstArgument");
        int secondColumn = lines.stream().filter(line -> line.contains("secondArgument")).findFirst().orElseThrow().indexOf("secondArgument");
        assertEquals(firstColumn, secondColumn, formatted);
        assertFormat(source, formatted);
        common.INDENT_CASE_FROM_SWITCH = false;
        common.INDENT_BREAK_FROM_CASE = false;
        String switched = NativeFormatting.format(
                "void f(){switch(value){case 0:value=1;break;default:break;}}\n", settings);
        assertTrue(switched.contains("\n    case 0:"), switched);
        assertTrue(switched.contains("\n    break;"), switched);
        assertEquals(switched, NativeFormatting.format(switched, settings));
    }

    @Test
    void platformFormattingInspectionFollowsEngineSelection() {
        EdtTestUtil.runInEdtAndWait(() -> {
            var file = fixture.addFileToProject("inspection.slang", "float value=1;\n");
            fixture.configureFromExistingVirtualFile(file.getVirtualFile());
            fixture.enableInspections(new com.intellij.codeInspection.incorrectFormatting.IncorrectFormattingInspection());
            assertTrue(fixture.doHighlighting().stream().anyMatch(info ->
                    "IncorrectFormatting".equals(info.getInspectionToolId())));
            settings.getCustomSettings(slanglsp.settings.SlangCodeStyleSettings.class).USE_CLANG_FORMAT = true;
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(fixture.getProject()).restart();
            assertTrue(fixture.doHighlighting().stream().noneMatch(info ->
                    "IncorrectFormatting".equals(info.getInspectionToolId())));
        });
    }

    @Test
    void nativeFormatterReadsProjectClangFormatWhenEnabled() {
        EdtTestUtil.runInEdtAndWait(() -> {
            fixture.addFileToProject(".clang-format", """
                    BasedOnStyle: LLVM
                    IndentWidth: 2
                    BreakBeforeBraces: Attach
                    """);
            settings.getCustomSettings(slanglsp.settings.SlangCodeStyleSettings.class).READ_CLANG_FORMAT = true;
            var manager = CodeStyleSettingsManager.getInstance(fixture.getProject());
            var previous = manager.getMainProjectCodeStyle();
            boolean wasProject = manager.USE_PER_PROJECT_SETTINGS;
            manager.dropTemporarySettings();
            manager.setMainProjectCodeStyle(settings);
            manager.USE_PER_PROJECT_SETTINGS = true;
            var file = fixture.addFileToProject("config.slang", "void f(){return;}\n");
            try {
                WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> {
                    CodeStyleManager.getInstance(fixture.getProject()).reformat(file);
                });
                assertEquals("void f() {\n  return;\n}\n", file.getText());
                assertTrue(settings.getCustomSettings(slanglsp.settings.SlangCodeStyleSettings.class).BRACES_ON_NEW_LINE,
                        "Reading a file must not mutate the IDE scheme");
            } finally {
                manager.setMainProjectCodeStyle(previous);
                manager.USE_PER_PROJECT_SETTINGS = wasProject;
            }
        });
    }

    @Test
    void shaderAttributesAndPublicDeclarationsUseSlangAwareFormatting() {
        EdtTestUtil.runInEdtAndWait(() -> {
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            state.clangFormatLocation = slanglsp.TestDependencies.clangFormat();
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
            for (String source : List.of("""
                    [shader("compute")]
                    [numthreads(64, 1, 1)]
                    void main(uint3 id : SV_DispatchThreadID)
                    {
                        return;
                    }
                    """, """
                    public float3 shadeSurface(float3 color, float intensity)
                    {
                        return color * intensity;
                    }
                    """)) {
                var file = fixture.addFileToProject("shader" + System.nanoTime() + ".slang", source);
                fixture.configureFromExistingVirtualFile(file.getVirtualFile());
                assertNull(ClangFormat.options(file), "C++ formatting must not rewrite Slang declaration syntax");
                assertEquals(source, NativeFormatting.format(source, settings));
            }
        });
    }

    @Test
    void selectionFormattingOnlyChangesTargetFunction() {
        EdtTestUtil.runInEdtAndWait(() -> {
            String source = """
                    float first=1;

                    float badlyFormatted(float x){if(x>0){return x+1;}else{return -x;}}
                    """;
            var file = fixture.addFileToProject("multiple.slang", source);
            fixture.configureFromExistingVirtualFile(file.getVirtualFile());
            int function = source.indexOf("float badlyFormatted");
            WriteCommandAction.runWriteCommandAction(fixture.getProject(),
                    () -> CodeStyleManager.getInstance(fixture.getProject()).reformatText(file, function, source.length()));
            String expected = "float first=1;\n\n"
                    + NativeFormatting.format(source.substring(source.indexOf("float badlyFormatted")), settings);
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
                    "Formatting intention", () -> fixture.getEditor().getDocument().getText().equals(expected), 10);
        });
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
