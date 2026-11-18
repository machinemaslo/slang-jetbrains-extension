package slanglsp.editor;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class AngleBracketsTest {
    private CodeInsightTestFixture fixture;
    private boolean oldPair;
    private boolean oldTab;
    private boolean oldSurround;

    @BeforeEach
    void setUp() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("SlangAngles").getFixture(), new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(() -> {
            fixture.setUp();
            com.intellij.openapi.roots.ModuleRootModificationUtil.addContentRoot(
                    fixture.getModule(), fixture.getTempDirPath());
            var definition = com.redhat.devtools.lsp4ij.LanguageServersRegistry.getInstance()
                    .getServerDefinition("slanglsp.SlangLanguageServer");
            org.junit.jupiter.api.Assertions.assertNotNull(definition);
            definition.setEnabled(false, fixture.getProject());
            var settings = CodeInsightSettings.getInstance();
            oldPair = settings.AUTOINSERT_PAIR_BRACKET;
            oldTab = settings.TAB_EXITS_BRACKETS_AND_QUOTES;
            oldSurround = settings.SURROUND_SELECTION_ON_QUOTE_TYPED;
            settings.AUTOINSERT_PAIR_BRACKET = true;
            settings.TAB_EXITS_BRACKETS_AND_QUOTES = true;
            settings.SURROUND_SELECTION_ON_QUOTE_TYPED = true;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        EdtTestUtil.runInEdtAndWait(() -> {
            CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET = oldPair;
            CodeInsightSettings.getInstance().TAB_EXITS_BRACKETS_AND_QUOTES = oldTab;
            CodeInsightSettings.getInstance().SURROUND_SELECTION_ON_QUOTE_TYPED = oldSurround;
            fixture.tearDown();
        });
    }

    @Test
    void reusesLargeDocumentParseUntilItChanges() {
        EdtTestUtil.runInEdtAndWait(() -> {
            fixture.configureByText("large.slang", "float4 color;\n".repeat(4000));
            var document = fixture.getEditor().getDocument();
            var initial = GenericResolver.parsed(document);
            assertSame(initial, GenericResolver.parsed(document));
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(fixture.getProject(),
                    () -> document.insertString(0, "import lighting;\n"));
            var updated = GenericResolver.parsed(document);
            assertNotSame(initial, updated);
            assertEquals(java.util.Set.of("lighting"), updated.imports());
            assertSame(updated, GenericResolver.parsed(document));
        });
    }

    @Test
    void documentedGenericDeclarationForms() {
        EdtTestUtil.runInEdtAndWait(() -> {
            for (String declaration : new String[] {
                    "struct container", "interface iface", "typealias alias", "func apply",
                    "void apply", "T apply", "vector<float, 4> apply",
                    "__generic", "extension", "__init", "__subscript"
            }) {
                type(declaration + "<caret>", "<", declaration + "<<caret>>");
                fixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE);
                fixture.checkResult(declaration + "<caret>");
            }
            type("void f<caret>", "<let N : int, each T>",
                    "void f<let N : int, each T><caret>");
            type("extension<caret>", "<T : IFoo>", "extension<T : IFoo><caret>");
            type("__generic<caret>", "<typename T, int N>", "__generic<typename T, int N><caret>");
        });
    }

    @Test
    void genericUsesResolveDeclarationsAndBuiltins() {
        EdtTestUtil.runInEdtAndWait(() -> {
            for (String builtin : new String[] {
                    "vector", "matrix", "Texture2D", "RWStructuredBuffer", "ParameterBlock", "reinterpret", "bit_cast"
            }) {
                type(builtin + "<caret>", "<float>", builtin + "<float><caret>");
            }
            type("struct box<T> {}\nbox<caret>", "<float>", "struct box<T> {}\nbox<float><caret>");
            type("T identity<T>(T value) { return value; }\nidentity<caret>", "<float>",
                    "T identity<T>(T value) { return value; }\nidentity<float><caret>");
            type("__generic<T>\nT identity(T value) { return value; }\nidentity<caret>", "<float>",
                    "__generic<T>\nT identity(T value) { return value; }\nidentity<float><caret>");
            type("struct box<T> {}\ntypealias alias<T> = box<T>;\nalias<caret>", "<int>",
                    "struct box<T> {}\ntypealias alias<T> = box<T>;\nalias<int><caret>");
            type("struct S { T get<T>() {} }\nS value;\nvalue.get<caret>", "<int>",
                    "struct S { T get<T>() {} }\nS value;\nvalue.get<int><caret>");
            type("namespace ns { T get<T>() {} }\nns::get<caret>", "<int>",
                    "namespace ns { T get<T>() {} }\nns::get<int><caret>");
            type("void f(int Texture2D) {}\nTexture2D<caret>", "<float>",
                    "void f(int Texture2D) {}\nTexture2D<float><caret>");
            type("struct S<T> { U get<U>() {} }\nS<int> value;\nvalue.get<caret>", "<int>",
                    "struct S<T> { U get<U>() {} }\nS<int> value;\nvalue.get<int><caret>");
            type("struct S { T get<T>() {} void f() { this.get<caret> } }", "<int>",
                    "struct S { T get<T>() {} void f() { this.get<int><caret> } }");
        });
    }

    @Test
    void nestedAnglesTypeOverAndTabOut() {
        EdtTestUtil.runInEdtAndWait(() -> {
            type("StructuredBuffer<caret>", "<vector<float, 4>>", "StructuredBuffer<vector<float, 4>><caret>");
            type("matrix<caret>", "<float, 3, 4>", "matrix<float, 3, 4><caret>");
            type("struct box<T> {}\nbox<caret>", "<box<int>>",
                    "struct box<T> {}\nbox<box<int>><caret>");
            type("vector<caret>>", "<", "vector<<caret>>");
            type("vector<float<caret>>", ">", "vector<float><caret>");
            type("Texture2D<caret>", "<", "Texture2D<<caret>>");
            fixture.performEditorAction(IdeActions.ACTION_BRACE_OR_QUOTE_OUT);
            fixture.checkResult("Texture2D<><caret>");
            type("vector<<caret>>", "", "vector<<caret>>");
            fixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE);
            fixture.checkResult("vector<caret>");
            fixture.configureByText("test.slang", "vector<selection>float</selection>");
            fixture.type('<');
            fixture.checkResult("vector<<selection>float</selection>>");
        });
    }

    @Test
    void operatorsCommentsAndStringsAreNotGenericBrackets() {
        EdtTestUtil.runInEdtAndWait(() -> {
            for (String prefix : new String[] {
                    "a", "A", "if (value", "return value", "int value;\nvalue",
                    "int Value;\nValue", "int Texture2D;\nTexture2D",
                    "struct box<T> {}\nvoid f() { int box; box",
                    "struct plain {}\nplain", "float normal(float x) { return x; }\nnormal",
                    "let x", "var x", "// vector", "/* vector", "a < b > c", "#if Texture2D",
                    "void f(int Texture2D) { Texture2D"
            }) {
                type(prefix + "<caret>", "<", prefix + "<<caret>");
            }
            type("a<caret>", "<<", "a<<<caret>");
            type("a<caret>", "<=", "a<=<caret>");
            type("a<caret>", ">>", "a>><caret>");
            type("a<caret>", ">=", "a>=<caret>");
            type("\"Texture2D<caret>\"", "<", "\"Texture2D<<caret>\"");
            type("vector<caret>", "<<", "vector<<<caret>");
            type("vector<caret>", "<=", "vector<=<caret>");
            type("void f<let N : int = (2 <caret> 1)>() {}", ">>",
                    "void f<let N : int = (2 >><caret> 1)>() {}");
            type("void f<let N : bool = (2 <caret> 1)>() {}", ">",
                    "void f<let N : bool = (2 ><caret> 1)>() {}");
        });
    }

    @Test
    void enterIndentsGenericArgumentsAndAlignsCloser() {
        EdtTestUtil.runInEdtAndWait(() -> {
            var style = com.intellij.application.options.CodeStyle.createTestSettings();
            style.copyFrom(com.intellij.application.options.CodeStyle.getSettings(fixture.getProject()));
            var manager = com.intellij.psi.codeStyle.CodeStyleSettingsManager.getInstance(fixture.getProject());
            manager.setTemporarySettings(style);
            var options = style.getIndentOptions(slanglsp.language.SlangFileType.INSTANCE);
            options.INDENT_SIZE = 4;
            options.CONTINUATION_INDENT_SIZE = 8;
            options.USE_TAB_CHARACTER = false;
            try {
                type("StructuredBuffer<caret>", "<", "StructuredBuffer<<caret>>");
                fixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER);
                fixture.checkResult("StructuredBuffer<\n        <caret>\n>");
                fixture.type("float");
                fixture.checkResult("StructuredBuffer<\n        float<caret>\n>");
            } finally {
                manager.dropTemporarySettings();
            }
        });
    }

    @Test
    void importedGenericsUseIndexedDeclarations() {
        EdtTestUtil.runInEdtAndWait(() -> {
            fixture.addFileToProject("library.slang",
                    """
                            module library;
                            struct box<T> {}
                            T identity<T>(T x) { return x; }
                            struct object { T get<T>() {} }""");
            type("import library;\nbox<caret>", "<int>", "import library;\nbox<int><caret>");
            type("import library;\nidentity<caret>", "<float>", "import library;\nidentity<float><caret>");
            type("import library;\nobject instance;\ninstance.get<caret>", "<float>",
                    "import library;\nobject instance;\ninstance.get<float><caret>");
            type("box<caret>", "<", "box<<caret>");
            type("import library;\nint box;\nbox<caret>", "<", "import library;\nint box;\nbox<<caret>");
            type("import library;\nunknown.get<caret>", "<", "import library;\nunknown.get<<caret>");
        });
    }

    @Test
    void importedGenericIndexTracksDeclarationEdits() {
        EdtTestUtil.runInEdtAndWait(() -> {
            var library = fixture.addFileToProject("changing.slang", "struct box<T> {}");
            assertEquals(java.util.Set.of(":box"), new GenericContext(library.getText()).genericDeclarations());
            org.junit.jupiter.api.Assertions.assertFalse(
                    com.intellij.util.indexing.FileBasedIndex.getInstance().getContainingFiles(
                            GenericDeclarationsIndex.NAME, "changing::box",
                            com.intellij.psi.search.GlobalSearchScope.allScope(fixture.getProject())).isEmpty());
            type("import changing;\nbox<caret>", "<", "import changing;\nbox<<caret>>");
            var manager = com.intellij.psi.PsiDocumentManager.getInstance(fixture.getProject());
            var document = manager.getDocument(library);
            org.junit.jupiter.api.Assertions.assertNotNull(document);
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(fixture.getProject(),
                    () -> document.setText("struct box {}"));
            manager.commitAllDocuments();
            type("import changing;\nbox<caret>", "<", "import changing;\nbox<<caret>");
        });
    }

    @Test
    void settingsMultiCaretAndUndo() {
        EdtTestUtil.runInEdtAndWait(() -> {
            type("Texture2D<caret>\nvector<caret>", "<", "Texture2D<<caret>>\nvector<<caret>>");
            fixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE);
            fixture.checkResult("Texture2D<caret>\nvector<caret>");

            type("Texture2D<caret>", "<", "Texture2D<<caret>>");
            var undo = com.intellij.openapi.command.undo.UndoManager.getInstance(fixture.getProject());
            var editor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance()
                    .getTextEditor(fixture.getEditor());
            undo.undo(editor);
            fixture.checkResult("Texture2D<caret>");
            undo.redo(editor);
            fixture.checkResult("Texture2D<<caret>>");

            CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET = false;
            type("Texture2D<caret>", "<", "Texture2D<<caret>");
            type("vector<float<caret>>", ">", "vector<float><caret>>");
        });
    }

    private void type(String before, String typed, String after) {
        fixture.configureByText("test.slang", before);
        fixture.type(typed);
        assertEquals(after.replace("<caret>", ""), fixture.getEditor().getDocument().getText(), before);
        fixture.checkResult(after);
    }
}
