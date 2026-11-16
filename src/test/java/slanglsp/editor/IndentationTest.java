package slanglsp.editor;

import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.editorActions.SmartBackspaceMode;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.junit.jupiter.api.Test;

class IndentationTest {
    @Test
    void nativeBackspaceAndEnterUseSlangIndentation() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        CodeInsightTestFixture fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("IndentationTest").getFixture(), new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(fixture::setUp);

        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                var settings = CodeInsightSettings.getInstance();
                SmartBackspaceMode oldMode = settings.getBackspaceMode();
                boolean oldPairBrackets = settings.AUTOINSERT_PAIR_BRACKET;
                boolean oldPairQuotes = settings.AUTOINSERT_PAIR_QUOTE;
                boolean oldSmartIndent = settings.SMART_INDENT_ON_ENTER;
                boolean oldCloseComment = settings.CLOSE_COMMENT_ON_ENTER;
                boolean oldSurround = settings.SURROUND_SELECTION_ON_QUOTE_TYPED;
                boolean oldTabOut = settings.TAB_EXITS_BRACKETS_AND_QUOTES;
                boolean oldInsertBrace = settings.INSERT_BRACE_ON_ENTER;
                boolean oldReformatBrace = settings.REFORMAT_BLOCK_ON_RBRACE;
                int oldPaste = settings.REFORMAT_ON_PASTE;

                var style = CodeStyle.createTestSettings();
                style.copyFrom(CodeStyle.getSettings(fixture.getProject()));
                com.intellij.psi.codeStyle.CodeStyleSettingsManager.getInstance(fixture.getProject())
                        .setTemporarySettings(style);

                var options = style.getIndentOptions(slanglsp.language.SlangFileType.INSTANCE);
                options.INDENT_SIZE = 4;
                options.CONTINUATION_INDENT_SIZE = 8;
                options.TAB_SIZE = 4;
                options.USE_TAB_CHARACTER = false;

                try {
                    settings.AUTOINSERT_PAIR_BRACKET = true;
                    settings.AUTOINSERT_PAIR_QUOTE = true;
                    settings.SMART_INDENT_ON_ENTER = true;
                    settings.CLOSE_COMMENT_ON_ENTER = true;
                    settings.SURROUND_SELECTION_ON_QUOTE_TYPED = true;
                    settings.TAB_EXITS_BRACKETS_AND_QUOTES = true;
                    settings.INSERT_BRACE_ON_ENTER = true;
                    settings.REFORMAT_BLOCK_ON_RBRACE = true;
                    settings.setBackspaceMode(SmartBackspaceMode.AUTOINDENT);
                    backspace(fixture, "void f()\n    <caret>{}", "void f()\n<caret>{}");
                    backspace(fixture, "void f()\n<caret>{}", "void f() <caret>{}");
                    backspace(fixture, "void f() {\n    object\n        <caret>.call();\n}",
                            "void f() {\n    object<caret>.call();\n}");
                    backspace(fixture, "void f() {\n        <caret>work();\n}",
                            "void f() {\n    <caret>work();\n}");
                    backspace(fixture, "void f() {\n    <caret>work();\n}",
                            "void f() { <caret>work();\n}");
                    backspace(fixture, "call(\n        <caret>value);", "call(<caret>value);");
                    backspace(fixture, "void f() {\n    call(\n            <caret>value);\n}",
                            "void f() {\n    call(<caret>value);\n}");
                    backspace(fixture, "void f() {\n    work();\n    <caret>}",
                            "void f() {\n    work();\n<caret>}");
                    backspace(fixture, "void f() {\n    \n    <caret>work();\n}",
                            "void f() {\n    <caret>work();\n}");
                    backspace(fixture, "void f() {\n    if (ready)\n        <caret>work();\n}",
                            "void f() {\n    if (ready) <caret>work();\n}");
                    backspace(fixture, "void f() {\n    string s = \"{\";\n        <caret>work();\n}",
                            "void f() {\n    string s = \"{\";\n    <caret>work();\n}");
                    backspace(fixture, "    <caret>value;", "<caret>value;");
                    backspace(fixture, "// comment\n    <caret>value;", "// comment\n   <caret>value;");
                    backspace(fixture, "/* comment\n    <caret>text\n*/", "/* comment\n   <caret>text\n*/");
                    backspace(fixture, "#define VALUE 1\n    <caret>value;",
                            "#define VALUE 1\n   <caret>value;");
                    backspace(fixture, "void f() {\n    <selection>work</selection>();\n}",
                            "void f() {\n    <caret>();\n}");
                    backspace(fixture, "void f() {\n        <caret>a();\n        <caret>b();\n}",
                            "void f() {\n    <caret>a();\n    <caret>b();\n}");

                    options.USE_TAB_CHARACTER = true;
                    backspace(fixture, "void f() {\n\t\t<caret>work();\n}",
                            "void f() {\n\t<caret>work();\n}");
                    backspace(fixture, "void f() {\n\t<caret>work();\n}",
                            "void f() { <caret>work();\n}");
                    options.USE_TAB_CHARACTER = false;

                    settings.setBackspaceMode(SmartBackspaceMode.INDENT);
                    backspace(fixture, "void f() {\n    <caret>work();\n}",
                            "void f() {\n<caret>work();\n}");

                    settings.setBackspaceMode(SmartBackspaceMode.OFF);
                    backspace(fixture, "void f() {\n    <caret>work();\n}",
                            "void f() {\n   <caret>work();\n}");
                    backspace(fixture, "void f() {\n\t<caret>work();\n}",
                            "void f() {\n<caret>work();\n}");

                    fixture.configureByText("test.slang", "void f() {<caret>");
                    fixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER);
                    fixture.checkResult("void f() {\n    <caret>\n}");

