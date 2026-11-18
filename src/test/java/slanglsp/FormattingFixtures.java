package slanglsp;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Loads formatting scenarios verbatim: no trimming, dedenting, or line-ending normalization. */
public final class FormattingFixtures {
    private FormattingFixtures() {
    }

    public static @NotNull String text(@NotNull String path) {
        String resource = "/formatting/" + path;
        try (var stream = FormattingFixtures.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError("Missing formatting fixture: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("Cannot read formatting fixture: " + resource, failure);
        }
    }

    /** Explicit variant for tests of unterminated editor text; fixture files themselves end with a newline. */
    public static @NotNull String withoutFinalNewline(@NotNull String path) {
        String text = text(path);
        if (!text.endsWith("\n")) {
            throw new AssertionError("Expected a final newline in formatting fixture: " + path);
        }
        return text.substring(0, text.length() - (text.endsWith("\r\n") ? 2 : 1));
    }
}
