package slanglsp.editor.inspections.naming

import com.intellij.application.options.CodeStyle
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.usages.UsageViewManager
import java.nio.file.Path
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import slanglsp.TestDependencies
import slanglsp.editor.AnnotationRecorder
import slanglsp.editor.NativeServerSupport
import slanglsp.editor.inspections.naming.NamingParameters.collect
import slanglsp.settings.codestyle.NamingRule
import slanglsp.settings.codestyle.NamingRule.Companion.defaults
import slanglsp.settings.codestyle.SlangCodeStyleSettings
import slanglsp.settings.project.ProjectSettings

internal class NamingInspectionTest {
    @Test
    fun parameterDiscoverySkipsUnnamedTypesAndHandlesGenerics() {
        val text = "<element_type, let Count : int>(const MyType, MyType namedValue, float scalarValue : TEXCOORD0)"
        val candidates = LinkedHashMap<Int?, NamingAnnotator.Candidate?>()
        collect(text, 0, text.length, candidates)
        Assertions.assertEquals(
            mutableListOf<String?>("element_type", "Count", "namedValue", "scalarValue"),
            candidates.values.filterNotNull().map { it.name })
        Assertions.assertEquals(NamingRule.Category.GENERIC_PARAMETERS, candidates.values.first()?.category)
    }

