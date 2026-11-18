package slanglsp.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import slanglsp.TestDependencies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FormattingConfigurationCacheTest {
    @Test
    void validatesOnceUntilParentConfigChangesAndDoesNotCacheFailure(@TempDir Path root) throws Exception {
        var nested = Files.createDirectory(root.resolve("nested"));
        var parent = Files.writeString(root.resolve(".clang-format"), "IndentWidth: 2\n");
        var config = Files.writeString(nested.resolve(".clang-format"), "BasedOnStyle: InheritParentConfig\n");
        var launches = new AtomicInteger();
        String executable = TestDependencies.clangFormat();
        var source = nested.resolve("shader.slang");

        assertEquals("file:" + config, validate(source, executable, launches));
        assertEquals("file:" + config, validate(nested.resolve("other.slang"), executable, launches));
        assertEquals(1, launches.get());

        Files.writeString(parent, "IndentWidth: 8\n");
        validate(source, executable, launches);
        assertEquals(2, launches.get());

        Files.writeString(config, "NotAClangFormatOption: true\n");
        assertThrows(IOException.class, () -> validate(source, executable, launches));
        assertThrows(IOException.class, () -> validate(source, executable, launches));
        assertEquals(4, launches.get());
    }

    private static Object validate(Path source, String executable, AtomicInteger launches) throws Exception {
        String style = "slang.format.clangFormatStyle";
        var settings = new HashMap<String, Object>(Map.of(style, "file"));
        FormattingConfiguration.validate(source, executable, settings, ignored -> launches.incrementAndGet());
        return settings.get(style);
    }
}
