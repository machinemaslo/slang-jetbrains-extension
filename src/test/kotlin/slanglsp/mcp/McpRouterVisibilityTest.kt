package slanglsp.mcp

import com.intellij.mcpserver.McpToolFilter
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@EnabledIfSystemProperty(named = "slang.test.mcp", matches = "true")
class McpRouterVisibilityTest {
    @Test
    fun bundlesAnAgentGuideWithRouterExamplesForEverySlangTool() {
        val resource = "${AgentSkillRegistration.RESOURCE_PATH}/SKILL.md"
        val markdown = checkNotNull(javaClass.classLoader.getResource(resource)).readText()
        assertTrue("name: slang-tools" in markdown)
        assertTrue("slang_lookup --query" in markdown)
        assertTrue("""slang_check_files --files '["path/to/shader.slang"]'""" in markdown)
        assertTrue("slang_symbol --file path/to/shader.slang --line 12 --column 8" in markdown)
    }

    @Test
    fun registersOneReadableBundledSkillWhenTheIdeSupportsIt() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("SlangAgentSkill").fixture
        runInEdtAndWait { fixture.setUp() }

        try {
            val app = ApplicationManager.getApplication()
            val point = app.extensionArea.getExtensionPointIfRegistered<Any>(AgentSkillRegistration.PROVIDER_EP)
            assumeTrue(point != null, "Bundled skills were added after the minimum IDE")
            val extensions = checkNotNull(point)
            runInEdtAndWait {
                PlatformTestUtil.waitWithEventsDispatching(
                    "Slang bundled skill is registered on IDE startup",
                    { extensions.extensionList.any { it.toString() == "slang-tools" } },
                    10,
                )
            }
            val provider = extensions.extensionList.single { it.toString() == "slang-tools" }
            val resource = checkNotNull(
                provider.javaClass.classLoader.getResource("${AgentSkillRegistration.RESOURCE_PATH}/SKILL.md")
            )
            assertTrue("slang_lookup --query" in resource.readText())
            val api = provider.javaClass.interfaces.single()
            val skill = (api.getMethod("registeredSkills").invoke(provider) as List<*>).single()!!
            assertEquals("slang-tools", skill.javaClass.getMethod("getSkillId").invoke(skill))
            assertEquals(
                setOf("slang_lookup", "slang_check_files", "slang_symbol"),
                skill.javaClass.getMethod("getRequiredTools").invoke(skill),
            )
            val targets = skill.javaClass.getMethod("getTargets").invoke(skill) as Set<*>
            assertTrue(targets.any { (it as Enum<*>).name == "OTHER" })
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

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
