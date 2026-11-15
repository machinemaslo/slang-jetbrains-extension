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

class SlangIndentationTest {
    @Test
    void nativeBackspaceAndEnterUseSlangIndentation() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        CodeInsightTestFixture fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("SlangIndentationTest").getFixture(), new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(fixture::setUp);

        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                var settings = CodeInsightSettings.getInstance();
                SmartBackspaceMode oldMode = settings.getBackspaceMode();

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
                            "void f() {\n    if (ready)\n       <caret>work();\n}");
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
                    fixture.checkResult("void f() {\n    <caret>");

                    fixture.configureByText("test.slang", "call(<caret>");
                    fixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER);
                    fixture.checkResult("call(\n        <caret>");
                } finally {
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
}
