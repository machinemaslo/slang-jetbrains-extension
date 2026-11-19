package slanglsp.formatting.clang

import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import slanglsp.formatting.clang.ClangFormatSettingsImporter.Options.readOptions

internal class ParsedFormattingConfigTest {
    @Test
    @Throws(Exception::class)
    fun reusesParsedYamlUntilTheFileChanges() {
        val file = LightVirtualFile(".clang-format", "IndentWidth: 2\n")
        val first = readOptions(file)
        Assertions.assertSame(first, readOptions(file))
        runInEdtAndWait {
            WriteAction.run<RuntimeException?> { file.setContent(this, "IndentWidth: 6\n", true) }
        }
        val updated = readOptions(file)
        Assertions.assertNotSame(first, updated)
        Assertions.assertEquals(2, first["IndentWidth"])
        Assertions.assertEquals(6, updated["IndentWidth"])
        Assertions.assertSame(updated, readOptions(file))
    }
}
