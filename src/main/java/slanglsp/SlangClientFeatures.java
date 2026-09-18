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
        setHoverFeature(new SlangHoverFeature());
        // Our Find Usages handler owns references and the indexed fallback. Avoid duplicate searches.
        setUsageFeature(new com.redhat.devtools.lsp4ij.client.features.LSPUsageFeature() {
            @Override public boolean isSupported(@NotNull com.intellij.psi.PsiFile file) { return false; }
        });
    }

    @Override
    public boolean isEnabled(@NotNull com.intellij.openapi.vfs.VirtualFile file) {
        return !(file instanceof SlangBuiltinFiles.BuiltinFile)
                && file.getFileType() == SlangFileType.INSTANCE && super.isEnabled(file);
    }

    @Override public com.intellij.openapi.vfs.VirtualFile findFileByUri(@NotNull String uri) {
        if (SlangBuiltinFiles.moduleName(uri) != null) {
            return getProject().getService(SlangBuiltinFiles.class).resolve(uri);
        }
        return super.findFileByUri(uri);
    }

    @Override public URI getFileUri(@NotNull com.intellij.openapi.vfs.VirtualFile file) {
        return file instanceof SlangBuiltinFiles.BuiltinFile builtin ? builtin.uri() : super.getFileUri(file);
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
                if (!isSameRoot(folder.getUri(), root)) folders.add(folder);
            }
        }
        folders.add(new WorkspaceFolder(uri, projectName));
        params.setWorkspaceFolders(folders);
    }

    private static boolean isSameRoot(String uri, Path root) {
        try {
            return root.equals(Path.of(URI.create(uri)).toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
