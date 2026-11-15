package slanglsp.editor;

import com.intellij.formatting.*;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.TokenType;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import slanglsp.language.SlangLanguage;
import slanglsp.language.SlangLexer;
import slanglsp.settings.SlangCodeStyleSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The parser exposes token-level PSI. Virtual formatting blocks retain exact token ranges,
 * so formatting never rewrites strings, comments, directives, or the PSI structure.
 */
class SlangFormattingBlock implements BlockEx {
    private static final Set<String> CONTROL = Set.of("if", "for", "while", "switch", "catch");
    private static final Set<String> OPERATORS = Set.of(
            "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
            "<<", ">>", "<<=", ">>=", "->", "::");
    private final String source;
    private final List<Block> children = new ArrayList<>();
    private final boolean newLineBraces;

    SlangFormattingBlock(String source, CodeStyleSettings settings) {
        this.source = source;
        newLineBraces = settings.getCustomSettings(SlangCodeStyleSettings.class).BRACES_ON_NEW_LINE;
        var indent = settings.getIndentOptions(slanglsp.language.SlangFileType.INSTANCE);
        var generic = new GenericContext(source);
        var lexer = new SlangLexer();
        lexer.start(source);
        int braces = 0;
        int parentheses = 0;
        int unbraced = 0;
        var parenKinds = new ArrayList<Boolean>();
        var braceParens = new ArrayList<Integer>();
        boolean caseLabel = false;
        int caseDepth = -1;
        while (lexer.getTokenType() != null) {
            ProgressManager.checkCanceled();
            IElementType type = lexer.getTokenType();
            int start = lexer.getTokenStart();
            int end = lexer.getTokenEnd();
            if (type == TokenType.WHITE_SPACE) {
                lexer.advance();
                continue;
            }
            boolean directive = type == SlangLexer.DIRECTIVE;
            if (directive) {
                end = directiveEnd(source, start);
            }
            String text = source.substring(start, end);
            boolean angle = (text.equals("<") && generic.isOpening(generic.tokenAt(start)))
                    || (text.equals(">") && generic.isClosing(generic.tokenAt(start)));
            if (type == SlangLexer.OPERATOR && !angle) {
                // Keep actual multi-character operators atomic, but never merge spaced tokens.
                for (int length = 3; length >= 2; length--) {
                    if (start + length <= source.length() && OPERATORS.contains(source.substring(start, start + length))) {
                        text = source.substring(start, start + length);
                        end = start + length;
                        break;
                    }
                }
            }
            Leaf previous = children.isEmpty() ? null : (Leaf) children.getLast();
            String before = previous == null ? "" : previous.text;
            boolean closesControl = false;
            if (text.equals(")")) {
                parentheses = Math.max(0, parentheses - 1);
                if (!parenKinds.isEmpty()) closesControl = parenKinds.removeLast();
            }
            if (text.equals("}")) {
                braces = Math.max(0, braces - 1);
                if (!braceParens.isEmpty()) parentheses = braceParens.removeLast();
                unbraced = 0;
                if (braces < caseDepth) caseDepth = -1;
            }
            if (text.equals("case") || text.equals("default")) {
                caseLabel = true;
                caseDepth = braces;
            }
            boolean labelColon = caseLabel && text.equals(":");
            int columns = braces * indent.INDENT_SIZE + parentheses * indent.CONTINUATION_INDENT_SIZE;
            if (unbraced > 0 && !text.equals("{") && !text.equals("else")) columns += indent.INDENT_SIZE;
            if (caseDepth == braces && !caseLabel && !text.equals("}")) columns += indent.INDENT_SIZE;
            boolean prefix = Set.of("!", "~").contains(text)
                    || (Set.of("+", "-", "++", "--", "*", "&").contains(text)
                    && (previous == null || Set.of("(", "[", "{", "=", ",", ";", "return", ":", "?").contains(before)
                    || previous.prefix || previous.type == SlangLexer.OPERATOR && !previous.angle
                    && !Set.of("++", "--").contains(before)));
            boolean wrap = previous != null && (before.equals(",") || before.equals("="));
            children.add(new Leaf(text, type, new TextRange(start, end), directive ? 0 : columns,
                    parentheses, directive, angle, prefix, labelColon, wrap));
            if (text.equals("{")) {
                braces++;
                braceParens.add(parentheses);
                parentheses = 0;
                unbraced = 0;
            } else if (text.equals("(")) {
                parentheses++;
                parenKinds.add(CONTROL.contains(before));
            } else if (closesControl) {
                int next = end;
                while (next < source.length() && Character.isWhitespace(source.charAt(next))) next++;
                if (next < source.length() && source.charAt(next) != '{' && source.charAt(next) != ';') unbraced++;
            } else if (text.equals(";") && parentheses == 0) {
                unbraced = 0;
            }
            if (labelColon) caseLabel = false;
            do lexer.advance(); while (lexer.getTokenType() != null && lexer.getTokenStart() < end);
        }
    }

