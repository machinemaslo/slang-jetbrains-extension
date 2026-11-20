package slanglsp.editor.typing

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.Companion.getInstance
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.indexing.FileBasedIndex
import slanglsp.editor.NativeServerSupport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import slanglsp.editor.typing.GenericResolver.parsed
import slanglsp.language.SlangFileType
import slanglsp.language.generics.GenericContext
import slanglsp.replaceAllText
import slanglsp.useTemporarySettings

internal class AngleBracketsTest {
    private var fixture: CodeInsightTestFixture? = null
    private var oldPair = false
    private var oldTab = false
    private var oldSurround = false

    @BeforeEach
    @Throws(Exception::class)
    fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture =
            factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("SlangAngles").getFixture(), TempDirTestFixtureImpl())
        runInEdtAndWait {
            fixture!!.setUp()
            ModuleRootModificationUtil.addContentRoot(fixture!!.module, fixture!!.tempDirPath)
            NativeServerSupport.disable(fixture!!.project)
            val settings = CodeInsightSettings.getInstance()
            oldPair = settings.AUTOINSERT_PAIR_BRACKET
            oldTab = settings.TAB_EXITS_BRACKETS_AND_QUOTES
            oldSurround = settings.SURROUND_SELECTION_ON_QUOTE_TYPED
            settings.AUTOINSERT_PAIR_BRACKET = true
            settings.TAB_EXITS_BRACKETS_AND_QUOTES = true
            settings.SURROUND_SELECTION_ON_QUOTE_TYPED = true
        }
    }

    @AfterEach
    @Throws(Exception::class)
    fun tearDown() {
        runInEdtAndWait {
            CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET = oldPair
            CodeInsightSettings.getInstance().TAB_EXITS_BRACKETS_AND_QUOTES = oldTab
            CodeInsightSettings.getInstance().SURROUND_SELECTION_ON_QUOTE_TYPED = oldSurround
            fixture!!.tearDown()
        }
    }

    @Test
    fun reusesLargeDocumentParseUntilItChanges() {
        runInEdtAndWait {
            fixture!!.configureByText("large.slang", "float4 color;\n".repeat(4000))
            val document = fixture!!.editor.document
            val initial = parsed(document)
            Assertions.assertSame(initial, parsed(document))
            WriteCommandAction.runWriteCommandAction(fixture!!.project) {
                document.insertString(0, "import lighting;\n")
            }
            val updated = parsed(document)
            Assertions.assertNotSame(initial, updated)
            Assertions.assertEquals(mutableSetOf<String?>("lighting"), updated.imports())
            Assertions.assertSame(updated, parsed(document))
        }
    }

    @Test
    fun documentedGenericDeclarationForms() {
        runInEdtAndWait {
            for (declaration in
                arrayOf(
                    "struct container",
                    "interface iface",
                    "typealias alias",
                    "func apply",
                    "void apply",
                    "T apply",
                    "vector<float, 4> apply",
                    "__generic",
                    "extension",
                    "__init",
                    "__subscript")) {
                type("$declaration<caret>", "<", "$declaration<<caret>>")
                fixture!!.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
                fixture!!.checkResult("$declaration<caret>")
            }
            type("void f<caret>", "<let N : int, each T>", "void f<let N : int, each T><caret>")
            type("extension<caret>", "<T : IFoo>", "extension<T : IFoo><caret>")
            type("__generic<caret>", "<typename T, int N>", "__generic<typename T, int N><caret>")
        }
    }

    @Test
    fun genericUsesResolveDeclarationsAndBuiltins() {
        runInEdtAndWait {
            for (builtin in
                arrayOf(
                    "vector",
                    "matrix",
                    "Texture2D",
                    "RWStructuredBuffer",
                    "ParameterBlock",
                    "reinterpret",
                    "bit_cast")) {
                type("$builtin<caret>", "<float>", "$builtin<float><caret>")
            }
            type("struct box<T> {}\nbox<caret>", "<float>", "struct box<T> {}\nbox<float><caret>")
            type(
                "T identity<T>(T value) { return value; }\nidentity<caret>",
                "<float>",
                "T identity<T>(T value) { return value; }\nidentity<float><caret>")
            type(
                "__generic<T>\nT identity(T value) { return value; }\nidentity<caret>",
                "<float>",
                "__generic<T>\nT identity(T value) { return value; }\nidentity<float><caret>")
            type(
                "struct box<T> {}\ntypealias alias<T> = box<T>;\nalias<caret>",
                "<int>",
                "struct box<T> {}\ntypealias alias<T> = box<T>;\nalias<int><caret>")
            type(
                "struct S { T get<T>() {} }\nS value;\nvalue.get<caret>",
                "<int>",
                "struct S { T get<T>() {} }\nS value;\nvalue.get<int><caret>")
            type(
                "namespace ns { T get<T>() {} }\nns::get<caret>",
                "<int>",
                "namespace ns { T get<T>() {} }\nns::get<int><caret>")
            type(
                "void f(int Texture2D) {}\nTexture2D<caret>",
                "<float>",
                "void f(int Texture2D) {}\nTexture2D<float><caret>")
            type(
                "struct S<T> { U get<U>() {} }\nS<int> value;\nvalue.get<caret>",
                "<int>",
                "struct S<T> { U get<U>() {} }\nS<int> value;\nvalue.get<int><caret>")
            type(
                "struct S { T get<T>() {} void f() { this.get<caret> } }",
                "<int>",
                "struct S { T get<T>() {} void f() { this.get<int><caret> } }")
        }
    }

    @Test
    fun nestedAnglesTypeOverAndTabOut() {
        runInEdtAndWait {
            type("StructuredBuffer<caret>", "<vector<float, 4>>", "StructuredBuffer<vector<float, 4>><caret>")
            type("matrix<caret>", "<float, 3, 4>", "matrix<float, 3, 4><caret>")
            type("struct box<T> {}\nbox<caret>", "<box<int>>", "struct box<T> {}\nbox<box<int>><caret>")
            type("vector<caret>>", "<", "vector<<caret>>")
            type("vector<float<caret>>", ">", "vector<float><caret>")
            type("Texture2D<caret>", "<", "Texture2D<<caret>>")
            fixture!!.performEditorAction(IdeActions.ACTION_BRACE_OR_QUOTE_OUT)
            fixture!!.checkResult("Texture2D<><caret>")
            type("vector<<caret>>", "", "vector<<caret>>")
            fixture!!.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
            fixture!!.checkResult("vector<caret>")
            fixture!!.configureByText("test.slang", "vector<selection>float</selection>")
            fixture!!.type('<')
            fixture!!.checkResult("vector<<selection>float</selection>>")
        }
    }

    @Test
    fun operatorsCommentsAndStringsAreNotGenericBrackets() {
        runInEdtAndWait {
            for (prefix in
                arrayOf(
                    "a",
                    "A",
                    "if (value",
                    "return value",
                    "int value;\nvalue",
                    "int Value;\nValue",
                    "int Texture2D;\nTexture2D",
                    "struct box<T> {}\nvoid f() { int box; box",
                    "struct plain {}\nplain",
                    "float normal(float x) { return x; }\nnormal",
                    "let x",
                    "var x",
                    "// vector",
                    "/* vector",
                    "a < b > c",
                    "#if Texture2D",
                    "void f(int Texture2D) { Texture2D")) {
                type("$prefix<caret>", "<", "$prefix<<caret>")
            }
            type("a<caret>", "<<", "a<<<caret>")
            type("a<caret>", "<=", "a<=<caret>")
            type("a<caret>", ">>", "a>><caret>")
            type("a<caret>", ">=", "a>=<caret>")
            type("\"Texture2D<caret>\"", "<", "\"Texture2D<<caret>\"")
            type("vector<caret>", "<<", "vector<<<caret>")
            type("vector<caret>", "<=", "vector<=<caret>")
            type("void f<let N : int = (2 <caret> 1)>() {}", ">>", "void f<let N : int = (2 >><caret> 1)>() {}")
            type("void f<let N : bool = (2 <caret> 1)>() {}", ">", "void f<let N : bool = (2 ><caret> 1)>() {}")
        }
    }

    @Test
    fun enterIndentsGenericArgumentsAndAlignsCloser() {
        runInEdtAndWait {
            val style = CodeStyle.createTestSettings()
            style.copyFrom(CodeStyle.getSettings(fixture!!.project))
            val manager = CodeStyleSettingsManager.getInstance(fixture!!.project)
            manager.useTemporarySettings(style)
            val options = style.getIndentOptions(SlangFileType)
            options.INDENT_SIZE = 4
            options.CONTINUATION_INDENT_SIZE = 8
            options.USE_TAB_CHARACTER = false
            try {
                type("StructuredBuffer<caret>", "<", "StructuredBuffer<<caret>>")
                fixture!!.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
                fixture!!.checkResult("StructuredBuffer<\n        <caret>\n>")
                fixture!!.type("float")
                fixture!!.checkResult("StructuredBuffer<\n        float<caret>\n>")
            } finally {
                manager.dropTemporarySettings()
            }
        }
    }

    @Test
    fun importedGenericsUseIndexedDeclarations() {
        runInEdtAndWait {
            fixture!!.addFileToProject(
                "library.slang",
                """
                            module library;
                            struct box<T> {}
                            T identity<T>(T x) { return x; }
                            struct object { T get<T>() {} }
                            """
                    .trimIndent())
            type("import library;\nbox<caret>", "<int>", "import library;\nbox<int><caret>")
            type("import library;\nidentity<caret>", "<float>", "import library;\nidentity<float><caret>")
            type(
                "import library;\nobject instance;\ninstance.get<caret>",
                "<float>",
                "import library;\nobject instance;\ninstance.get<float><caret>")
            type("box<caret>", "<", "box<<caret>")
            type("import library;\nint box;\nbox<caret>", "<", "import library;\nint box;\nbox<<caret>")
            type("import library;\nunknown.get<caret>", "<", "import library;\nunknown.get<<caret>")
        }
    }

    @Test
    fun importedGenericIndexTracksDeclarationEdits() {
        runInEdtAndWait {
            val library = fixture!!.addFileToProject("changing.slang", "struct box<T> {}")
            Assertions.assertEquals(mutableSetOf<String?>(":box"), GenericContext(library.text).genericDeclarations())
            Assertions.assertFalse(
                FileBasedIndex.getInstance()
                    .getContainingFiles(
                        GenericDeclarations.NAME, "changing::box", GlobalSearchScope.allScope(fixture!!.project))
                    .isEmpty())
            type("import changing;\nbox<caret>", "<", "import changing;\nbox<<caret>>")
            val manager = PsiDocumentManager.getInstance(fixture!!.project)
            val document = manager.getDocument(library)
            Assertions.assertNotNull(document)
            WriteCommandAction.runWriteCommandAction(fixture!!.project) { document!!.replaceAllText("struct box {}") }
            manager.commitAllDocuments()
            type("import changing;\nbox<caret>", "<", "import changing;\nbox<<caret>")
        }
    }

    @Test
    fun settingsMultiCaretAndUndo() {
        runInEdtAndWait {
            type("Texture2D<caret>\nvector<caret>", "<", "Texture2D<<caret>>\nvector<<caret>>")
            fixture!!.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
            fixture!!.checkResult("Texture2D<caret>\nvector<caret>")

            type("Texture2D<caret>", "<", "Texture2D<<caret>>")
            val undo = UndoManager.getInstance(fixture!!.project)
            val editor = getInstance().getTextEditor(fixture!!.editor)
            undo.undo(editor)
            fixture!!.checkResult("Texture2D<caret>")
            undo.redo(editor)
            fixture!!.checkResult("Texture2D<<caret>>")

            CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET = false
            type("Texture2D<caret>", "<", "Texture2D<<caret>")
            type("vector<float<caret>>", ">", "vector<float><caret>>")
        }
    }

    private fun type(before: String, typed: String, after: String) {
        fixture!!.configureByText("test.slang", before)
        fixture!!.type(typed)
        Assertions.assertEquals(after.replace("<caret>", ""), fixture!!.editor.document.text, before)
        fixture!!.checkResult(after)
    }
}
