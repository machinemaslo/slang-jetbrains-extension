package slanglsp.formatting.native

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInspection.ex.InspectionToolRegistrar
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import com.redhat.devtools.lsp4ij.LanguageServersRegistry
import kotlin.streams.asStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import slanglsp.FormattingFixtures
import slanglsp.TestDependencies
import slanglsp.editor.NativeServerSupport
import slanglsp.formatting.clang.ClangFormattingService
import slanglsp.language.SlangLanguage
import slanglsp.language.SlangLexer
import slanglsp.settings.codestyle.CodeStylePresets.Slang
import slanglsp.settings.codestyle.SlangCodeStyleSettings
import slanglsp.settings.project.ProjectSettings
import slanglsp.useTemporarySettings

internal class NativeFormattingTest {
    private var fixture: CodeInsightTestFixture? = null
    private var settings: CodeStyleSettings? = null

    @BeforeEach
    @Throws(Exception::class)
    fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture =
            factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("NativeFormatting").getFixture(), TempDirTestFixtureImpl())
        runInEdtAndWait {
            fixture!!.setUp()
            ModuleRootModificationUtil.addContentRoot(fixture!!.module, fixture!!.tempDirPath)
            val definition = LanguageServersRegistry.getInstance().getServerDefinition("slanglsp.SlangLanguageServer")
            Assertions.assertNotNull(definition)
            definition!!.setEnabled(false, fixture!!.project)
            val state = ProjectSettings.getInstance(fixture!!.project).getState()
            state.clangFormatLocation = fixture!!.tempDirPath + "/missing-clang-format"
            ProjectSettings.getInstance(fixture!!.project).loadState(state)
            val manager = CodeStyleSettingsManager.getInstance(fixture!!.project)
            settings = manager.createSettings()
            Slang().apply(settings!!)
            manager.useTemporarySettings(settings!!)
        }
    }

    @AfterEach
    @Throws(Exception::class)
    fun tearDown() {
        runInEdtAndWait {
            CodeStyleSettingsManager.getInstance(fixture!!.project).dropTemporarySettings()
            NativeServerSupport.enable(fixture!!.project)
            fixture!!.tearDown()
        }
    }

    @Test
    fun presetsAndPlatformModelAgreeWithBackgroundEngine() {
        val input = FormattingFixtures.text("brace-style/before.slang")
        val allman = FormattingFixtures.text("brace-style/after.allman.slang")
        assertFormat(input, allman)
        settings!!.getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java).BRACES_ON_NEW_LINE =
            false
        settings!!.getCommonSettings(SlangLanguage.INSTANCE).ELSE_ON_NEW_LINE = false
        assertFormat(input, FormattingFixtures.text("brace-style/after.attach.slang"))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings =
            [
                "directives",
                "shader-comments",
                "generics",
                "operators",
                "loop",
                "switch",
                "multiline-parameters",
                "bitwise",
                "conditional-return",
                "initializer"])
    fun preservesTokensDirectivesCommentsAndGenerics(scenario: String?) {
        val source = FormattingFixtures.text("token-preservation/$scenario.slang")
        val formatted = NativeFormatting.format(source, settings!!)
        if (source.startsWith("#define")) {
            Assertions.assertTrue(formatted.startsWith(source.substring(0, source.indexOf("#if"))), formatted)
        }
        assertFormat(source, formatted)
    }

    @Test
    fun wrappingAndTabsUseSlangSettings() {
        settings!!.setRightMargin(SlangLanguage.INSTANCE, 40)
        val indent = settings!!.getCommonSettings(SlangLanguage.INSTANCE).indentOptions
        Assertions.assertNotNull(indent)
        indent!!.USE_TAB_CHARACTER = true
        indent.TAB_SIZE = 4
        val source = FormattingFixtures.text("tabs/before.slang")
        val formatted = NativeFormatting.format(source, settings!!)
        Assertions.assertTrue(formatted.contains("\n\tcall("), formatted)
        Assertions.assertTrue(formatted.contains(",\n"), formatted)
        Assertions.assertEquals(formatted, NativeFormatting.format(formatted, settings!!))
        assertFormat(source, formatted)
    }

    @Test
    fun braceAndSpacingOptionsControlActualFormatting() {
        val custom = settings!!.getCustomSettings(SlangCodeStyleSettings::class.java)
        custom.TYPE_BRACES = 1
        custom.FUNCTION_BRACES = 2
        custom.CONTROL_BRACES = 1
        val common = settings!!.getCommonSettings(SlangLanguage.INSTANCE)
        common.ELSE_ON_NEW_LINE = false
        common.SPACE_BEFORE_IF_PARENTHESES = false
        common.SPACE_AROUND_ADDITIVE_OPERATORS = false
        common.SPACE_AROUND_EQUALITY_OPERATORS = false
        common.SPACE_AROUND_ASSIGNMENT_OPERATORS = false
        assertFormat(
            FormattingFixtures.text("brace-spacing/before.slang"), FormattingFixtures.text("brace-spacing/after.slang"))
    }

    @Test
    fun specializedBraceOptionsOverrideTheirInheritedPlacement() {
        val custom = settings!!.getCustomSettings(SlangCodeStyleSettings::class.java)
        custom.TYPE_BRACES = 2
        custom.NAMESPACE_BRACES = 1
        custom.BUFFER_BRACES = 3
        custom.CONTROL_BRACES = 2
        custom.SWITCH_BRACES = 1
        custom.CASE_BRACES = 3
        custom.INITIALIZER_BRACES = 1

        val source =
            """
                namespace Lighting {
                struct Material { float roughness; };
                cbuffer Scene { float exposure; }
                float intensity(int mode) {
                float values[2] = {1.0, 2.0};
                switch (mode) {
                case 0: { return values[0]; }
                default: return exposure;
                }
                }
                }
                
                """
                .trimIndent()
        val formatted = NativeFormatting.format(source, settings!!)
        Assertions.assertTrue(formatted.startsWith("namespace Lighting {\n"), formatted)
        Assertions.assertTrue(formatted.contains("struct Material\n    {"), formatted)
        Assertions.assertTrue(formatted.contains("cbuffer Scene{"), formatted)
        Assertions.assertTrue(formatted.contains("switch (mode) {"), formatted)
        Assertions.assertTrue(formatted.contains("case 0:{"), formatted)
        Assertions.assertTrue(formatted.contains("values[2] = {"), formatted)
        assertFormat(source, formatted)
    }

    @ParameterizedTest(name = "operator spaces: {0}")
    @ValueSource(booleans = [true, false])
    fun adjacentOperatorsRemainSeparateWhenSpacingChanges(spaces: Boolean) {
        val common = settings!!.getCommonSettings(SlangLanguage.INSTANCE)
        common.SPACE_AROUND_ADDITIVE_OPERATORS = spaces
        common.SPACE_AROUND_ASSIGNMENT_OPERATORS = spaces
        assertFormat(
            FormattingFixtures.text("token-preservation/operators.slang"),
            FormattingFixtures.text("operators/after." + (if (spaces) "spaced" else "compact") + ".slang"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["shader", "types"])
    fun slangDeclarationsHaveGoldenOutput(scenario: String) {
        assertFormat(
            FormattingFixtures.text("$scenario/before.slang"),
            FormattingFixtures.text(scenario + (if (scenario == "shader") "/after.native.slang" else "/after.slang")))
    }

    @Test
    fun assemblyContentsArePreserved() {
        val source = FormattingFixtures.text("assembly/before.slang")
        val assembly = source.substring(source.indexOf('{', source.indexOf("spirv_asm")), source.lastIndexOf(';'))
        val formatted = NativeFormatting.format(source, settings!!)
        Assertions.assertTrue(
            formatted.contains(assembly), "The SPIR-V assembly block must remain byte-for-byte unchanged")
        assertFormat(source, formatted)
    }

    @Test
    fun blankLineAndWrappingOptionsAreApplied() {
        val common = settings!!.getCommonSettings(SlangLanguage.INSTANCE)
        common.BLANK_LINES_AROUND_METHOD = 1
        common.KEEP_BLANK_LINES_IN_CODE = 0
        common.KEEP_BLANK_LINES_IN_DECLARATIONS = 1
        common.KEEP_BLANK_LINES_BEFORE_RBRACE = 0
        assertFormat(
            FormattingFixtures.text("blank-lines/before.slang"), FormattingFixtures.text("blank-lines/after.slang"))
        common.CALL_PARAMETERS_WRAP = 0
        common.ASSIGNMENT_WRAP = 0
        common.KEEP_LINE_BREAKS = false
        settings!!.setRightMargin(SlangLanguage.INSTANCE, 20)
        val call = FormattingFixtures.text("wrapping/before.slang")
        val unwrapped = NativeFormatting.format(call, settings!!)
        Assertions.assertTrue(unwrapped.contains("call(firstArgument, secondArgument, thirdArgument);"), unwrapped)
        common.CALL_PARAMETERS_WRAP = CommonCodeStyleSettings.WRAP_ALWAYS
        val wrapped = NativeFormatting.format(call, settings!!)
        Assertions.assertTrue(wrapped.contains(",\n        secondArgument,\n        thirdArgument"), wrapped)
        Assertions.assertEquals(wrapped, NativeFormatting.format(wrapped, settings!!))
    }

    @Test
    fun multilineAlignmentAndSwitchIndentationFollowSettings() {
        val common = settings!!.getCommonSettings(SlangLanguage.INSTANCE)
        common.ALIGN_MULTILINE_PARAMETERS_IN_CALLS = true
        common.CALL_PARAMETERS_WRAP = 0
        val source = FormattingFixtures.text("alignment/before.slang")
        val formatted = NativeFormatting.format(source, settings!!)
        val lines =
            formatted
                .lineSequence()
                .asStream()
                .dropWhile { line: String? -> !line!!.contains("call(firstArgument") }
                .toList()
        val firstColumn =
            lines
                .stream()
                .filter { line: String? -> line!!.contains("firstArgument") }
                .findFirst()
                .orElseThrow()!!
                .indexOf("firstArgument")
        val secondColumn =
            lines
                .stream()
                .filter { line: String? -> line!!.contains("secondArgument") }
                .findFirst()
                .orElseThrow()!!
                .indexOf("secondArgument")
        Assertions.assertEquals(firstColumn, secondColumn, formatted)
        assertFormat(source, formatted)
        common.INDENT_CASE_FROM_SWITCH = false
        common.INDENT_BREAK_FROM_CASE = false
        val switched = NativeFormatting.format(FormattingFixtures.text("switch-indentation/before.slang"), settings!!)
        Assertions.assertTrue(switched.contains("\n    case 0:"), switched)
        Assertions.assertTrue(switched.contains("\n    break;"), switched)
        Assertions.assertEquals(switched, NativeFormatting.format(switched, settings!!))
    }

    @Test
    fun platformFormattingInspectionFollowsEngineSelection() {
        runInEdtAndWait {
            val file =
                fixture!!.addFileToProject("inspection.slang", FormattingFixtures.text("assignment/before.slang"))
            fixture!!.configureFromExistingVirtualFile(file.virtualFile)
            val inspectionId = "IncorrectFormatting"
            val inspection =
                checkNotNull(
                    InspectionToolRegistrar.getInstance().createTools().singleOrNull { it.shortName == inspectionId }) {
                        "The platform formatting inspection is not registered"
                    }
            fixture!!.enableInspections(inspection.tool)
            Assertions.assertTrue(fixture!!.doHighlighting().any { it.inspectionToolId == inspectionId })
            settings!!.getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java).USE_CLANG_FORMAT =
                true
            DaemonCodeAnalyzer.getInstance(fixture!!.project).restart(file, "Slang formatting engine changed")
            Assertions.assertTrue(fixture!!.doHighlighting().none { it.inspectionToolId == inspectionId })
        }
    }

    @Test
    fun shaderAttributesAndPublicDeclarationsUseSlangAwareFormatting() {
        runInEdtAndWait {
            val state = ProjectSettings.getInstance(fixture!!.project).getState()
            state.clangFormatLocation = TestDependencies.clangFormat()
            ProjectSettings.getInstance(fixture!!.project).loadState(state)
            for (scenario in mutableListOf("attributes", "public")) {
                val source = FormattingFixtures.text("shader-preservation/$scenario.slang")
                val file = fixture!!.addFileToProject("shader" + System.nanoTime() + ".slang", source)
                fixture!!.configureFromExistingVirtualFile(file.virtualFile)
                Assertions.assertFalse(
                    ClangFormattingService().canFormat(file), "Native formatting must not select the external service")
                Assertions.assertEquals(source, NativeFormatting.format(source, settings!!))
            }
        }
    }

    @Test
    fun selectionFormattingOnlyChangesTargetFunction() {
        runInEdtAndWait {
            val source = FormattingFixtures.text("selection-function/before.slang")
            val file = fixture!!.addFileToProject("multiple.slang", source)
            fixture!!.configureFromExistingVirtualFile(file.virtualFile)
            val function = source.indexOf("float badlyFormatted")
            WriteCommandAction.runWriteCommandAction(fixture!!.project) {
                CodeStyleManager.getInstance(fixture!!.project).reformatText(file, function, source.length)
            }
            val expected =
                (source.substring(0, function) + NativeFormatting.format(source.substring(function), settings!!))
            Assertions.assertEquals(
                expected,
                fixture!!.editor.document.text,
                "Selection formatting must leave the preceding declaration unchanged")
        }
    }

    @Test
    fun selectionFormattingLeavesOtherStatementsAlone() {
        runInEdtAndWait {
            val source = FormattingFixtures.text("selection/before.slang")
            val file = fixture!!.addFileToProject("selection.slang", source)
            val start = source.indexOf("static float second")
            val end = source.indexOf("static float third")
            WriteCommandAction.runWriteCommandAction(fixture!!.project) {
                CodeStyleManager.getInstance(fixture!!.project).reformatText(file, start, end)
            }
            Assertions.assertEquals(FormattingFixtures.text("selection/after.slang"), file.text)
        }
    }

    private fun assertFormat(source: String, expected: String) {
        Assertions.assertEquals(expected, NativeFormatting.format(source, settings!!))
        Assertions.assertEquals(tokens(source), tokens(expected), "Formatting must preserve tokens")
        Assertions.assertEquals(
            expected, NativeFormatting.format(expected, settings!!), "Formatting must be idempotent")
        runInEdtAndWait {
            val file = fixture!!.addFileToProject("sample" + System.nanoTime() + ".slang", source)
            WriteCommandAction.runWriteCommandAction(fixture!!.project) {
                CodeStyleManager.getInstance(fixture!!.project).reformat(file)
            }
            val document = PsiDocumentManager.getInstance(fixture!!.project).getDocument(file)
            Assertions.assertNotNull(document)
            Assertions.assertEquals(expected, document!!.text)
        }
    }

    companion object {
        private fun tokens(text: String): MutableList<String?> {
            val result = ArrayList<String?>()
            val lexer = SlangLexer()
            lexer.start(text)
            while (lexer.tokenType != null) {
                if (lexer.tokenType !== TokenType.WHITE_SPACE) {
                    result.add(text.substring(lexer.tokenStart, lexer.tokenEnd))
                }
                lexer.advance()
            }
            return result
        }
    }
}
