package slanglsp

import java.io.IOException
import java.nio.charset.StandardCharsets

/** Loads formatting scenarios verbatim: no trimming, dedenting, or line-ending normalization. */
object FormattingFixtures {
    fun text(path: String): String {
        val resource = "/formatting/$path"
        try {
            FormattingFixtures::class.java.getResourceAsStream(resource).use { stream ->
                if (stream == null) {
                    throw AssertionError("Missing formatting fixture: $resource")
                }
                return String(stream.readAllBytes(), StandardCharsets.UTF_8)
            }
        } catch (failure: IOException) {
            throw AssertionError("Cannot read formatting fixture: $resource", failure)
        }
    }

    /** Explicit variant for tests of unterminated editor text; fixture files themselves end with a newline. */
    fun withoutFinalNewline(path: String): String {
        val text = text(path)
        if (!text.endsWith("\n")) {
            throw AssertionError("Expected a final newline in formatting fixture: $path")
        }
        return text.substring(0, text.length - (if (text.endsWith("\r\n")) 2 else 1))
    }
}
