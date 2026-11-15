package slanglsp.editor;

import org.eclipse.lsp4j.Location;

import java.net.URI;
import java.net.URISyntaxException;

/** Shared declaration identity for usage search and rename, after position repair. */
record SymbolLocation(URI uri, int line, int character) {
    SymbolLocation {
        uri = normalizeUri(uri);
    }

    static SymbolLocation from(Location location) {
        var start = location.getRange().getStart();
        return new SymbolLocation(URI.create(location.getUri()), start.getLine(), start.getCharacter());
    }

    static URI normalizeUri(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            return uri.normalize();
        }
        try {
            // URI.normalize only removes dot segments. Rebuild from decoded components
            // so slangd's C%3a and the IDE's C: have the same identity. Do not lowercase
            // paths: filesystem case/alias handling belongs to VFS resolution.
            return new URI("file", uri.getAuthority(), uri.getPath(), uri.getQuery(), uri.getFragment()).normalize();
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Invalid file URI: " + uri, failure);
        }
    }
}
