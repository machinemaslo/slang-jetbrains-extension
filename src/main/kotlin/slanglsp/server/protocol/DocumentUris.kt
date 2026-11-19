package slanglsp.server.protocol

import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.client.features.FileUriSupport
import java.net.URI
import java.net.URISyntaxException
import slanglsp.SlangBundle
import slanglsp.server.BuiltinFiles.BuiltinFile

/** Shared URI identity for editor requests and results from slangd. */
internal object DocumentUris {
    fun fileUri(file: VirtualFile): URI {
        if (file is BuiltinFile) {
            return file.uri()
        }
        val uri =
            checkNotNull(FileUriSupport.getFileUri(file, null)) {
                SlangBundle.message("usages.uri.failed", file.name)
            }
        return normalizeUri(uri)
    }

    fun normalizeUri(uri: URI): URI {
        if (!"file".equals(uri.scheme, ignoreCase = true)) {
            return uri.normalize()
        }
        try {
            // Rebuild from decoded components so slangd's C%3a and the IDE's C: match.
            // Filesystem case and alias handling still belong to VFS.
            return URI("file", uri.authority, uri.path, uri.query, uri.fragment).normalize()
        } catch (failure: URISyntaxException) {
            throw IllegalArgumentException("Invalid file URI: $uri", failure)
        }
    }
}
