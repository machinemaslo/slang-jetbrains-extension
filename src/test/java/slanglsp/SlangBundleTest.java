package slanglsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlangBundleTest {
    @Test
    void messageFormattingPreservesQuotesAndSubstitutesNames() {
        assertEquals("Rename 'value' to (project Slang files):", SlangBundle.message("rename.prompt", "value"));
        assertEquals("Only symbols declared in this project's Slang files can be renamed.",
                SlangBundle.message("rename.only.symbols.declared.in.this.project.s.slang.files.can.be.renamed"));
        assertEquals("Cannot read author's shader.slang",
                SlangBundle.message("rename.cannot.read", "author's shader.slang"));
    }
}
