package slanglsp.formatting.clang

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import java.util.stream.Stream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import slanglsp.FormattingFixtures
import slanglsp.editor.NativeServerSupport
import slanglsp.language.SlangFileType
import slanglsp.language.SlangLanguage
import slanglsp.settings.codestyle.CodeStylePresets.Slang
import slanglsp.settings.codestyle.SlangCodeStyleSettings
import slanglsp.settings.project.ProjectSettings

/** Configuration import must affect real IDE formatting without requiring either executable. */
internal class NativeFormattingConfigurationTest {
    private var fixture: CodeInsightTestFixture? = null
    private var settings: CodeStyleSettings? = null
    private var previous: CodeStyleSettings? = null
    private var wasProject = false

    @BeforeEach
    @Throws(Exception::class)
    fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture =
            factory.createCodeInsightFixture(
                factory
                    .createLightFixtureBuilder(LightProjectDescriptor(), "NativeFormattingConfiguration")
                    .getFixture(),
                TempDirTestFixtureImpl())
        runInEdtAndWait {
            fixture!!.setUp()
            NativeServerSupport.disable(fixture!!.project)
            ModuleRootModificationUtil.addContentRoot(fixture!!.module, fixture!!.tempDirPath)
            val state = ProjectSettings.getInstance(fixture!!.project).getState()
            state.explicitSlangdLocation = fixture!!.tempDirPath + "/no-slangd"
            state.clangFormatLocation = fixture!!.tempDirPath + "/no-clang-format"
            ProjectSettings.getInstance(fixture!!.project).loadState(state)
            val manager = CodeStyleSettingsManager.getInstance(fixture!!.project)
            manager.dropTemporarySettings()
            previous = manager.mainProjectCodeStyle
            wasProject = manager.USE_PER_PROJECT_SETTINGS
            settings = manager.createSettings()
            Slang().apply(settings!!)
            manager.mainProjectCodeStyle = settings
            manager.USE_PER_PROJECT_SETTINGS = true
            settings = CodeStyle.getSettings(fixture!!.project)
        }
    }

    @Test
    fun inheritedConfigAppliesCustomBracesSpacingAndBlankLineLimits() {
        runInEdtAndWait {
            settings!!
                .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                .READ_CLANG_FORMAT = true
            CodeStyleSettingsManager.getInstance(fixture!!.project).notifyCodeStyleSettingsChanged()
            fixture!!.addFileToProject(
                ".clang-format",
                """
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
                    
                    """
                    .trimIndent())
            fixture!!.addFileToProject(
                "nested/.clang-format",
                """
                    BasedOnStyle: InheritParentConfig
                    BraceWrapping:
                      AfterFunction: false
                    
                    """
                    .trimIndent())
            val file =
                fixture!!.addFileToProject(
                    "nested/example.slang",
                    """
                    namespace N {
                    struct S { int field; };
                    class C { int field; };
                    int f(int x) {
                    int values[2] = {1, 2};


                    if (x) { return values[0]; } else { return 0; }
                    switch (x) { case 0: return 1; default: return 2; }
                    }
                    }
                    
                    """
                        .trimIndent())
            reformat(file)
            val formatted = file.text
            Assertions.assertTrue(formatted.startsWith("namespace N\n{\n  struct S\n  {"), formatted)
            Assertions.assertTrue(formatted.contains("class C {"), formatted)
            Assertions.assertTrue(formatted.contains("int f(int x) {"), formatted)
            Assertions.assertTrue(formatted.contains("int values[ 2 ]= {"), formatted)
            Assertions.assertTrue(formatted.contains("if(x)\n    {"), formatted)
            Assertions.assertTrue(formatted.contains("} else\n    {"), formatted)
            Assertions.assertTrue(formatted.contains("switch(x)\n    {\n    case 0:"), formatted)
            Assertions.assertFalse(formatted.contains("\n\n"), formatted)
            reformat(file)
            Assertions.assertEquals(formatted, file.text)
            Assertions.assertEquals(
                -1,
                settings!!
                    .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                    .SPACE_BEFORE_ASSIGNMENT)
        }
    }

    private fun reformat(file: PsiFile) {
        WriteCommandAction.runWriteCommandAction(fixture!!.project) {
            CodeStyleManager.getInstance(fixture!!.project).reformat(file)
        }
    }

    @AfterEach
    @Throws(Exception::class)
    fun tearDown() {
        runInEdtAndWait {
            val manager = CodeStyleSettingsManager.getInstance(fixture!!.project)
            manager.mainProjectCodeStyle = previous
            manager.USE_PER_PROJECT_SETTINGS = wasProject
            fixture!!.tearDown()
        }
    }

    @JvmRecord
    internal data class Scenario(
        val name: String,
        val readConfig: Boolean,
        val files: Map<String, String>,
        val expected: String?
    ) {
        override fun toString(): String {
            return name
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurations")
    fun importsOnlyWhenRequestedAndDoesNotMutateTheScheme(scenario: Scenario) {
        runInEdtAndWait {
            settings!!
                .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                .READ_CLANG_FORMAT = scenario.readConfig
            CodeStyleSettingsManager.getInstance(fixture!!.project).notifyCodeStyleSettingsChanged()
            scenario.files.forEach { (relativePath, fileText) ->
                fixture!!.addFileToProject(relativePath, fileText)
            }
            val file =
                fixture!!.addFileToProject(
                    "nested/deeper/sample.slang", FormattingFixtures.text("simple-function/before.slang"))
            Assertions.assertFalse(
                ClangFormattingService().canFormat(file), "The native mode must never select the external service")
            WriteCommandAction.runWriteCommandAction(fixture!!.project) {
                CodeStyleManager.getInstance(fixture!!.project).reformat(file)
            }
            Assertions.assertEquals(scenario.expected, file.text)
            WriteCommandAction.runWriteCommandAction(fixture!!.project) {
                CodeStyleManager.getInstance(fixture!!.project).reformat(file)
            }
            Assertions.assertEquals(scenario.expected, file.text, "Formatting must be idempotent")
            Assertions.assertEquals(4, settings!!.getIndentOptions(SlangFileType).INDENT_SIZE)
            Assertions.assertTrue(
                settings!!
                    .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                    .BRACES_ON_NEW_LINE)
            Assertions.assertEquals(100, settings!!.getRightMargin(SlangLanguage.INSTANCE))
        }
    }

    @Test
    fun customizedNativeSettingsWorkWithoutConfigurationOrExecutables() {
        runInEdtAndWait {
            settings!!.getIndentOptions(SlangFileType).INDENT_SIZE = 3
            settings!!
                .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                .BRACES_ON_NEW_LINE = false
            CodeStyleSettingsManager.getInstance(fixture!!.project).notifyCodeStyleSettingsChanged()
            val file =
                fixture!!.addFileToProject("custom.slang", FormattingFixtures.text("simple-function/before.slang"))
            WriteCommandAction.runWriteCommandAction(fixture!!.project) {
                CodeStyleManager.getInstance(fixture!!.project).reformat(file)
            }
            Assertions.assertEquals(FormattingFixtures.text("simple-function/after.attach-three.slang"), file.text)
        }
    }

    @Test
    fun importsNamespaceIndentationGenericSpacingAndLogicalNot() {
        runInEdtAndWait {
            settings!!
                .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                .READ_CLANG_FORMAT = true
            CodeStyleSettingsManager.getInstance(fixture!!.project).notifyCodeStyleSettingsChanged()
            fixture!!.addFileToProject(
                ".clang-format",
                """
                    NamespaceIndentation: Inner
                    SpaceAfterLogicalNot: true
                    SpacesInAngles: Always
                    KeepEmptyLines:
                      AtStartOfBlock: false
                    
                    """
                    .trimIndent())
            val file =
                fixture!!.addFileToProject(
                    "nested.slang",
                    """
                    namespace Outer {
                    namespace Inner {

                    struct Box<T> { T value; };
                    bool check(bool value) { return !value; }
                    }
                    }
                    
                    """
                        .trimIndent())
            reformat(file)
            val formatted = file.text
            Assertions.assertTrue(
                formatted.startsWith("namespace Outer\n{\nnamespace Inner\n{\n    struct Box< T >"), formatted)
            Assertions.assertTrue(formatted.contains("return ! value;"), formatted)
            reformat(file)
            Assertions.assertEquals(formatted, file.text)
        }
    }

    @Test
    fun respectsDisableFormatAndClangFormatComments() {
        runInEdtAndWait {
            settings!!
                .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                .READ_CLANG_FORMAT = true
            CodeStyleSettingsManager.getInstance(fixture!!.project).notifyCodeStyleSettingsChanged()
            val source =
                """
                    int f( int x ){
                      // clang-format off: intentional layout
                    int y  =    x;
                      /* clang-format on */
                    return  y;
                    }
                    
                    """
                    .trimIndent()
            fixture!!.addFileToProject("disabled/.clang-format", "DisableFormat: true\n")
            val disabled = fixture!!.addFileToProject("disabled/sample.slang", source)
            reformat(disabled)
            Assertions.assertEquals(source, disabled.text)

            val enabled = fixture!!.addFileToProject("sample.slang", source)
            reformat(enabled)
            Assertions.assertTrue(enabled.text.contains("\nint y  =    x;\n  /* clang-format on */"), enabled.text)
            Assertions.assertTrue(enabled.text.contains("\n    return y;"), enabled.text)
        }
    }

    companion object {
        @JvmStatic
        fun configurations(): Stream<Scenario?> {
            val inputConfig = FormattingFixtures.text("configuration/native/.clang-format")
            val slang = FormattingFixtures.text("simple-function/after.allman-four.slang")
            val twoSpaces = FormattingFixtures.text("simple-function/after.attach-two.slang")
            return Stream.of(
                Scenario("disabled import ignores config", false, mapOf(".clang-format" to inputConfig), slang),
                Scenario("enabled import without a file", true, emptyMap(), slang),
                Scenario("parent directory discovery", true, mapOf(".clang-format" to inputConfig), twoSpaces),
                Scenario("underscore filename", true, mapOf("_clang-format" to inputConfig), twoSpaces),
                Scenario(
                    "nearest file wins",
                    true,
                    mapOf(
                        ".clang-format" to inputConfig,
                        "nested/.clang-format" to FormattingFixtures.text("configuration/nearest/.clang-format")),
                    slang),
                Scenario(
                    "inherit parent config",
                    true,
                    mapOf(
                        ".clang-format" to inputConfig,
                        "nested/.clang-format" to FormattingFixtures.text("configuration/inherit/.clang-format")),
                    FormattingFixtures.text("simple-function/after.attach-four.slang")),
                Scenario(
                    "malformed YAML leaves scheme intact",
                    true,
                    mapOf(".clang-format" to FormattingFixtures.text("configuration/malformed/.clang-format")),
                    slang),
                Scenario(
                    "unsupported options do not rewrite tokens",
                    true,
                    mapOf(".clang-format" to FormattingFixtures.text("configuration/unsupported/.clang-format")),
                    slang),
                Scenario(
                    "Cpp section overrides defaults, other languages ignored",
                    true,
                    mapOf(".clang-format" to FormattingFixtures.text("configuration/languages/.clang-format")),
                    twoSpaces))
        }
    }
}
