package slanglsp;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.redhat.devtools.lsp4ij.client.features.LSPHoverFeature;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

/** Recover documentation stripped from slangd's serialized standard library. */
final class SlangHoverFeature extends LSPHoverFeature {
    private static final Pattern BUILTIN_LOCATION = Pattern.compile(
            "(?m)^Defined in (core|glsl)\\(([1-9][0-9]*)\\)\\s*$");

    @Override public String getContent(@NotNull MarkupContent content, @NotNull PsiFile file) {
        if (MarkupKind.MARKDOWN.equals(content.getKind())) {
            String markdown = content.getValue();
            var location = BUILTIN_LOCATION.matcher(markdown);
            if (location.find() && lacksExplanation(markdown.substring(0, location.start()))) {
                int line;
                try {
                    line = Integer.parseInt(location.group(2));
                } catch (NumberFormatException invalid) {
                    return super.getContent(content, file);
                }
                String module = location.group(1);
                var builtin = file.getProject().getService(SlangBuiltinFiles.class)
                        .resolve("slang-synth://" + module + "/" + module + ".builtin");
                if (builtin != null) {
                    String documentation = documentationAtLine(builtin.getContent(), line);
                    if (!documentation.isBlank()) {
                        content = new MarkupContent(MarkupKind.MARKDOWN,
                                markdown.substring(0, location.start()).stripTrailing() + "\n\n"
                                        + documentation + "\n\n" + markdown.substring(location.start()));
                    }
                }
            }
        }
        return super.getContent(content, file);
    }

    private static boolean lacksExplanation(String prefix) {
        // Keep documentation supplied by newer servers, including its formatting.
        return prefix.replaceFirst("(?s)\\A```[^\\n]*\\n.*?```", "")
                .replaceAll("(?m)^(Forward|Backward) derivative:.*$", "").isBlank();
    }

    static String documentationAtLine(CharSequence source, int line) {
        if (line < 1) return "";
        int offset = 0;
        for (int current = 1; current < line; offset++) {
            if (offset >= source.length()) return "";
            if (source.charAt(offset) == '\n') current++;
        }
        if (offset >= source.length()) return "";
        var lexer = new SlangLexer();
        lexer.start(source);
        StringBuilder comment = new StringBuilder();
        boolean previousDocLine = false;
        while (lexer.getTokenType() != null && lexer.getTokenStart() < offset) {
            ProgressManager.checkCanceled();
            var type = lexer.getTokenType();
            String token = lexer.getTokenText();
            if (type == SlangLexer.BLOCK_COMMENT && token.startsWith("/**")) {
                comment.setLength(0);
                comment.append(token.replaceFirst("^/\\*+", "").replaceFirst("\\*+/$", "")
                        .replaceAll("(?m)^\\h*\\* ?", ""));
                previousDocLine = false;
            } else if (type == SlangLexer.COMMENT && token.startsWith("///")) {
                if (!previousDocLine) comment.setLength(0);
                comment.append(token.substring(3).stripLeading()).append('\n');
                previousDocLine = true;
            } else if (type != TokenType.WHITE_SPACE) {
                previousDocLine = false;
                // Never borrow docs from a preceding declaration or enclosing type.
                if (type == SlangLexer.SEMICOLON || type == SlangLexer.BRACES
                        || type == SlangLexer.COMMENT || type == SlangLexer.BLOCK_COMMENT
                        || type == SlangLexer.DIRECTIVE) comment.setLength(0);
            }
            lexer.advance();
        }
        return renderComment(comment.toString());
    }

    private static String renderComment(String comment) {
        return comment
                .replaceAll("(?m)^\\h*@param\\s+(\\w+)\\h*", "\n**Parameter `$1`:** ")
                .replaceAll("(?m)^\\h*@returns?\\h*", "\n**Returns:** ")
                .replaceAll("(?m)^\\h*@remarks\\h*", "\n**Remarks**\n\n")
                .replaceAll("(?m)^\\h*@see\\h*", "\n**See also:** ")
                .replaceAll("(?m)^\\h*@category[^\\n]*", "")
                .strip();
    }
}
