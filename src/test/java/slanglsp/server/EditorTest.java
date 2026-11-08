package slanglsp.server;

import slanglsp.editor.SlangGotoDeclarationHandler;

import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EditorTest {
    private IdeaProjectTestFixture fixture;

    @BeforeEach void setUp() throws Exception {
        fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("SlangEditorTest").getFixture();
        com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(() -> fixture.setUp());
    }

    @AfterEach void tearDown() throws Exception {
        if (fixture != null) com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(() -> fixture.tearDown());
    }

    @Test void slangClientAndNavigationNeverClaimCppFiles() {
        com.intellij.openapi.application.ReadAction.run(() -> {
            var cpp = new com.intellij.testFramework.LightVirtualFile("test.cpp", "int main() { return 0; }");
            var features = new ClientFeatures();
            assertFalse(features.isEnabled(cpp));
            var file = com.intellij.psi.PsiManager.getInstance(fixture.getProject()).findFile(cpp);
            assertNotNull(file);
            assertFalse(com.redhat.devtools.lsp4ij.LanguageServersRegistry.getInstance().isFileSupported(file));
            assertNull(new SlangGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(0), 0, null));
        });
    }

    @Test void clientRegistrationSurvivesRestartAndEndsOnDisposal() {
        var client = new ServerClient(fixture.getProject()) {
            // This lifecycle test has no remote language server.
            @Override public void triggerChangeConfiguration() { }
        };
        try {
            assertFalse(ServerClient.activeClients.contains(client));
            client.handleServerStatusChanged(com.redhat.devtools.lsp4ij.ServerStatus.started);
            client.handleServerStatusChanged(com.redhat.devtools.lsp4ij.ServerStatus.started);
            assertEquals(1, ServerClient.activeClients.stream().filter(c -> c == client).count());
            client.handleServerStatusChanged(com.redhat.devtools.lsp4ij.ServerStatus.stopped);
            assertFalse(ServerClient.activeClients.contains(client));
            client.handleServerStatusChanged(com.redhat.devtools.lsp4ij.ServerStatus.started);
            assertTrue(ServerClient.activeClients.contains(client));
            client.dispose();
            assertTrue(client.isDisposed());
            assertFalse(ServerClient.activeClients.contains(client));
            client.handleServerStatusChanged(com.redhat.devtools.lsp4ij.ServerStatus.started);
            assertFalse(ServerClient.activeClients.contains(client));
        } finally {
            client.dispose();
        }
    }

}
