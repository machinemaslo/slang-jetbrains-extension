package slanglsp

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import slanglsp.SlangBundle.message

internal class SlangBundleTest {
    @Test
    fun messageFormattingPreservesQuotesAndSubstitutesNames() {
        Assertions.assertEquals("Rename 'value' to (project Slang files):", message("rename.prompt", "value"))
        Assertions.assertEquals(
            "Only symbols declared in this project's Slang files can be renamed.",
            message("rename.only.symbols.declared.in.this.project.s.slang.files.can.be.renamed"))
        Assertions.assertEquals(
            "Cannot read author's shader.slang", message("rename.cannot.read", "author's shader.slang"))
    }
}
