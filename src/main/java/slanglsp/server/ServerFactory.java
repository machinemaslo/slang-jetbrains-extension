package slanglsp.server;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import org.jetbrains.annotations.NotNull;

// LSP4IJ exposes custom client features through experimental extension points.
public class ServerFactory implements LanguageServerFactory
{
    @Override
    @NotNull
    public com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures createClientFeatures()
    {
        return new ClientFeatures();
    }

    @NotNull
    public StreamConnectionProvider createConnectionProvider(@NotNull Project project)
    {
        return new ServerConnection(project);
    }

    @NotNull
    public LanguageClientImpl createLanguageClient(@NotNull Project project)
    {
        return new ServerClient(project);
    }
}
