package slanglsp.server.protocol

import com.google.gson.JsonNull
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.Array
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Int
import kotlin.NumberFormatException
import kotlin.Throws
import kotlin.byteArrayOf
import kotlin.code
import kotlin.collections.dropLastWhile
import kotlin.collections.indices
import kotlin.collections.toTypedArray
import kotlin.text.equals
import kotlin.text.indexOf
import kotlin.text.isEmpty
import kotlin.text.split
import kotlin.text.toByteArray
import kotlin.text.toInt
import kotlin.text.toRegex
import kotlin.text.trim

/**
 * Normalize slangd's empty-object no-result response before LSP4J deserializes it. Array-valued responses such as
 * definition cannot be deserialized from {}. Normal results, server requests, notifications and errors pass through
 * unchanged.
 */
internal class ResponseInputStream(private val source: InputStream, private val method: (String) -> String?) :
    InputStream() {
    private var frame = ByteArrayInputStream(ByteArray(0))

    @Throws(IOException::class)
    override fun read(): Int {
        if (frame.available() == 0 && notNextFrame()) {
            return -1
        }

        return frame.read()
    }

    @Throws(IOException::class)
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        Objects.checkFromIndexSize(offset, length, bytes.size)

        if (length == 0) {
            return 0
        }

        if (frame.available() == 0 && notNextFrame()) {
            return -1
        }

        return frame.read(bytes, offset, length)
    }

    @Throws(IOException::class)
    private fun notNextFrame(): Boolean {
        val header = ByteArrayOutputStream()
        var matched = 0
        val separator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

        while (matched < separator.size) {
            val value = source.read()

            if (value == -1) {
                if (header.size() == 0) {
                    return true
                }
                throw EOFException("Truncated Slang LSP header")
            }

            header.write(value)
            matched = if (value == separator[matched].toInt()) matched + 1 else (if (value == '\r'.code) 1 else 0)

            if (header.size() > 8192) {
                throw IOException("Slang LSP header is too large")
            }
        }
        val lines: Array<String?> =
            header
                .toString(StandardCharsets.US_ASCII)
                .split("\r\n".toRegex())
                .dropLastWhile { it.isEmpty() }
                .toTypedArray()
        var length = -1
        var lengthHeader = -1

        for (i in lines.indices) {
            val colon = lines[i]!!.indexOf(':')
            if (colon >= 0 && lines[i]!!.substring(0, colon).equals("Content-Length", ignoreCase = true)) {
                try {
                    length = lines[i]!!.substring(colon + 1).trim { it <= ' ' }.toInt()
                } catch (e: NumberFormatException) {
                    throw IOException("Invalid Slang LSP Content-Length", e)
                }

                lengthHeader = i
            }
        }

        if (length < 0) {
            throw IOException("Missing or negative Slang LSP Content-Length")
        }
        val body = source.readNBytes(length)

        if (body.size != length) {
            throw EOFException("Truncated Slang LSP body")
        }
        val normalized = normalize(body)
        val output = ByteArrayOutputStream()

        if (normalized === body) {
            header.writeTo(output)
        } else {
            lines[lengthHeader] = "Content-Length: " + normalized.size
            output.write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
        }

        output.write(normalized)
        frame = ByteArrayInputStream(output.toByteArray())

        return false
    }

    private fun normalize(body: ByteArray): ByteArray {
        try {
            val element = JsonParser.parseString(String(body, StandardCharsets.UTF_8))

            if (element.isJsonObject) {
                val message = element.getAsJsonObject()
                val result = message.get("result")
                if (message.has("id") &&
                    !message.has("method") &&
                    !message.has("error") &&
                    result != null &&
                    result.isJsonObject &&
                    result.getAsJsonObject().isEmpty &&
                    "textDocument/definition" == method(message.get("id").asString)) {
                    message.add("result", JsonNull.INSTANCE)

                    return message.toString().toByteArray(StandardCharsets.UTF_8)
                }
            }
        } catch (_: JsonParseException) {
            // Let LSP4J report malformed JSON; only normalize the known empty-result bug.
        }

        return body
    }

    @Throws(IOException::class)
    override fun close() {
        source.close()
    }
}
