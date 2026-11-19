package slanglsp.editor.navigation

import java.net.URI
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import slanglsp.editor.navigation.SymbolLocation.Companion.from
import slanglsp.server.protocol.DocumentUris.normalizeUri

internal class SymbolLocationTest {
    @Test
    @Throws(Exception::class)
    fun windowsDriveEncodingDoesNotChangeDeclarationIdentity() {
        val ide = SymbolLocation(URI("file", "", "/C:/project/test.slang", null), 3, 12)
        val server = from(Location("file:///C%3a/project/test.slang", Range(Position(3, 12), Position(3, 17))))
        Assertions.assertEquals(ide, server)
        Assertions.assertTrue(HashSet(listOf<SymbolLocation?>(ide)).contains(server))
        Assertions.assertNotEquals(ide, SymbolLocation(server.uri, 3, 13))
    }

    @Test
    fun preservesDistinctPathsAndDecodesOnlyOnce() {
        val encoded = normalizeUri(URI.create("file:///home/me/a%2520b.slang"))
        Assertions.assertEquals("/home/me/a%20b.slang", encoded.getPath())
        Assertions.assertNotEquals(encoded, normalizeUri(URI.create("file:///home/me/a%20b.slang")))
        Assertions.assertNotEquals(
            SymbolLocation(URI.create("file:///home/me/A.slang"), 0, 0),
            SymbolLocation(URI.create("file:///home/me/a.slang"), 0, 0))
        Assertions.assertEquals(
            URI.create("slang-synth://core/core.builtin"), normalizeUri(URI.create("slang-synth://core/core.builtin")))
    }
}
