package slanglsp.editor.navigation

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import slanglsp.TestDependencies
import slanglsp.editor.NativeServerSupport
import slanglsp.editor.inspections.unused.UnusedSymbolsAnnotator
import slanglsp.editor.navigation.UsageSearch.isUnused
import slanglsp.editor.navigation.UsageSearch.process

internal class UsageTargetTest {
    @Test
    fun missingUsageTargetIsNotAnUnusedSymbol() {
        Assertions.assertFalse(isUnused(null))
        Assertions.assertTrue(
            process(null, GlobalSearchScope.EMPTY_SCOPE) {
                throw AssertionError("Missing target must not produce a usage")
            })
    }

    @Test
    @Timeout(90)
    @Throws(Exception::class)
    fun caretTargetFindsCrossFileCallsWithNativeServer() {
        val executable = TestDependencies.slangd()

        val fixture = NativeServerSupport.isolatedFixture("SlangUsageSearch")
        runInEdtAndWait { fixture.setUp() }

        try {
            val target = arrayOfNulls<PsiElement>(2)

            runInEdtAndWait {
                NativeServerSupport.configure(fixture, executable)

                val declaration =
                    fixture.addFileToProject(
                        "quartzContour.slang",
                        """
                        module quartzContour;
                        public float4 quartzContour(float2 p) { return float4(p, 0, 1); }
                        
                        """
                            .trimIndent())
                fixture.addFileToProject(
                    "opticalContext.slang",
                    """
                                module opticalContext;
                                import quartzContour;
                                public float4 analyticContour(float2 p) { return quartzContour(p); }
                                
                                """
                        .trimIndent())

                fixture.configureFromExistingVirtualFile(declaration.virtualFile)
                fixture.editor.caretModel.moveToOffset(declaration.text.lastIndexOf("quartzContour") + 3)
                target[0] = TargetElementUtil.findTargetElement(fixture.editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED)
                Assertions.assertNotNull(target[0])

                val grid =
                    fixture.addFileToProject(
                        "page/common/grid_traversal.slang",
                        """
                                module grid_traversal;
                                public float helper(float v)
                                {
                                ${'\t'}return v;
                                }
                                public bool intersect_box(
                                ${'\t'}float3 origin,
                                ${'\t'}out float near)
                                {
                                ${'\t'}near = origin.x;
                                ${'\t'}return true;
                                }
                                
                                """
                            .trimIndent())
                fixture.addFileToProject(
                    "page/common/volume.slang",
                    """
                                module volume;
                                import page.common.grid_traversal;
                                public bool volume(float3 p) {
                                ${'\t'}float t;
                                ${'\t'}if (!intersect_box(p, t)) return false;
                                ${'\t'}return true;
                                }
                                
                                """
                        .trimIndent())
                fixture.addFileToProject(
                    "page/resolve/visibility.slang",
                    """
                                module visibility;
                                import page.common.grid_traversal;
                                public bool visible(float3 p) {
                                ${'\t'}float t;
                                ${'\t'}${'\t'}if (!intersect_box(p, t)) return false;
                                ${'\t'}${'\t'}return intersect_box(p, t);
                                }
                                
                                """
                        .trimIndent())
                target[1] = grid.findElementAt(grid.text.indexOf("intersect_box"))
                FileDocumentManager.getInstance().saveAllDocuments()
            }

            ProgressManager.getInstance()
                .runProcess(
                    {
                        val handler = IndexedFindUsagesHandlerFactory().createFindUsagesHandler(target[0]!!, false)
                        val options = runReadActionBlocking { FindUsagesOptions(fixture.project) }
                        options.searchScope = GlobalSearchScope.projectScope(fixture.project)
                        val usages = ArrayList<UsageInfo?>()

                        Assertions.assertTrue(
                            handler.processElementUsages(
                                target[0]!!, Processor { e: UsageInfo? -> usages.add(e) }, options))
                        Assertions.assertEquals(1, usages.size)
                        run {
                            val usageFile = usages.first()!!.file
                            Assertions.assertNotNull(usageFile)
                            Assertions.assertEquals("opticalContext.slang", usageFile!!.name)
                        }
                        Assertions.assertFalse(isUnused(target[0]))

                        usages.clear()
                        Assertions.assertTrue(
                            process(target[1], options.searchScope, Processor { e: UsageInfo? -> usages.add(e) }))
                        Assertions.assertEquals(
                            3, usages.size, "All imported intersect_box calls must resolve to one declaration")
                        Assertions.assertFalse(isUnused(target[1]), "A used declaration must not be grayed out")

                        val annotator = UnusedSymbolsAnnotator()
                        val input = runReadActionBlocking {
                            annotator.collectInformation(target[1]!!.containingFile)
                        }
                        Assertions.assertNotNull(input)

                        val result = annotator.doAnnotate(checkNotNull(input))
                        Assertions.assertNotNull(result)

                        val unused = checkNotNull(result).unused.map { it.name }
                        Assertions.assertTrue(unused.contains("helper"), unused.toString())
                        Assertions.assertFalse(unused.contains("intersect_box"), unused.toString())
                    },
                    EmptyProgressIndicator())
        } finally {
            try {
                NativeServerSupport.stop(fixture.project)
            } finally {
                runInEdtAndWait { fixture.tearDown() }
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun usageActionsRecognizeSlangIdentifiersAtTheCaret() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture =
            factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("SlangUsageTargets").getFixture(), TempDirTestFixtureImpl())
        runInEdtAndWait { fixture.setUp() }

        try {
            runInEdtAndWait {
                for (code in
                    arrayOf(
                        "module quartzContour;\npublic float4 quartz<caret>Contour(float2 p) { return 0; }",
                        "public float4 <caret>quartzContour(float2 p) { return 0; }",
                        "public float4 quartzContour<caret>(float2 p) { return 0; }",
                        "float4 f() { return quartz<caret>Contour(0); }",
                        "struct Optical<caret>Context {}",
                        "float unused<caret>Value;")) {
                    fixture.configureByText("test.slang", code)
                    val target =
                        TargetElementUtil.findTargetElement(fixture.editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED)
                    Assertions.assertNotNull(target, code)
                    Assertions.assertTrue(IndexedFindUsagesHandlerFactory().canFindUsages(target!!), code)
                }
                for (code in
                    arrayOf(
                        "module quartz<caret>Contour;",
                        "// quartz<caret>Contour",
                        "float f() { return \"quartz<caret>Contour\"; }",
                        "pub<caret>lic float f() { return 0; }")) {
                    fixture.configureByText("test.slang", code)
                    Assertions.assertNull(
                        TargetElementUtil.findTargetElement(fixture.editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED),
                        code)
                }
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }
}
