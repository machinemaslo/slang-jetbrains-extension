package slanglsp.server.protocol

import com.intellij.openapi.editor.Document
import org.eclipse.lsp4j.Position

/** Converts UTF-16 positions, clamping to the line or document boundary. */
internal fun Document.protocolOffset(position: Position): Int {
    val line = position.line
    if (line < 0) {
        return 0
    }
    if (line >= lineCount) {
        return textLength
    }

    val start = getLineStartOffset(line)
    val end = getLineEndOffset(line)
    return start + position.character.coerceIn(0, end - start)
}
