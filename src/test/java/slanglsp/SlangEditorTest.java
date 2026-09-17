package slanglsp;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFileFactory;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlangEditorTest {
    private IdeaProjectTestFixture fixture;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path documentationDirectory;

    @BeforeEach void setUp() throws Exception {
        fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("SlangEditorTest").getFixture();
        com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(() -> fixture.setUp());
    }

    @AfterEach void tearDown() throws Exception {
        if (fixture != null) com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(() -> fixture.tearDown());
    }

    @Test void moduleHoverSourceIsOnlyTheIdentifier() {
        ReadAction.run(() -> {
            String code = "import lighting;\n\nfloat4 main() { return lightingValue; }\n";
            var file = PsiFileFactory.getInstance(fixture.getProject())
                    .createFileFromText("shader.slang", SlangFileType.INSTANCE, code);
            int start = code.indexOf("lighting");
            for (int offset = start; offset < start + "lighting".length(); offset++) {
                var element = file.findElementAt(offset);
                assertNotNull(element);
                assertEquals("lighting", element.getText());
                assertEquals(new TextRange(start, start + "lighting".length()), element.getTextRange());
            }
            assertEquals(";", file.findElementAt(start + "lighting".length()).getText());
            assertEquals("main", file.findElementAt(code.indexOf("main") + 1).getText());
            assertEquals(code, file.getText());
        });
    }

    @Test void commentsAndStringsRemainSeparateFromIdentifiers() {
        ReadAction.run(() -> {
            String code = "/* import hidden; */\nimport visible;\n#include \"shared.slang\"\n";
            var file = PsiFileFactory.getInstance(fixture.getProject())
                    .createFileFromText("shader.slang", SlangFileType.INSTANCE, code);
            assertEquals("/* import hidden; */", file.findElementAt(code.indexOf("hidden")).getText());
            assertEquals("visible", file.findElementAt(code.indexOf("visible")).getText());
            assertEquals("\"shared.slang\"", file.findElementAt(code.indexOf("shared")).getText());
        });
    }

    @Test void highlightingFollowsChangesToTheEditorScheme() {
        var scheme = (com.intellij.openapi.editor.colors.EditorColorsScheme)
                com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme().clone();
        var themeKey = com.intellij.openapi.editor.DefaultLanguageHighlighterColors.KEYWORD;
        var slangKey = new SlangSyntaxHighlighter().getTokenHighlights(SlangLexer.KEYWORD)[0];
        var first = new com.intellij.openapi.editor.markup.TextAttributes(
                java.awt.Color.MAGENTA, null, null, null, java.awt.Font.BOLD);
        var second = new com.intellij.openapi.editor.markup.TextAttributes(
                java.awt.Color.CYAN, null, null, null, java.awt.Font.ITALIC);
        scheme.setAttributes(themeKey, first);
        assertEquals(first, scheme.getAttributes(slangKey));
        scheme.setAttributes(themeKey, second);
        assertEquals(second, scheme.getAttributes(slangKey));
    }

    @Test void ctrlHoverUsesUsageSiteAndRendersMarkdownDocumentation() throws Exception {
        var path = documentationDirectory.resolve("shader.slang");
        java.nio.file.Files.writeString(path, "struct Surface {}\nSurface surface;");
        var virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(virtualFile);
        ReadAction.run(() -> {
            var file = com.intellij.psi.PsiManager.getInstance(fixture.getProject()).findFile(virtualFile);
            assertNotNull(file);
            var declaration = file.findElementAt(7);
            var usage = file.findElementAt(18);
            assertNotNull(usage);
            var provider = new SlangDocumentationProvider() {
                @Override protected java.util.List<String>
                hoverDocumentation(com.intellij.psi.PsiFile source, int offset) {
                    assertSame(file, source);
                    assertEquals(18, offset);
                    return java.util.List.of(com.redhat.devtools.lsp4ij.features.documentation.LSPDocumentationHelper.convertToHtml(
                            java.util.List.of(new org.eclipse.lsp4j.MarkupContent("markdown",
                                    "A **documented** surface.\n\n- Stores material data\n- Used by `shade()`")),
                            null, file));
                }
            };
            String html = provider.getQuickNavigateInfo(declaration, usage);
            assertNotNull(html);
            assertTrue(html.contains("<strong>documented</strong>"), html);
            assertTrue(html.contains("<li>"), html);
            assertTrue(html.contains("shade()"), html);
            assertEquals(html, provider.generateDoc(declaration, usage));
            assertEquals(html, provider.generateDoc(usage, null));
        });
    }

    @Test void missingHoverDocumentationDoesNotProduceAnEmptyPopup() {
        ReadAction.run(() -> {
            var file = PsiFileFactory.getInstance(fixture.getProject()).createFileFromText(
                    "shader.slang", SlangFileType.INSTANCE, "Unknown value;");
            var provider = new SlangDocumentationProvider() {
                @Override protected java.util.List<String>
                hoverDocumentation(com.intellij.psi.PsiFile source, int offset) {
                    return java.util.List.of();
                }
            };
            assertNull(provider.getQuickNavigateInfo(file.findElementAt(0), null));
        });
    }

    @Test void languageClientAndSemanticHighlightingCanBeCreated() {
        var client = new SlangLanguageServerFactory().createLanguageClient(fixture.getProject());
        try {
            assertInstanceOf(SlangLanguageClient.class, client);
            var feature = new SlangSemanticTokensFeature();
            ReadAction.run(() -> {
                var file = PsiFileFactory.getInstance(fixture.getProject()).createFileFromText(
                        "shader.slang", SlangFileType.INSTANCE, "struct Surface {}");
                assertSame(slanglsp.highlighting.SlangSyntaxHighlighterColors.TYPE_NAME,
                        feature.getTextAttributesKey("struct", java.util.List.of(), file));
            });
        } finally {
            client.handleServerStatusChanged(com.redhat.devtools.lsp4ij.ServerStatus.stopped);
            client.dispose();
        }
    }

    @Test void dottedImportsHaveOneSourceRangeAndDoNotMergeMemberAccess() {
        ReadAction.run(() -> {
            String code = "import shared.scene;\nScene item;\nitem.value;";
            var file = PsiFileFactory.getInstance(fixture.getProject()).createFileFromText(
                    "shader.slang", SlangFileType.INSTANCE, code);
            for (int offset = 7; offset < 19; offset++) {
                assertEquals("shared.scene", file.findElementAt(offset).getText());
                assertEquals(new TextRange(7, 19), file.findElementAt(offset).getTextRange());
            }
            assertEquals("value", file.findElementAt(code.indexOf("value")).getText());
        });
    }

    @Test void navigationFindsUsagesAtDeclarationsAndDefinitionsAtUsages() {
        ReadAction.run(() -> {
            String code = "float shade(float value) { return value; }\nfloat result = shade(1);";
            var file = PsiFileFactory.getInstance(fixture.getProject()).createFileFromText(
                    "shader.slang", SlangFileType.INSTANCE, code);
            var declaration = file.findElementAt(code.indexOf("shade"));
            var usage = file.findElementAt(code.lastIndexOf("shade"));
            var handler = new SlangGotoDeclarationHandler() {
                @Override protected com.intellij.psi.PsiElement[] definitions(com.intellij.psi.PsiElement source, int offset) {
                    return new com.intellij.psi.PsiElement[]{declaration};
                }
                @Override protected com.intellij.psi.PsiElement[] references(com.intellij.psi.PsiElement source, int offset,
                                                                           com.intellij.openapi.editor.Editor editor) {
                    assertSame(declaration, source);
                    return new com.intellij.psi.PsiElement[]{declaration, usage};
                }
            };
            assertArrayEquals(new com.intellij.psi.PsiElement[]{usage},
                    handler.getGotoDeclarationTargets(declaration, declaration.getTextOffset(), null));
            assertArrayEquals(new com.intellij.psi.PsiElement[]{declaration},
                    handler.getGotoDeclarationTargets(usage, usage.getTextOffset(), null));
        });
    }

    @Test void moduleNavigationQueriesFinalComponentAndOpensTheModuleFile() {
        ReadAction.run(() -> {
            var file = PsiFileFactory.getInstance(fixture.getProject()).createFileFromText(
                    "shader.slang", SlangFileType.INSTANCE, "import shared.scene;");
            var target = PsiFileFactory.getInstance(fixture.getProject()).createFileFromText(
                    "scene.slang", SlangFileType.INSTANCE, "module scene;");
            var handler = new SlangGotoDeclarationHandler() {
                @Override protected com.intellij.psi.PsiElement[] definitions(com.intellij.psi.PsiElement source, int offset) {
                    assertEquals(18, offset);
                    return new com.intellij.psi.PsiElement[]{target.findElementAt(7)};
                }
            };
            for (int offset = 7; offset < 19; offset++) {
                assertArrayEquals(new com.intellij.psi.PsiElement[]{target},
                        handler.getGotoDeclarationTargets(file.findElementAt(offset), offset, null));
            }
        });
    }

    @Test void slangClientAndNavigationNeverClaimCppFiles() {
        ReadAction.run(() -> {
            var cpp = new com.intellij.testFramework.LightVirtualFile("test.cpp", "int main() { return 0; }");
            var features = new SlangClientFeatures();
            assertFalse(features.isEnabled(cpp));
            assertInstanceOf(SlangSemanticTokensFeature.class, features.getSemanticTokensFeature());
            var file = com.intellij.psi.PsiManager.getInstance(fixture.getProject()).findFile(cpp);
            assertNotNull(file);
            assertFalse(com.redhat.devtools.lsp4ij.LanguageServersRegistry.getInstance().isFileSupported(file));
            assertNull(new SlangGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(0), 0, null));
        });
    }

}
