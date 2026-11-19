package slanglsp.server.protocol

import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class ResponseInputStreamTest {
    @Test
    @Throws(Exception::class)
    fun onlyKnownEmptyDefinitionResponsesAreNormalized() {
        val response = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}"
        val normalized: String = read(response, "textDocument/definition")
        val body = normalized.substring(normalized.indexOf("\r\n\r\n") + 4)
        Assertions.assertTrue(JsonParser.parseString(body).asJsonObject.get("result").isJsonNull)
        Assertions.assertTrue(
            normalized.startsWith("Content-Length: " + body.toByteArray(StandardCharsets.UTF_8).size + "\r\n"))
        for (method in arrayOf("initialize", "workspace/executeCommand", "textDocument/hover", null)) {
            Assertions.assertEquals(frame(response), read(response, method))
        }
    }

    @Test
    @Throws(Exception::class)
    fun requestsErrorsAndUnicodeResultsPassThroughUnchanged() {
        for (body in
            arrayOf(
                "{\"id\":7,\"method\":\"workspace/configuration\",\"params\":{}}",
                "{\"id\":7,\"error\":{\"code\":-32603,\"message\":\"é🙂\"}}",
                "{\"id\":7,\"result\":[{\"uri\":\"file:///é.slang\"}]}",
                "{\"id\":7,\"result\":null}")) {
            Assertions.assertEquals(frame(body), read(body, "textDocument/definition"))
        }
    }

    companion object {
        @Throws(Exception::class)
        private fun read(body: String, method: String?): String {
            ResponseInputStream(ByteArrayInputStream(frame(body).toByteArray(StandardCharsets.UTF_8))) { id: String? ->
                    Assertions.assertEquals("7", id)
                    method
                }
                .use { stream ->
                    return String(stream.readAllBytes(), StandardCharsets.UTF_8)
                }
        }

        private fun frame(body: String): String {
            return "Content-Length: " + body.toByteArray(StandardCharsets.UTF_8).size + "\r\n\r\n" + body
        }
    }
}
