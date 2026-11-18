package slanglsp.mcp

import com.intellij.mcpserver.ClientInfo
import com.intellij.mcpserver.McpCallAdditionalDataElement
import com.intellij.mcpserver.McpCallInfo
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Exercise JSON argument binding, project context and error propagation without a network dependency. */
@EnabledIfSystemProperty(named = "slang.test.mcp", matches = "true")
class BaselineMcpCallsTest {
    @Test
    fun callsRegisteredTools(@TempDir directory: Path) {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory()
            .createFixtureBuilder("SlangCalls", directory.toRealPath(), false).fixture
        runInEdtAndWait { fixture.setUp() }
        try {
            val toolset = McpToolset.EP.extensionList.single { it.javaClass.name == "slanglsp.mcp.SlangToolset" }
            val tools = toolset.asTools(Json).associateBy { it.descriptor.name }
            val requests = mapOf(
                "slang_lookup" to ("""{"query":""}""" to "Use a Slang topic or identifier (1–240 characters)."),
                "slang_check_files" to ("""{"files":[]}""" to "Supply 1–16 project-relative Slang files."),
                "slang_symbol" to ("""{"file":"../x.slang","line":1,"column":1}""" to "Path is outside the project: ../x.slang"),
            )
            for ((name, request) in requests) {
                val tool = tools.getValue(name)
                val args = Json.parseToJsonElement(request.first).jsonObject
                val info = McpCallInfo(
                    1, ClientInfo("test", "1"), fixture.project, tool.descriptor, args,
                    Json.parseToJsonElement("{}").jsonObject,
                    McpServerService.McpSessionOptions(McpServerService.AskCommandExecutionMode.ASK),
                )
                val failure = assertThrows(McpExpectedError::class.java) {
                    runBlocking(McpCallAdditionalDataElement(info)) { tool.call(args) }
                }
                assertEquals(request.second, failure.mcpErrorText)
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }
}