                    fixture.configureByText("test.slang", "call(<caret>");
                    fixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER);
                    fixture.checkResult("call(\n        <caret>");

                    fixture.configureByText("test.slang", "void text() {<caret>}");
                    fixture.type("return");
                    fixture.checkResult("void text() {return<caret>}");
                    fixture.type(';');
                    fixture.checkResult("void text() {return;<caret>}");

                    fixture.configureByText("test.slang", "void text() {<caret>}");
                    fixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER);
                    fixture.type("return;");
                    fixture.checkResult("void text() {\n    return;<caret>\n}");

                    fixture.configureByText("test.slang", "void text() {<caret> return;\n}");
                    fixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER);
                    fixture.checkResult("void text() {\n    <caret>return;\n}");

                    fixture.configureByText("test.slang", "void text() {\n    if (true) {<caret>}\n}");
                    fixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER);
                    fixture.checkResult("void text() {\n    if (true) {\n        <caret>\n    }\n}");

                    fixture.configureByText("test.slang", "void text() { return;<caret>}");
                    fixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER);
                    fixture.checkResult("void text() {\n    return;<caret>\n}");

                    enter(fixture, "void f() {\n    if (ready)<caret>\n}",
                            "void f() {\n    if (ready)\n        <caret>\n}");
                    enter(fixture, "void f() {\n    if (ready)\n        work();<caret>\n}",
                            "void f() {\n    if (ready)\n        work();\n    <caret>\n}");
                    enter(fixture, "void f() {\n    switch (x) {\n        case 1:<caret>\n    }\n}",
                            "void f() {\n    switch (x) {\n        case 1:\n            <caret>\n    }\n}");
                    enter(fixture, "void f() {\n    value =<caret>\n}",
                            "void f() {\n    value =\n            <caret>\n}");
                    enter(fixture, "/**<caret>", "/**\n * <caret>\n */");
                    enter(fixture, "    /// Description<caret>", "    /// Description\n    /// <caret>");
                    enter(fixture, "void f() {return;<caret>}",
                            "void f() {\n    return;<caret>\n}");
                    enter(fixture, "void f() { return;<caret>   }",
                            "void f() {\n    return;<caret>\n}");
                    enter(fixture, "void f() { <caret>return; }",
                            "void f() {\n    <caret>return;\n}");
                    enter(fixture, "void f() {<caret>return;}",
                            "void f() {\n    <caret>return;\n}");
                    fixture.configureByText("test.slang", "<caret>");
                    fixture.type("void test() {return;");
                    fixture.checkResult("void test() {return;<caret>}");
                    fixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER);
                    fixture.checkResult("void test() {\n    return;<caret>\n}");
                    enter(fixture, "void f() {a();<caret>b();}",
                            "void f() {\n    a();\n    <caret>b();\n}");
                    enter(fixture, "void f() {\n    while (\n        ready)<caret>\n}",
                            "void f() {\n    while (\n        ready)\n        <caret>\n}");

                    options.USE_TAB_CHARACTER = true;
                    enter(fixture, "void f() {return;<caret>}", "void f() {\n\treturn;<caret>\n}");
                    options.USE_TAB_CHARACTER = false;

                    fixture.configureByText("test.slang", "void f() {\n        return;\n    <caret>}");
                    fixture.type('}');
                    fixture.checkResult("void f() {\n    return;\n}<caret>");

                    fixture.configureByText("test.slang",
                            "void f() {\n    switch (x) {\n        case 1:\n            break;\n            case 2<caret>\n    }\n}");
                    fixture.type(':');
                    fixture.checkResult(
                            "void f() {\n    switch (x) {\n        case 1:\n            break;\n        case 2:<caret>\n    }\n}");

                    fixture.configureByText("test.slang", "void f() {return;<caret>}");
                    fixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER);
                    var undo = com.intellij.openapi.command.undo.UndoManager.getInstance(fixture.getProject());
                    var textEditor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance()
                            .getTextEditor(fixture.getEditor());
                    undo.undo(textEditor);
                    fixture.checkResult("void f() {return;<caret>}");
                    undo.redo(textEditor);
                    fixture.checkResult("void f() {\n    return;<caret>\n}");

                    fixture.configureByText("test.slang", "<caret>");
                    fixture.type("/*");
                    fixture.checkResult("/*<caret>*/");

                    fixture.configureByText("test.slang", "<selection>value</selection>");
                    fixture.type('(');
                    fixture.checkResult("(<selection>value</selection>)");

                    fixture.configureByText("test.slang", "<selection>value</selection>");
                    fixture.type('"');
                    fixture.checkResult("\"<selection>value</selection>\"");

                    settings.REFORMAT_ON_PASTE = CodeInsightSettings.INDENT_EACH_LINE;
                    fixture.configureByText("test.slang", "void f() {\n<caret>\n}");
                    com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(
                            new java.awt.datatransfer.StringSelection("if (ready) {\nwork();\n}"));
                    fixture.performEditorAction(IdeActions.ACTION_EDITOR_PASTE);
                    org.junit.jupiter.api.Assertions.assertEquals(
                            "void f() {\n    if (ready) {\n        work();\n    }\n}",
                            fixture.getEditor().getDocument().getText());
                    fixture.checkResult("void f() {\n    if (ready) {\n        work();\n    }<caret>\n}");

                    settings.REFORMAT_ON_PASTE = CodeInsightSettings.INDENT_BLOCK;
                    fixture.configureByText("test.slang", "void f() {\n<caret>\n}");
                    com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(
                            new java.awt.datatransfer.StringSelection("if (ready) {\n  work();\n}"));
                    fixture.performEditorAction(IdeActions.ACTION_EDITOR_PASTE);
                    fixture.checkResult("void f() {\n    if (ready) {\n      work();\n    }<caret>\n}");

                    settings.REFORMAT_ON_PASTE = CodeInsightSettings.NO_REFORMAT;
                    fixture.configureByText("test.slang", "void f() {\n<caret>\n}");
                    fixture.performEditorAction(IdeActions.ACTION_EDITOR_PASTE);
                    fixture.checkResult("void f() {\nif (ready) {\n  work();\n}<caret>\n}");

                    enter(fixture, "void a() {return;<caret>}\nvoid b() {return;<caret>}",
                            "void a() {\n    return;<caret>\n}\nvoid b() {\n    return;<caret>\n}");

                    fixture.configureByText("test.slang", "string s = \"a\\<caret>\";");
                    fixture.type('"');
                    fixture.checkResult("string s = \"a\\\"<caret>\";");

                    fixture.configureByText("test.slang", "// <caret>");
                    fixture.type('"');
                    fixture.checkResult("// \"<caret>");

                    settings.SMART_INDENT_ON_ENTER = false;
                    enter(fixture, "void f() {return;<caret>}", "void f() {return;\n<caret>}");
                    settings.SMART_INDENT_ON_ENTER = true;

                    for (String pair : new String[] {"{}", "()", "[]", "\"\"", "''"}) {
                        fixture.configureByText("test.slang", "<caret>");
                        fixture.type(pair.charAt(0));
                        fixture.checkResult(pair.charAt(0) + "<caret>" + pair.charAt(1));
                        fixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE);
                        fixture.checkResult("<caret>");

                        fixture.type(pair.charAt(0));
                        fixture.type(pair.charAt(1));
                        fixture.checkResult(pair + "<caret>");

                        fixture.configureByText("test.slang", "<caret>");
                        fixture.type(pair.charAt(0));
                        fixture.performEditorAction(IdeActions.ACTION_BRACE_OR_QUOTE_OUT);
                        fixture.checkResult(pair + "<caret>");
                    }

                    fixture.configureByText("test.slang", "// <caret>");
                    fixture.type('(');
                    fixture.checkResult("// (<caret>");

                    fixture.configureByText("test.slang", "string s = \"<caret>\";");
                    fixture.type('[');
                    fixture.checkResult("string s = \"[<caret>\";");

                    settings.AUTOINSERT_PAIR_BRACKET = false;
                    fixture.configureByText("test.slang", "<caret>");
                    fixture.type('(');
                    fixture.checkResult("(<caret>");
                    settings.AUTOINSERT_PAIR_QUOTE = false;
                    fixture.configureByText("test.slang", "<caret>");
                    fixture.type('"');
                    fixture.checkResult("\"<caret>");
                } finally {
                    settings.REFORMAT_BLOCK_ON_RBRACE = oldReformatBrace;
                    settings.INSERT_BRACE_ON_ENTER = oldInsertBrace;
                    settings.TAB_EXITS_BRACKETS_AND_QUOTES = oldTabOut;
                    settings.REFORMAT_ON_PASTE = oldPaste;
                    settings.SURROUND_SELECTION_ON_QUOTE_TYPED = oldSurround;
                    settings.CLOSE_COMMENT_ON_ENTER = oldCloseComment;
                    settings.SMART_INDENT_ON_ENTER = oldSmartIndent;
                    settings.AUTOINSERT_PAIR_QUOTE = oldPairQuotes;
                    settings.AUTOINSERT_PAIR_BRACKET = oldPairBrackets;
                    settings.setBackspaceMode(oldMode);
                    com.intellij.psi.codeStyle.CodeStyleSettingsManager.getInstance(fixture.getProject())
                            .dropTemporarySettings();
                }
            });
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }

    private static void backspace(CodeInsightTestFixture fixture, String before, String after) {
        fixture.configureByText("test.slang", before);
        fixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE);
        fixture.checkResult(after);
    }

    private static void enter(CodeInsightTestFixture fixture, String before, String after) {
        fixture.configureByText("test.slang", before);
        fixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER);
        org.junit.jupiter.api.Assertions.assertEquals(after.replace("<caret>", ""), fixture.getEditor().getDocument().getText());
        fixture.checkResult(after);
    }
}
