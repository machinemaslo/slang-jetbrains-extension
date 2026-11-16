package slanglsp.editor;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;

final class NativeServerSupport {
    private NativeServerSupport() {}

    static void enable(Project project) {
        // Light fixtures share LSP4IJ state. An earlier editor-only test may have
        // disabled this definition when no native executable was configured.
        var definition = LanguageServersRegistry.getInstance().getServerDefinition("slanglsp.SlangLanguageServer");
        if (definition == null) {
            throw new AssertionError("Slang language server definition is not registered");
        }
        definition.setEnabled(true, project);
    }

    static void disable(Project project) {
        var definition = LanguageServersRegistry.getInstance().getServerDefinition("slanglsp.SlangLanguageServer");
        if (definition == null) {
            throw new AssertionError("Slang language server definition is not registered");
        }
        definition.setEnabled(false, project);
    }

    /**
     * Await actual shutdown off the EDT before the shared light project is reused.
     * LSP4IJ's public manager discards the shutdown futures; enumerating existing wrappers
     * requires its internal accessor. This helper is test-only and is not packaged in the plugin.
     */
    @SuppressWarnings("JetBrainsInternalApiUsage")
    static void stop(Project project) throws Exception {
        disable(project);
        for (var server : com.redhat.devtools.lsp4ij.LanguageServiceAccessor.getInstance(project).getStartedServers()) {
            if (server.getServerDefinition().getId().equals("slanglsp.SlangLanguageServer")) {
                server.stop().get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }
}
