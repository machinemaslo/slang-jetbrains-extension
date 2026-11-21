import org.junit.Assert.assertEquals
import org.junit.Test

class ShowPlaygroundLogTest {
    @Test
    fun keepsPluginEntriesAndTheirStackTracesWithoutIdeWarnings() {
        val plugin = "2026-11-21 03:53:54,809 [   5913]   WARN - #slanglsp.editor.rename.ReflectionRenameRegeneration - Generation failed"
        val attributed = "2026-11-21 03:53:57,551 [   8655] SEVERE - #c.i.d.ErrorHandler - Unhandled exception"
        val stack = "    at slanglsp.editor.rename.ReflectionRenameRegeneration.regenerate(ReflectionRenameRegeneration.kt:160)"
        val attribution = "com.intellij.diagnostic.PluginException: startup failed [Plugin: slanglsp_r]"
        val lines = sequenceOf(
            "2026-11-21 03:53:49,302 [    406]   WARN - #c.i.p.instanceContainer - Incorrect override",
            "    unrelated warning continuation for a path that contains slanglsp",
            plugin,
            stack,
            "2026-11-21 03:53:54,900 [   6000]   WARN - #c.j.c.p.workspace - Unrelated IDE warning",
            attributed,
            attribution,
            stack,
        )

        assertEquals(listOf("$plugin\n$stack", "$attributed\n$attribution\n$stack"), pluginLogEntries(lines))
    }
}
