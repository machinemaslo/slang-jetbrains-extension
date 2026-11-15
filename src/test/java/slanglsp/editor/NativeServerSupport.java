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
}
