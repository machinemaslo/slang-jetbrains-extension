package slanglsp;

import com.intellij.openapi.diagnostic.Logger;
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;

final class SlangClientFeatures extends LSPClientFeatures {
    SlangClientFeatures() {
        setSemanticTokensFeature(new SlangSemanticTokensFeature());
    }

    @Override
    public boolean isEnabled(@NotNull com.intellij.openapi.vfs.VirtualFile file) {
        return file.getFileType() == SlangFileType.INSTANCE && super.isEnabled(file);
    }

    @Override
    public void initializeParams(@NotNull InitializeParams params) {
        configureWorkspace(params, getProject().getBasePath(), getProject().getName());
        Logger.getInstance(SlangClientFeatures.class).info("Slang workspace folders: " + params.getWorkspaceFolders());
    }

    static void configureWorkspace(InitializeParams params, String basePath, String projectName) {
        if (basePath == null) return;
        Path root = Path.of(basePath).toAbsolutePath().normalize();
        String uri = root.toUri().toString();
        var folders = new ArrayList<WorkspaceFolder>();
        if (params.getWorkspaceFolders() != null) {
            for (var folder : params.getWorkspaceFolders()) {
                // Keep other roots, but put the project root last: slangd versions that
                // overwrite their implicit search paths per root must scan it last.
                if (!isSameRoot(folder.getUri(), root)) folders.add(folder);
            }
        }
        folders.add(new WorkspaceFolder(uri, projectName));
        params.setWorkspaceFolders(folders);
        params.setRootUri(uri);
        params.setRootPath(root.toString());
    }

    private static boolean isSameRoot(String uri, Path root) {
        try {
            return root.equals(Path.of(URI.create(uri)).toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
