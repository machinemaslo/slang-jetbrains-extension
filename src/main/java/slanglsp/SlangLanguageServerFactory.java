package slanglsp;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import org.jetbrains.annotations.NotNull;

public class SlangLanguageServerFactory implements LanguageServerFactory
{
    @Override
    public com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures createClientFeatures()
    {
        return new SlangClientFeatures();
    }

    @NotNull
    public StreamConnectionProvider createConnectionProvider(Project project)
    {
        return new SlangLanguageServer(project);
    }

    @NotNull
    public LanguageClientImpl createLanguageClient(Project project)
    {
        return new SlangLanguageClient(project);
    }
}
