package slanglsp.settings;

import com.intellij.openapi.application.WriteAction;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.LightVirtualFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParsedFormattingConfigTest {
    @Test
    void reusesParsedYamlUntilTheFileChanges() throws Exception {
        var file = new LightVirtualFile(".clang-format", "IndentWidth: 2\n");
        var first = ClangFormatSettingsImporter.readOptions(file);
        assertSame(first, ClangFormatSettingsImporter.readOptions(file));
        EdtTestUtil.runInEdtAndWait(() -> WriteAction.run(() -> file.setContent(this, "IndentWidth: 6\n", true)));
        var updated = ClangFormatSettingsImporter.readOptions(file);
        assertNotSame(first, updated);
        assertEquals(2, first.get("IndentWidth"));
        assertEquals(6, updated.get("IndentWidth"));
        assertSame(updated, ClangFormatSettingsImporter.readOptions(file));
    }
}
