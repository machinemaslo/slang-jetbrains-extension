package slanglsp.server.protocol

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.net.URI
import java.net.URISyntaxException
import kotlin.io.path.toPath
import slanglsp.server.BuiltinFiles.BuiltinFile

/** Shared URI identity for editor requests and results from slangd. */
internal object DocumentUris {
    fun findLocalFile(value: String): VirtualFile? {
        return try {
            val uri = normalizeUri(URI(value))
            if (uri.scheme == "file") LocalFileSystem.getInstance().findFileByNioFile(uri.toPath()) else null
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun fileUri(file: VirtualFile): URI {
        if (file is BuiltinFile) {
            return file.uri()
        }

        return normalizeUri(file.toNioPath().toUri())
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
