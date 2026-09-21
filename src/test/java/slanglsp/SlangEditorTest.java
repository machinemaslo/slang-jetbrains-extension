package slanglsp;

import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlangEditorTest {
    private IdeaProjectTestFixture fixture;

    @BeforeEach void setUp() throws Exception {
        fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("SlangEditorTest").getFixture();
        com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(() -> fixture.setUp());
    }

    @AfterEach void tearDown() throws Exception {
        if (fixture != null) com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(() -> fixture.tearDown());
    }

    @Test void slangClientAndNavigationNeverClaimCppFiles() {
        SlangReadAction.run(() -> {
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
