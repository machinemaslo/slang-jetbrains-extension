package slanglsp;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlangServerConfigurationTest {
    @Test void configurationRepliesPreserveRequestOrderTypesAndUnknownSections() {
        var state = new SlangPersistentStateConfig.State();
        state.additionalIncludePaths = List.of("/project/src/shader");
        var values = SlangServerConfiguration.select(state.createServerSettings(), request(
                "slang.additionalSearchPaths", "slang.searchInAllWorkspaceDirectories", "unknown", null));
        assertEquals(4, values.size());
        assertEquals(state.additionalIncludePaths, values.get(0));
        assertEquals(Boolean.TRUE, values.get(1));
        assertNull(values.get(2));
        assertEquals(state.createServerSettings(), values.get(3));
    }

    @Test void settingsSurviveSerializationByAnotherPluginClassloader() throws Exception {
        var state = new SlangPersistentStateConfig.State();
        state.additionalIncludePaths = List.of("/project/src/shader");
        // IDE test classloaders may not expose a CodeSource. Load a second copy
        // from class resources, with only the bootstrap loader as its parent.
        var otherPlugin = new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.startsWith("com.google.gson.")) throw new ClassNotFoundException(name);
                try (var stream = Gson.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                    if (stream == null) throw new ClassNotFoundException(name);
                    byte[] bytes = stream.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (java.io.IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        };
        {
            var gsonType = otherPlugin.loadClass("com.google.gson.Gson");
            var gson = gsonType.getConstructor().newInstance();
            var serialize = gsonType.getMethod("toJson", Object.class);

            // Reproduce the old failure: a foreign JsonObject is serialized as its
            // internal fields instead of a JSON settings object.
            String oldJson = (String) serialize.invoke(gson, state.createJSONFromObject());
            assertFalse(JsonParser.parseString(oldJson).getAsJsonObject().has("slang.additionalSearchPaths"));

            String json = (String) serialize.invoke(gson, state.createServerSettings());
            assertEquals(state.createJSONFromObject(), JsonParser.parseString(json));
            String response = (String) serialize.invoke(gson, SlangServerConfiguration.select(
                    state.createServerSettings(), request("slang.additionalSearchPaths",
                            "slang.searchInAllWorkspaceDirectories", "unknown")));
            assertEquals(JsonParser.parseString("[[\"/project/src/shader\"],true,null]"),
                    JsonParser.parseString(response));
        }
    }

    private static ConfigurationParams request(String... sections) {
        var params = new ConfigurationParams();
        params.setItems(java.util.Arrays.stream(sections).map(section -> {
            var item = new ConfigurationItem();
            item.setSection(section);
            return item;
        }).toList());
        return params;
    }
}
