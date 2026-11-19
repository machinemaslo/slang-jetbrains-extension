package slanglsp.mcp

import com.intellij.mcpserver.McpToolFilter
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@EnabledIfSystemProperty(named = "slang.test.mcp", matches = "true")
class McpRouterVisibilityTest {
    @Test
    fun marksEverySlangToolAsAlwaysIncluded() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("SlangRouter").fixture
        runInEdtAndWait { fixture.setUp() }

        try {
            val toolset = McpToolset.EP.extensionList.filterIsInstance<SlangToolset>().single()
            assertEquals(false, toolset.isExperimental())
            assertEquals(true, toolset.alwaysIncluded())
            val included =
                toolset.asTools().filter(McpToolFilter.AlwaysIncluded::shouldInclude).map { it.descriptor.name }.toSet()
            assertEquals(setOf("slang_lookup", "slang_check_files", "slang_symbol"), included)
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }
}
