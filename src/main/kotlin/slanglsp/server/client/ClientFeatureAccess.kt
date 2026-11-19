package slanglsp.server.client

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.client.features.FileUriSupport
import com.redhat.devtools.lsp4ij.features.LSPPsiElement
import com.redhat.devtools.lsp4ij.features.LSPPsiElementFactory
import com.redhat.devtools.lsp4ij.usages.LocationData

/** Keep LSP4IJ's experimental client feature types out of the editor request and navigation code. */
@Suppress("UnstableApiUsage")
internal object ClientFeatureAccess {
    fun supportsReferences(server: LanguageServerItem, file: PsiFile): Boolean =
        server.clientFeatures.referencesFeature.isSupported(file)

    // Let the active client handle both ordinary file URIs and slangd's built-in library URIs.
    fun documentUri(server: LanguageServerItem, file: VirtualFile?): String? =
        FileUriSupport.toString(file, server.clientFeatures)

    fun toPsiElement(location: LocationData, project: Project): LSPPsiElement? =
        LSPPsiElementFactory.toPsiElement(location.location(), location.languageServer().clientFeatures, project)
}
