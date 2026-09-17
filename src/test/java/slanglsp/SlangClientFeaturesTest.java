package slanglsp;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlangClientFeaturesTest {
    @TempDir Path project;

    @Test void initializationIncludesProjectEvenWithoutIdeContentRoots() {
        var params = new InitializeParams();
        SlangClientFeatures.configureWorkspace(params, project.toString(), "Shaders");
        assertEquals(List.of(new WorkspaceFolder(project.toUri().toString(), "Shaders")), params.getWorkspaceFolders());
        assertEquals(project.toUri().toString(), params.getRootUri());
    }

    @Test void projectRootIsLastWithoutDuplicatesAndOtherRootsArePreserved() {
        var params = new InitializeParams();
        var external = new WorkspaceFolder(project.resolveSibling("external").toUri().toString(), "External");
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(project.toUri().toString(), "Old name"), external));
        SlangClientFeatures.configureWorkspace(params, project.toString(), "Shaders");
        assertEquals(List.of(external, new WorkspaceFolder(project.toUri().toString(), "Shaders")), params.getWorkspaceFolders());
    }

    @Test void projectlessInitializationPreservesExistingFolders() {
        var params = new InitializeParams();
        var folders = List.of(new WorkspaceFolder(project.toUri().toString(), "External"));
        params.setWorkspaceFolders(folders);
        SlangClientFeatures.configureWorkspace(params, null, "Default");
        assertEquals(folders, params.getWorkspaceFolders());
    }
}
