package slanglsp.server;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ResponseInputStreamTest {
    @Test
    void onlyKnownEmptyDefinitionResponsesAreNormalized() throws Exception {
        String response = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}";
        String normalized = read(response, "textDocument/definition");
        String body = normalized.substring(normalized.indexOf("\r\n\r\n") + 4);
        assertTrue(JsonParser.parseString(body).getAsJsonObject().get("result").isJsonNull());
        assertTrue(normalized.startsWith("Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"));
        for (String method : new String[] {"initialize", "workspace/executeCommand", "textDocument/hover", null}) {
            assertEquals(frame(response), read(response, method));
        }
    }

    @Test
    void requestsErrorsAndUnicodeResultsPassThroughUnchanged() throws Exception {
        for (String body : new String[] {
                "{\"id\":7,\"method\":\"workspace/configuration\",\"params\":{}}",
                "{\"id\":7,\"error\":{\"code\":-32603,\"message\":\"é🙂\"}}",
                "{\"id\":7,\"result\":[{\"uri\":\"file:///é.slang\"}]}",
                "{\"id\":7,\"result\":null}"
        }) {
            assertEquals(frame(body), read(body, "textDocument/definition"));
        }
    }

    private static String read(String body, String method) throws Exception {
        try (var stream = new ResponseInputStream(
                new ByteArrayInputStream(frame(body).getBytes(StandardCharsets.UTF_8)), id -> {
                    assertEquals("7", id);
                    return method;
                })) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String frame(String body) {
        return "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }
}
