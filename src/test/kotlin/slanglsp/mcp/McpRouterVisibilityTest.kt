package slanglsp.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@EnabledIfSystemProperty(named = "slang.test.mcp", matches = "true")
class McpRouterVisibilityTest {
    @Test
    fun exposesSlangDirectlyInRouterModeStartingWith2026_2() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("SlangRouter").fixture
        runInEdtAndWait { fixture.setUp() }

        try {
            val toolset = McpToolset.EP.extensionList.single { it.javaClass.name == "slanglsp.mcp.SlangToolset" }
            val supportsRouter = ApplicationInfo.getInstance().build.baselineVersion >= 262
            assertEquals(supportsRouter, (toolset as CompatibleToolset).alwaysIncluded())

            if (supportsRouter) {
                assertEquals(true, McpToolset::class.java.getMethod("alwaysIncluded").invoke(toolset))
                assertEquals(setOf("slang_lookup", "slang_check_files", "slang_symbol"), directlyExposedSlangTools())
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    private fun directlyExposedSlangTools(): Set<String> {
        // Resolve the newer router API through the server's loader so the test also runs on 2025.2.
        val loader = McpToolset::class.java.classLoader
        val serviceType = loader.loadClass("com.intellij.mcpserver.impl.McpServerService")
        val companion = serviceType.getField("Companion").get(null)
        val service = companion.javaClass.getMethod("getInstance").invoke(companion)
        val modeType = loader.loadClass("com.intellij.mcpserver.McpToolInvocationMode")
        val mode = modeType.enumConstants.single { (it as Enum<*>).name == "DIRECT_WITH_ROUTER_ENABLED" }
        val listTools = serviceType.methods.single {
            it.name.substringBefore('$') == "getMcpToolsFiltered" && it.parameterTypes.lastOrNull() == modeType
        }
        val tools = listTools.invoke(service, null, true, emptySet<Class<*>>(), null, null, mode) as List<*>

        val toolType = loader.loadClass("com.intellij.mcpserver.McpTool")
        val getDescriptor = toolType.getMethod("getDescriptor")
        val getName = getDescriptor.returnType.getMethod("getName")
        return tools.map { getName.invoke(getDescriptor.invoke(it)) as String }
            .filter { it.startsWith("slang_") }
            .toSet()
    }
}
