package slanglsp.editor;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import slanglsp.language.SlangLanguage;
import slanglsp.settings.CodeStylePresets;
import slanglsp.settings.ProjectSettings;
import slanglsp.settings.SlangCodeStyleSettings;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static slanglsp.FormattingFixtures.text;

/** Configuration import must affect real IDE formatting without requiring either executable. */
class NativeFormattingConfigurationTest {
    private CodeInsightTestFixture fixture;
    private CodeStyleSettings settings;
    private CodeStyleSettings previous;
    private boolean wasProject;

    @BeforeEach
    void setUp() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder(
                        new com.intellij.testFramework.LightProjectDescriptor(), "NativeFormattingConfiguration").getFixture(),
                new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(() -> {
            fixture.setUp();
            NativeServerSupport.disable(fixture.getProject());
            ModuleRootModificationUtil.addContentRoot(fixture.getModule(), fixture.getTempDirPath());
            var state = ProjectSettings.getInstance(fixture.getProject()).getState();
            state.explicitSlangdLocation = fixture.getTempDirPath() + "/no-slangd";
            state.clangFormatLocation = fixture.getTempDirPath() + "/no-clang-format";
            ProjectSettings.getInstance(fixture.getProject()).loadState(state);
            var manager = CodeStyleSettingsManager.getInstance(fixture.getProject());
            manager.dropTemporarySettings();
            previous = manager.getMainProjectCodeStyle();
            wasProject = manager.USE_PER_PROJECT_SETTINGS;
            settings = manager.createSettings();
            new CodeStylePresets.Slang().apply(settings);
            manager.setMainProjectCodeStyle(settings);
            manager.USE_PER_PROJECT_SETTINGS = true;
            settings = com.intellij.application.options.CodeStyle.getSettings(fixture.getProject());
        });
    }

    @Test
    void inheritedConfigAppliesCustomBracesSpacingAndBlankLineLimits() {
        EdtTestUtil.runInEdtAndWait(() -> {
            settings.getCustomSettings(SlangCodeStyleSettings.class).READ_CLANG_FORMAT = true;
            CodeStyleSettingsManager.getInstance(fixture.getProject()).notifyCodeStyleSettingsChanged();
            fixture.addFileToProject(".clang-format", """
                    IndentWidth: 2
                    BreakBeforeBraces: Custom
                    BraceWrapping:
                      AfterNamespace: true
                      AfterStruct: true
                      AfterFunction: true
                      AfterControlStatement: Always
                      BeforeElse: false
                    SpaceBeforeParens: Never
                    SpaceBeforeAssignmentOperators: false
                    SpacesInSquareBrackets: true
                    IndentCaseLabels: false
                    MaxEmptyLinesToKeep: 0
                    """);
            fixture.addFileToProject("nested/.clang-format", """
                    BasedOnStyle: InheritParentConfig
                    BraceWrapping:
                      AfterFunction: false
                    """);
            var file = fixture.addFileToProject("nested/example.slang", """
                    namespace N {
                    struct S { int field; };
                    class C { int field; };
                    int f(int x) {
                    int values[2] = {1, 2};


                    if (x) { return values[0]; } else { return 0; }
                    switch (x) { case 0: return 1; default: return 2; }
                    }
                    }
                    """);
            reformat(file);
            String formatted = file.getText();
            assertTrue(formatted.startsWith("namespace N\n{\n  struct S\n  {"), formatted);
            assertTrue(formatted.contains("class C {"), formatted);
            assertTrue(formatted.contains("int f(int x) {"), formatted);
            assertTrue(formatted.contains("int values[ 2 ]= {"), formatted);
            assertTrue(formatted.contains("if(x)\n    {"), formatted);
            assertTrue(formatted.contains("} else\n    {"), formatted);
            assertTrue(formatted.contains("switch(x)\n    {\n    case 0:"), formatted);
            assertFalse(formatted.contains("\n\n"), formatted);
            reformat(file);
            assertEquals(formatted, file.getText());
            assertEquals(-1, settings.getCustomSettings(SlangCodeStyleSettings.class).SPACE_BEFORE_ASSIGNMENT);
        });
    }

    private void reformat(com.intellij.psi.PsiFile file) {
        WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> {
            CodeStyleManager.getInstance(fixture.getProject()).reformat(file);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        EdtTestUtil.runInEdtAndWait(() -> {
            var manager = CodeStyleSettingsManager.getInstance(fixture.getProject());
            manager.setMainProjectCodeStyle(previous);
            manager.USE_PER_PROJECT_SETTINGS = wasProject;
            fixture.tearDown();
        });
    }

    private record Scenario(String name, boolean readConfig, Map<String, String> files, String expected) {
        @Override
        public @NotNull String toString() {
            return name;
        }
    }

    static Stream<Scenario> configurations() {
        String inputConfig = text("configuration/native/.clang-format");
        String slang = text("simple-function/after.allman-four.slang");
        String twoSpaces = text("simple-function/after.attach-two.slang");
        return Stream.of(
                new Scenario("disabled import ignores config", false, Map.of(".clang-format", inputConfig), slang),
                new Scenario("enabled import without a file", true, Map.of(), slang),
                new Scenario("parent directory discovery", true, Map.of(".clang-format", inputConfig), twoSpaces),
                new Scenario("underscore filename", true, Map.of("_clang-format", inputConfig), twoSpaces),
                new Scenario("nearest file wins", true,
                        Map.of(".clang-format", inputConfig, "nested/.clang-format",
                                text("configuration/nearest/.clang-format")), slang),
                new Scenario("inherit parent config", true,
                        Map.of(".clang-format", inputConfig, "nested/.clang-format",
                                text("configuration/inherit/.clang-format")),
                        text("simple-function/after.attach-four.slang")),
                new Scenario("malformed YAML leaves scheme intact", true,
                        Map.of(".clang-format", text("configuration/malformed/.clang-format")), slang),
                new Scenario("unsupported options do not rewrite tokens", true,
                        Map.of(".clang-format", text("configuration/unsupported/.clang-format")), slang),
                new Scenario("Cpp section overrides defaults, other languages ignored", true,
                        Map.of(".clang-format", text("configuration/languages/.clang-format")), twoSpaces));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurations")
    void importsOnlyWhenRequestedAndDoesNotMutateTheScheme(Scenario scenario) {
        EdtTestUtil.runInEdtAndWait(() -> {
            settings.getCustomSettings(SlangCodeStyleSettings.class).READ_CLANG_FORMAT = scenario.readConfig();
            CodeStyleSettingsManager.getInstance(fixture.getProject()).notifyCodeStyleSettingsChanged();
            scenario.files().forEach(fixture::addFileToProject);
            var file = fixture.addFileToProject("nested/deeper/sample.slang", text("simple-function/before.slang"));
            assertFalse(new ClangFormattingService().canFormat(file),
                    "The native mode must never select the external service");
            WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> {
                CodeStyleManager.getInstance(fixture.getProject()).reformat(file);
            });
            assertEquals(scenario.expected(), file.getText());
            WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> {
                CodeStyleManager.getInstance(fixture.getProject()).reformat(file);
            });
            assertEquals(scenario.expected(), file.getText(), "Formatting must be idempotent");
            assertEquals(4, settings.getIndentOptions(slanglsp.language.SlangFileType.INSTANCE).INDENT_SIZE);
            assertTrue(settings.getCustomSettings(SlangCodeStyleSettings.class).BRACES_ON_NEW_LINE);
            assertEquals(100, settings.getRightMargin(SlangLanguage.INSTANCE));
        });
    }

    @Test
    void customizedNativeSettingsWorkWithoutConfigurationOrExecutables() {
        EdtTestUtil.runInEdtAndWait(() -> {
            settings.getIndentOptions(slanglsp.language.SlangFileType.INSTANCE).INDENT_SIZE = 3;
            settings.getCustomSettings(SlangCodeStyleSettings.class).BRACES_ON_NEW_LINE = false;
            CodeStyleSettingsManager.getInstance(fixture.getProject()).notifyCodeStyleSettingsChanged();
            var file = fixture.addFileToProject("custom.slang", text("simple-function/before.slang"));
            WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> {
                CodeStyleManager.getInstance(fixture.getProject()).reformat(file);
            });
            assertEquals(text("simple-function/after.attach-three.slang"), file.getText());
        });
    }

    @Test
    void importsNamespaceIndentationGenericSpacingAndLogicalNot() {
        EdtTestUtil.runInEdtAndWait(() -> {
            settings.getCustomSettings(SlangCodeStyleSettings.class).READ_CLANG_FORMAT = true;
            CodeStyleSettingsManager.getInstance(fixture.getProject()).notifyCodeStyleSettingsChanged();
            fixture.addFileToProject(".clang-format", """
                    NamespaceIndentation: Inner
                    SpaceAfterLogicalNot: true
                    SpacesInAngles: Always
                    KeepEmptyLines:
                      AtStartOfBlock: false
                    """);
            var file = fixture.addFileToProject("nested.slang", """
                    namespace Outer {
                    namespace Inner {

                    struct Box<T> { T value; };
                    bool check(bool value) { return !value; }
                    }
                    }
                    """);
            reformat(file);
            String formatted = file.getText();
            assertTrue(formatted.startsWith("namespace Outer\n{\nnamespace Inner\n{\n    struct Box< T >"), formatted);
            assertTrue(formatted.contains("return ! value;"), formatted);
            reformat(file);
            assertEquals(formatted, file.getText());
        });
    }

    @Test
    void respectsDisableFormatAndClangFormatComments() {
        EdtTestUtil.runInEdtAndWait(() -> {
            settings.getCustomSettings(SlangCodeStyleSettings.class).READ_CLANG_FORMAT = true;
            CodeStyleSettingsManager.getInstance(fixture.getProject()).notifyCodeStyleSettingsChanged();
            String source = """
                    int f( int x ){
                      // clang-format off: intentional layout
                    int y  =    x;
                      /* clang-format on */
                    return  y;
                    }
                    """;
            fixture.addFileToProject("disabled/.clang-format", "DisableFormat: true\n");
            var disabled = fixture.addFileToProject("disabled/sample.slang", source);
            reformat(disabled);
            assertEquals(source, disabled.getText());

            var enabled = fixture.addFileToProject("sample.slang", source);
            reformat(enabled);
            assertTrue(enabled.getText().contains("\nint y  =    x;\n  /* clang-format on */"), enabled.getText());
            assertTrue(enabled.getText().contains("\n    return y;"), enabled.getText());
        });
    }
}
