package slanglsp.editor.navigation

import java.net.URI
import org.eclipse.lsp4j.Location
import slanglsp.server.protocol.DocumentUris.normalizeUri

/** Shared declaration identity for usage search and rename, after position repair. */
internal class SymbolLocation(uri: URI, val line: Int, val character: Int) {
    val uri: URI = normalizeUri(uri)

    override fun equals(other: Any?): Boolean =
        other is SymbolLocation && uri == other.uri && line == other.line && character == other.character

    override fun hashCode(): Int = (31 * uri.hashCode() + line) * 31 + character

    companion object {
        @JvmStatic
        fun from(location: Location): SymbolLocation {
            val start = location.range.start
            return SymbolLocation(URI.create(location.uri), start.line, start.character)
        }
    }
}
