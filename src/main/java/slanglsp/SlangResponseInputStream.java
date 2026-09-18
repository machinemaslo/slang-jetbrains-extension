package slanglsp;

import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Normalize slangd's empty-object no-result response before LSP4J deserializes it.
 * Array-valued responses such as definition cannot be deserialized from {}.
 * Normal results, server requests, notifications and errors pass through unchanged.
 */
final class SlangResponseInputStream extends InputStream {
    private final InputStream source;
    private ByteArrayInputStream frame = new ByteArrayInputStream(new byte[0]);

    SlangResponseInputStream(InputStream source) {
        this.source = source;
    }

    @Override public int read() throws IOException {
        if (frame.available() == 0 && !nextFrame()) return -1;
        return frame.read();
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (frame.available() == 0 && !nextFrame()) return -1;
        return frame.read(bytes, offset, length);
    }

    private boolean nextFrame() throws IOException {
        var header = new ByteArrayOutputStream();
        int matched = 0;
        byte[] separator = {'\r', '\n', '\r', '\n'};
        while (matched < separator.length) {
            int value = source.read();
            if (value == -1) {
                if (header.size() == 0) return false;
                throw new EOFException("Truncated Slang LSP header");
            }
            header.write(value);
            matched = value == separator[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
            if (header.size() > 8192) throw new IOException("Slang LSP header is too large");
        }
        String[] lines = header.toString(StandardCharsets.US_ASCII).split("\r\n");
        int length = -1;
        int lengthHeader = -1;
        for (int i = 0; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon >= 0 && lines[i].substring(0, colon).equalsIgnoreCase("Content-Length")) {
                try {
                    length = Integer.parseInt(lines[i].substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid Slang LSP Content-Length", e);
                }
                lengthHeader = i;
            }
        }
        if (length < 0) throw new IOException("Missing or negative Slang LSP Content-Length");
        byte[] body = source.readNBytes(length);
        if (body.length != length) throw new EOFException("Truncated Slang LSP body");
        byte[] normalized = normalize(body);
        var output = new ByteArrayOutputStream();
        if (normalized == body) {
            header.writeTo(output);
        } else {
            lines[lengthHeader] = "Content-Length: " + normalized.length;
            output.write((String.join("\r\n", lines) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        output.write(normalized);
        frame = new ByteArrayInputStream(output.toByteArray());
        return true;
    }

    private static byte[] normalize(byte[] body) {
        try {
            var element = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (element.isJsonObject()) {
                var message = element.getAsJsonObject();
                var result = message.get("result");
                if (message.has("id") && !message.has("method") && !message.has("error")
                        && result != null && result.isJsonObject() && result.getAsJsonObject().size() == 0) {
                    message.add("result", com.google.gson.JsonNull.INSTANCE);
                    return message.toString().getBytes(StandardCharsets.UTF_8);
                }
            }
        } catch (com.google.gson.JsonParseException ignored) {
            // Let LSP4J report malformed JSON; only normalize the known empty-result bug.
        }
        return body;
    }

    @Override public void close() throws IOException {
        source.close();
    }
}
