package slanglsp.editor;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UsageTargetTest {
    @Test
    @org.junit.jupiter.api.Timeout(90)
    void caretTargetFindsCrossFileCallsWithNativeServer() throws Exception {
        String executable = slanglsp.TestDependencies.slangd();

        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("SlangUsageSearch").getFixture(), new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(fixture::setUp);

        try {
            com.intellij.psi.PsiElement[] target = new com.intellij.psi.PsiElement[2];

            EdtTestUtil.runInEdtAndWait(() -> {
                com.intellij.openapi.roots.ModuleRootModificationUtil.addContentRoot(
                        fixture.getModule(), fixture.getTempDirPath());

                var settings = slanglsp.settings.ProjectSettings.getInstance(fixture.getProject()).getState();
                settings.explicitSlangdLocation = java.nio.file.Path.of(executable).getParent().toString();
                settings.useLocalVcpkgSlangd = false;
                settings.additionalIncludePaths = java.util.List.of(fixture.getTempDirPath());
                slanglsp.settings.ProjectSettings.getInstance(fixture.getProject()).loadState(settings);
                NativeServerSupport.enable(fixture.getProject());

                var declaration = fixture.addFileToProject("quartzContour.slang",
                        """
                        module quartzContour;
                        public float4 quartzContour(float2 p) { return float4(p, 0, 1); }
                        """);
                fixture.addFileToProject("opticalContext.slang",
                        """
                                module opticalContext;
                                import quartzContour;
                                public float4 analyticContour(float2 p) { return quartzContour(p); }
                                """);

                fixture.configureFromExistingVirtualFile(declaration.getVirtualFile());
                fixture.getEditor().getCaretModel().moveToOffset(declaration.getText().lastIndexOf("quartzContour") + 3);
                target[0] = TargetElementUtil.findTargetElement(
                        fixture.getEditor(), TargetElementUtil.ELEMENT_NAME_ACCEPTED);
                assertNotNull(target[0]);

                var grid = fixture.addFileToProject("page/common/grid_traversal.slang",
                        """
                                module grid_traversal;
                                public float helper(float v)
                                {
                                \treturn v;
                                }
                                public bool intersect_box(
                                \tfloat3 origin,
                                \tout float near)
                                {
                                \tnear = origin.x;
                                \treturn true;
                                }
                                """);
                fixture.addFileToProject("page/common/volume.slang",
                        """
                                module volume;
                                import page.common.grid_traversal;
                                public bool volume(float3 p) {
                                \tfloat t;
                                \tif (!intersect_box(p, t)) return false;
                                \treturn true;
                                }
                                """);
                fixture.addFileToProject("page/resolve/visibility.slang",
                        """
                                module visibility;
                                import page.common.grid_traversal;
                                public bool visible(float3 p) {
                                \tfloat t;
                                \t\tif (!intersect_box(p, t)) return false;
                                \t\treturn intersect_box(p, t);
                                }
                                """);
                target[1] = grid.findElementAt(grid.getText().indexOf("intersect_box"));

                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
            });

            com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(() -> {
                var handler = new SlangFindUsagesHandlerFactory().createFindUsagesHandler(target[0], false);
                var options = ReadActions.compute(
                        () -> new com.intellij.find.findUsages.FindUsagesOptions(fixture.getProject()));
                options.searchScope = com.intellij.psi.search.GlobalSearchScope.projectScope(fixture.getProject());
                var usages = new java.util.ArrayList<com.intellij.usageView.UsageInfo>();

                assertTrue(handler.processElementUsages(target[0], usages::add, options));
                assertEquals(1, usages.size());
                ReadActions.run(() -> {
                    var usageFile = usages.getFirst().getFile();
                    assertNotNull(usageFile);
                    assertEquals("opticalContext.slang", usageFile.getName());
                });
                assertFalse(UsageSearch.isUnused(target[0]));

                usages.clear();
                assertTrue(UsageSearch.process(target[1], options.searchScope, usages::add));
                assertEquals(3, usages.size(), "All imported intersect_box calls must resolve to one declaration");
                assertFalse(UsageSearch.isUnused(target[1]), "A used declaration must not be grayed out");

                var annotator = new UnusedSymbolsAnnotator();
                var input = ReadActions.compute(() -> annotator.collectInformation(target[1].getContainingFile()));
                assertNotNull(input);

                var result = annotator.doAnnotate(input);
                assertNotNull(result);

                var unused = result.unused().stream().map(UnusedSymbolsAnnotator.Candidate::name).toList();
                assertTrue(unused.contains("helper"), unused.toString());
                assertFalse(unused.contains("intersect_box"), unused.toString());
            }, new com.intellij.openapi.progress.EmptyProgressIndicator());
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }

    @Test
    void usageActionsRecognizeSlangIdentifiersAtTheCaret() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var fixture = factory.createCodeInsightFixture(
                factory.createLightFixtureBuilder("SlangUsageTargets").getFixture(), new TempDirTestFixtureImpl());
        EdtTestUtil.runInEdtAndWait(fixture::setUp);

        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                for (String code : new String[] {
                        "module quartzContour;\npublic float4 quartz<caret>Contour(float2 p) { return 0; }",
                        "public float4 <caret>quartzContour(float2 p) { return 0; }",
                        "public float4 quartzContour<caret>(float2 p) { return 0; }",
                        "float4 f() { return quartz<caret>Contour(0); }",
                        "struct Optical<caret>Context {}",
                        "float unused<caret>Value;"
                }) {
                    fixture.configureByText("test.slang", code);
                    var target = TargetElementUtil.findTargetElement(
                            fixture.getEditor(), TargetElementUtil.ELEMENT_NAME_ACCEPTED);
                    assertNotNull(target, code);
                    assertTrue(new SlangFindUsagesHandlerFactory().canFindUsages(target), code);
                }

                for (String code : new String[] {
                        "module quartz<caret>Contour;",
                        "// quartz<caret>Contour",
                        "float f() { return \"quartz<caret>Contour\"; }",
                        "pub<caret>lic float f() { return 0; }"
                }) {
                    fixture.configureByText("test.slang", code);
                    assertNull(TargetElementUtil.findTargetElement(
                            fixture.getEditor(), TargetElementUtil.ELEMENT_NAME_ACCEPTED), code);
                }
            });
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }
}