    @Test
    @Timeout(90)
    @Throws(Exception::class)
    fun serverDeclarationsHighlightsProfileAndRenameAction() {
        val executable = TestDependencies.slangd()
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture =
            factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("NamingInspection").getFixture(), TempDirTestFixtureImpl())
        runInEdtAndWait { fixture.setUp() }
        try {
            val source =
                """
                    struct TestStruct { float fieldValue; };
                    float testFunction(float inputValue) {
                        float localValue = inputValue;
                        return localValue;
                    }
                    float useValue() { return testFunction(1); }
                    [shader("compute")]
                    [numthreads(1, 1, 1)]
                    void computeMain() {}
                    
                    """
                    .trimIndent()
            val files = arrayOfNulls<PsiFile>(1)
            val annotator = NamingAnnotator()
            val input = arrayOfNulls<NamingAnnotator.Input>(1)
            runInEdtAndWait {
                ModuleRootModificationUtil.addContentRoot(fixture.module, fixture.tempDirPath)
                val state = ProjectSettings.getInstance(fixture.project).getState()
                state.explicitSlangdLocation = Path.of(executable).parent.toString()
                state.useLocalVcpkgSlangd = false
                ProjectSettings.getInstance(fixture.project).loadState(state)
                files[0] = fixture.addFileToProject("naming.slang", source)
                NativeServerSupport.enable(fixture.project)
                fixture.enableInspections(InconsistentNamingInspection())
                CodeStyle.getSettings(files[0]!!)
                    .getCustomSettings<SlangCodeStyleSettings?>(SlangCodeStyleSettings::class.java)
                    .setNamingRules(defaults(false))
                input[0] = annotator.collectInformation(files[0]!!)
                Assertions.assertNotNull(input[0])
            }
            ProgressManager.getInstance()
                .runProcess(
                    {
                        val batch = annotator.doAnnotate(checkNotNull(input[0]))
                        Assertions.assertNotNull(batch, "An unopened file must not require an editor cache")
                        Assertions.assertNull(batch!!.failure)
                        Assertions.assertTrue(
                            batch.candidates.stream().anyMatch { candidate: NamingAnnotator.Candidate? ->
                                candidate!!.name == "testFunction"
                            })
                    },
                    EmptyProgressIndicator())
            runInEdtAndWait {
                fixture.configureFromExistingVirtualFile(files[0]!!.virtualFile)
                input[0] = annotator.collectInformation(files[0]!!, fixture.editor, false)
            }
            val result = arrayOfNulls<NamingAnnotator.Result>(1)
            ProgressManager.getInstance()
                .runProcess({ result[0] = annotator.doAnnotate(checkNotNull(input[0])) }, EmptyProgressIndicator())
            Assertions.assertNotNull(result[0])
            val expectedCategories =
                mapOf(
                    "TestStruct" to NamingRule.Category.TYPES,
                    "fieldValue" to NamingRule.Category.FIELDS,
                    "testFunction" to NamingRule.Category.FUNCTIONS,
                    "inputValue" to NamingRule.Category.PARAMETERS,
                    "localValue" to NamingRule.Category.LOCALS,
                )

            for (expected in expectedCategories.entries) {
                Assertions.assertTrue(
                    result[0]!!.candidates.stream().anyMatch { candidate: NamingAnnotator.Candidate? ->
                        candidate!!.name == expected.key && candidate.category == expected.value
                    },
                    expected.toString() + ": " + result[0]!!.candidates)
            }
            Assertions.assertFalse(
                result[0]!!.candidates.stream().anyMatch { c: NamingAnnotator.Candidate? -> c!!.name == "computeMain" })
            runInEdtAndWait {
                val holder = AnnotationRecorder()
                annotator.apply(files[0]!!, result[0], holder.holder())
                val unusedMessage = result[0]!!.unusedMessages[source.indexOf("TestStruct")]
                Assertions.assertNotNull(unusedMessage)
                val unusedNaming =
                    holder
                        .stream()
                        .filter { a -> a.startOffset == source.indexOf("TestStruct") }
                        .findFirst()
                        .orElseThrow()
                Assertions.assertNull(unusedNaming.message, "Unused takes precedence only in hover text")
                Assertions.assertNull(unusedNaming.tooltip, "Naming must not own the unused hover")
                Assertions.assertEquals(HighlightSeverity.INFORMATION, unusedNaming.severity)
                Assertions.assertEquals(2, unusedNaming.endOffset - unusedNaming.startOffset)
                val unusedRename = unusedNaming.quickFixes.first()
                Assertions.assertEquals("Rename to 'test_struct'", unusedRename.quickFix.text)
                Assertions.assertEquals("TestStruct".length, unusedRename.textRange?.length)
                Assertions.assertTrue(unusedRename.quickFix.isAvailable(fixture.project, fixture.editor, files[0]))
                val warning =
                    holder
                        .stream()
                        .filter { a -> a.startOffset == source.indexOf("testFunction") }
                        .findFirst()
                        .orElseThrow()
                Assertions.assertEquals(2, warning.endOffset - warning.startOffset)
                Assertions.assertEquals(
                    "Name 'testFunction' does not match rule 'Global functions'. Suggested name is 'test_function'.",
                    warning.message)
                val fix = warning.quickFixes.first()
                Assertions.assertEquals("Rename to 'test_function'", fix.quickFix.text)
                Assertions.assertEquals("testFunction".length, fix.textRange?.length)
                Assertions.assertTrue(fix.quickFix.isAvailable(fixture.project, fixture.editor, files[0]))
                fix.quickFix.invoke(fixture.project, fixture.editor, files[0])
                val views = UsageViewManager.getInstance(fixture.project)
                PlatformTestUtil.waitWithEventsDispatching(
                    "Naming rename applied", { fixture.editor.document.text.contains("test_function") }, 30)
                Assertions.assertNull(views.selectedUsageView, "Ordinary naming fixes must not open a preview")
                Assertions.assertEquals(source.replace("testFunction", "test_function"), fixture.editor.document.text)
                UndoManager.getInstance(fixture.project).undo(null)
                Assertions.assertEquals(source, fixture.editor.document.text)

                val profile = InspectionProfileManager.getInstance(fixture.project).currentProfile
                profile.setToolEnabled(InconsistentNamingInspection.ID, false, fixture.project)
                Assertions.assertNull(annotator.collectInformation(files[0]!!))
                profile.setToolEnabled(InconsistentNamingInspection.ID, true, fixture.project)
            }
        } finally {
            try {
                NativeServerSupport.stop(fixture.project)
            } finally {
                runInEdtAndWait { fixture.tearDown() }
            }
        }
    }
}
