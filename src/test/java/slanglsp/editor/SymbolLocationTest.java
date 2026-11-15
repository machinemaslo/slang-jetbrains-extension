package slanglsp.editor;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SymbolLocationTest {
    @Test
    void windowsDriveEncodingDoesNotChangeDeclarationIdentity() throws Exception {
        var ide = new SymbolLocation(new URI("file", "", "/C:/project/test.slang", null), 3, 12);
        var server = SymbolLocation.from(new Location("file:///C%3a/project/test.slang",
                new Range(new Position(3, 12), new Position(3, 17))));
        assertEquals(ide, server);
        assertTrue(new HashSet<>(List.of(ide)).contains(server));
        assertNotEquals(ide, new SymbolLocation(server.uri(), 3, 13));
    }

    @Test
    void preservesDistinctPathsAndDecodesOnlyOnce() {
        var encoded = SymbolLocation.normalizeUri(URI.create("file:///home/me/a%2520b.slang"));
        assertEquals("/home/me/a%20b.slang", encoded.getPath());
        assertNotEquals(encoded, SymbolLocation.normalizeUri(URI.create("file:///home/me/a%20b.slang")));
        assertNotEquals(new SymbolLocation(URI.create("file:///home/me/A.slang"), 0, 0),
                new SymbolLocation(URI.create("file:///home/me/a.slang"), 0, 0));
        assertEquals(URI.create("slang-synth://core/core.builtin"),
                SymbolLocation.normalizeUri(URI.create("slang-synth://core/core.builtin")));
    }
}
