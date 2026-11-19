package slanglsp.server

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import slanglsp.server.client.ClientFeatures
import slanglsp.server.client.ServerClient

class ServerFactory : LanguageServerFactory {
    // The LSP4IJ factory requires this return type to register Slang's client features.
    @Suppress("UnstableApiUsage")
    override fun createClientFeatures(): LSPClientFeatures {
        return ClientFeatures()
    }

    override fun createConnectionProvider(project: Project): StreamConnectionProvider {
        return ServerConnection(project)
    }

    override fun createLanguageClient(project: Project): LanguageClientImpl {
        return ServerClient(project)
    }
}
