package slanglsp.mcp;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Run with -PmcpSmokeTest=true and a platform containing JetBrains' MCP Server. */
@EnabledIfSystemProperty(named = "slang.test.mcp", matches = "true")
class McpRegistrationTest {
    @Test
    void contributesNamedToolsToTheRealMcpExtensionPoint() throws Exception {
        var fixture = IdeaTestFixtureFactory.getFixtureFactory().createLightFixtureBuilder("SlangMcp").getFixture();
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            assertRegistered();
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }

    private static void assertRegistered() throws ReflectiveOperationException {
        // Resolve the extension through the installed server's classloader.
        var server = PluginManagerCore.getPlugin(PluginId.getId("com.intellij.mcpServer"));
        assertNotNull(server, "Missing JetBrains MCP Server plugin");
        var loader = server.getPluginClassLoader();
        assertNotNull(loader, "JetBrains MCP Server plugin is not loaded");
        var toolsetType = loader.loadClass("com.intellij.mcpserver.McpToolset");
        var companion = toolsetType.getField("Companion").get(null);
        var extensionPoint = (ExtensionPointName<?>) companion.getClass().getMethod("getEP").invoke(companion);
        var toolset = extensionPoint.getExtensionList().stream()
                .filter(tool -> tool.getClass().getName().equals("slanglsp.mcp.SlangToolset"))
                .findFirst().orElseThrow(() -> new AssertionError("Missing Slang MCP toolset"));
        var interfaceDefaults = Set.of("isEnabled", "isUserConfigurable", "displayName", "displayDescription", "alwaysIncluded");
        var generatedBridges = Arrays.stream(toolset.getClass().getDeclaredMethods())
                .map(Method::getName)
                .filter(interfaceDefaults::contains).toList();
        assertEquals(List.of(), generatedBridges, "Do not generate calls to newer MCP interface defaults");
        var jsonType = loader.loadClass("kotlinx.serialization.json.Json");
        var reflection = loader.loadClass("com.intellij.mcpserver.impl.util.ToolsetReflection_utilKt");
        var tools = (List<?>) reflection.getMethod("asTools", toolsetType, jsonType)
                .invoke(null, toolset, jsonType.getField("Default").get(null));
        var names = new HashSet<String>();
        for (var tool : tools) {
            var descriptor = tool.getClass().getMethod("getDescriptor").invoke(tool);
            names.add((String) descriptor.getClass().getMethod("getName").invoke(descriptor));
        }
        assertEquals(Set.of("slang_lookup", "slang_check_files", "slang_symbol"), names);
    }
}