    private static int directiveEnd(String source, int start) {
        int end = start;
        while (end < source.length()) {
            int newline = source.indexOf('\n', end);
            if (newline < 0) return source.length();
            int last = newline - 1;
            if (last >= start && source.charAt(last) == '\r') last--;
            if (last < start || source.charAt(last) != '\\') return newline;
            end = newline + 1;
        }
        return end;
    }

    @Override
    public @Nullable Spacing getSpacing(@Nullable Block child1, @NotNull Block child2) {
        if (!(child1 instanceof Leaf left) || !(child2 instanceof Leaf right)) return null;
        String a = left.text, b = right.text;
        String gap = source.substring(left.range.getEndOffset(), right.range.getStartOffset());
        if (left.directive || right.directive || left.type == SlangLexer.COMMENT) return line();
        if (right.type == SlangLexer.COMMENT || right.type == SlangLexer.BLOCK_COMMENT
                || left.type == SlangLexer.BLOCK_COMMENT) return spaces(1, true);
        if (a.equals("{") && b.equals("}")) return spaces(0, false);
        if (b.equals("{")) return newLineBraces ? line() : spaces(1, false);
        if (a.equals("{") || b.equals("}")) return line();
        if (a.equals(";")) return left.parentheses == 0 ? line() : spaces(1, false);
        if (left.labelColon) return line();
        if (a.equals("}")) {
            if (Set.of(";", ",", ")", "]", ".", "(", "::").contains(b)) return spaces(0, false);
            if (Set.of("else", "catch", "while").contains(b)) return newLineBraces ? line() : spaces(1, false);
            return line();
        }
        if (Set.of(";", ",", ")", "]", ".", "::", "->").contains(b)
                || Set.of("(", "[", ".", "::", "->").contains(a)) return spaces(0, false);
        if (b.equals("(")) return spaces(CONTROL.contains(a) ? 1 : 0, false);
        if (b.equals("[")) return spaces(a.equals("]") ? 1 : 0, true);
        if (a.equals(",")) return spaces(1, true);
        if (right.angle && b.equals("<") || left.angle && a.equals("<") || right.angle && b.equals(">"))
            return spaces(0, false);
        if (left.angle && a.equals(">")) return spaces(b.equals(">") ? 0 : 1, true);
        if (left.prefix) return spaces(right.type == SlangLexer.OPERATOR ? 1 : 0, false);
        if (Set.of("++", "--").contains(b) && !right.prefix) return spaces(0, false);
        // Pointer declarations, dereferences, and unknown generic names need semantic information.
        // Preserve ambiguous gaps rather than inventing a different token sequence.
        if (Set.of("*", "&", "<", ">").contains(a) || Set.of("*", "&", "<", ">").contains(b))
            return Spacing.getReadOnlySpacing();
        if (left.type == SlangLexer.OPERATOR || right.type == SlangLexer.OPERATOR) return spaces(1, true);
        if (a.equals("]") && gap.contains("\n")) return line(); // Attributes preceding declarations.
        return spaces(1, true);
    }

    private static Spacing spaces(int count, boolean keepBreaks) {
        return Spacing.createSpacing(count, count, 0, keepBreaks, 2);
    }

    private static Spacing line() {
        return Spacing.createSpacing(0, 0, 1, true, 2);
    }

    @Override public @NotNull TextRange getTextRange() { return new TextRange(0, source.length()); }
    @Override public @NotNull com.intellij.lang.Language getLanguage() { return SlangLanguage.INSTANCE; }
    @Override public @NotNull List<Block> getSubBlocks() { return children; }
    @Override public @Nullable Wrap getWrap() { return null; }
    @Override public @Nullable Indent getIndent() { return Indent.getAbsoluteNoneIndent(); }
    @Override public @Nullable Alignment getAlignment() { return null; }
    @Override public @NotNull ChildAttributes getChildAttributes(int index) { return new ChildAttributes(Indent.getNoneIndent(), null); }
    @Override public boolean isIncomplete() { return false; }
    @Override public boolean isLeaf() { return children.isEmpty(); }

    private record Leaf(String text, IElementType type, TextRange range, int columns, int parentheses,
                        boolean directive, boolean angle, boolean prefix, boolean labelColon, boolean wrap) implements BlockEx {
        @Override public @NotNull TextRange getTextRange() { return range; }
        @Override public @NotNull com.intellij.lang.Language getLanguage() { return SlangLanguage.INSTANCE; }
        @Override public @NotNull List<Block> getSubBlocks() { return List.of(); }
        @Override public @Nullable Wrap getWrap() { return wrap ? Wrap.createWrap(WrapType.NORMAL, true) : null; }
        @Override public @Nullable Indent getIndent() { return Indent.getSpaceIndent(columns, true); }
        @Override public @Nullable Alignment getAlignment() { return null; }
        @Override public @Nullable Spacing getSpacing(Block left, @NotNull Block right) { return null; }
        @Override public @NotNull ChildAttributes getChildAttributes(int index) { return new ChildAttributes(Indent.getNoneIndent(), null); }
        @Override public boolean isIncomplete() { return false; }
        @Override public boolean isLeaf() { return true; }
    }
}
