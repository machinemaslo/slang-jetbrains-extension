package slanglsp.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/** Run with -PmcpSmokeTest=true and a platform containing JetBrains' MCP Server. */
@EnabledIfSystemProperty(named = "slang.test.mcp", matches = "true")
internal class McpRegistrationTest {
    @Test
    fun contributesNamedToolsToTheRealMcpExtensionPoint() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("SlangMcp").fixture
        runInEdtAndWait { fixture.setUp() }
        try {
            val toolset = McpToolset.EP.extensionList.filterIsInstance<SlangToolset>().single()
            val names = toolset.asTools().map { it.descriptor.name }.toSet()
            Assertions.assertEquals(setOf("slang_lookup", "slang_check_files", "slang_symbol"), names)
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }
}
