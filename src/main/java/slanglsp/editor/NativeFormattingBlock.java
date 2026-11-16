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
class NativeFormattingBlock implements BlockEx {
    private static final Set<String> CONTROL = Set.of("if", "for", "while", "switch", "catch");
    private static final Set<String> OPERATORS = Set.of(
            "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
            "<<", ">>", "<<=", ">>=", "->", "::");
    private final String source;
    private final List<Block> children = new ArrayList<>();
    private final boolean newLineBraces;
    private final com.intellij.psi.codeStyle.CommonCodeStyleSettings common;
    private final SlangCodeStyleSettings custom;
    private final Set<Integer> functionStarts = new java.util.HashSet<>();

    NativeFormattingBlock(String source, CodeStyleSettings settings) {
        this.source = source;
        custom = settings.getCustomSettings(SlangCodeStyleSettings.class);
        common = settings.getCommonSettings(SlangLanguage.INSTANCE);
        newLineBraces = custom.BRACES_ON_NEW_LINE;
        var indent = settings.getIndentOptions(slanglsp.language.SlangFileType.INSTANCE);
        var generic = new GenericContext(source);
        var lexer = new SlangLexer();
        lexer.start(source);
        int braces = 0;
        int parentheses = 0;
        int unbraced = 0;
        var parenKinds = new ArrayList<Boolean>();
        var parameterWraps = new ArrayList<Wrap>();
        var parameterAlignments = new ArrayList<Alignment>();
        var braceParens = new ArrayList<Integer>();
        var braceKinds = new ArrayList<Integer>();
        boolean pendingType = false;
        boolean pendingFunction = false;
        boolean statementAssignment = false;
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
            int braceKind = 0; // 1: function, 2: type/namespace, 3: control/initializer
            if (Set.of("struct", "class", "interface", "enum", "namespace", "cbuffer", "extension").contains(text)) {
                pendingType = true;
            }
            if (text.equals("=")) statementAssignment = true;
            if (text.equals(")")) {
                parentheses = Math.max(0, parentheses - 1);
                if (!parenKinds.isEmpty()) closesControl = parenKinds.removeLast();
                if (!parameterWraps.isEmpty()) parameterWraps.removeLast();
                if (!parameterAlignments.isEmpty()) parameterAlignments.removeLast();
                if (parentheses == 0 && !closesControl && !statementAssignment) pendingFunction = true;
            }
            if (text.equals("}")) {
                braces = Math.max(0, braces - 1);
                if (!braceParens.isEmpty()) parentheses = braceParens.removeLast();
                if (!braceKinds.isEmpty()) braceKind = braceKinds.removeLast();
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
            if (caseDepth == braces && !text.equals("}")) {
                if (!common.INDENT_CASE_FROM_SWITCH) columns -= indent.INDENT_SIZE;
                if (text.equals("break") && !common.INDENT_BREAK_FROM_CASE) columns -= indent.INDENT_SIZE;
            }
            boolean prefix = Set.of("!", "~").contains(text)
                    || (Set.of("+", "-", "++", "--", "*", "&").contains(text)
                    && (previous == null || Set.of("(", "[", "{", "=", ",", ";", "return", ":", "?").contains(before)
                    || previous.prefix || previous.type == SlangLexer.OPERATOR && !previous.angle
                    && !Set.of("++", "--").contains(before)));
            Wrap wrap = null;
            Alignment alignment = null;
            if ((before.equals(",") || before.equals("(")) && !text.equals(")") && !parameterWraps.isEmpty()) {
                wrap = parameterWraps.getLast();
                alignment = parameterAlignments.getLast();
            } else if (before.equals("=") && common.ASSIGNMENT_WRAP != 0) {
                wrap = Wrap.createWrap(WrapType.byLegacyRepresentation(common.ASSIGNMENT_WRAP), true);
            }
            if (text.equals("{")) braceKind = pendingType ? 2 : pendingFunction ? 1 : 3;
            children.add(new Leaf(text, type, new TextRange(start, end), directive ? 0 : columns,
                    parentheses, braces, braceKind, braces == 0 || !braceKinds.isEmpty() && braceKinds.getLast() == 2,
                    directive, angle, prefix, labelColon, wrap, alignment));
            if (text.equals("{")) {
                if (braceKind == 1 && braces == 0) {
                    int first = children.size() - 2;
                    while (first >= 0) {
                        Leaf token = (Leaf) children.get(first);
                        if (Set.of(";", "}").contains(token.text) || token.directive
                                || token.type == SlangLexer.COMMENT || token.type == SlangLexer.BLOCK_COMMENT) break;
                        first--;
                    }
                    functionStarts.add(children.get(first + 1).getTextRange().getStartOffset());
                }
                braces++;
                braceParens.add(parentheses);
                braceKinds.add(braceKind);
                parentheses = 0;
                unbraced = 0;
            } else if (text.equals("(")) {
                parentheses++;
                parenKinds.add(CONTROL.contains(before));
                parameterWraps.add(!CONTROL.contains(before) && before.matches("[A-Za-z_][A-Za-z_0-9]*")
                        && common.CALL_PARAMETERS_WRAP != 0
                        ? Wrap.createWrap(WrapType.byLegacyRepresentation(common.CALL_PARAMETERS_WRAP), true) : null);
                parameterAlignments.add(!CONTROL.contains(before) && before.matches("[A-Za-z_][A-Za-z_0-9]*")
                        && common.ALIGN_MULTILINE_PARAMETERS_IN_CALLS ? Alignment.createAlignment() : null);
            } else if (closesControl) {
                int next = end;
                while (next < source.length() && Character.isWhitespace(source.charAt(next))) next++;
                if (next < source.length() && source.charAt(next) != '{' && source.charAt(next) != ';') unbraced++;
            } else if (text.equals(";") && parentheses == 0) {
                unbraced = 0;
            }
            if (Set.of(";", "{", "}").contains(text)) {
                pendingType = false;
                pendingFunction = false;
                statementAssignment = false;
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
        int keptBlankLines = left.declarationContext ? common.KEEP_BLANK_LINES_IN_DECLARATIONS : common.KEEP_BLANK_LINES_IN_CODE;
        if (left.directive || right.directive || left.type == SlangLexer.COMMENT) return lines(1, keptBlankLines);
        if (right.type == SlangLexer.COMMENT || right.type == SlangLexer.BLOCK_COMMENT
                || left.type == SlangLexer.BLOCK_COMMENT) return spaces(1, true);
        if (functionStarts.contains(right.range.getStartOffset()) && common.BLANK_LINES_AROUND_METHOD > 0)
            return lines(common.BLANK_LINES_AROUND_METHOD + 1, common.KEEP_BLANK_LINES_IN_DECLARATIONS);
        if (a.equals("{") && b.equals("}")) return spaces(0, false);
        if (b.equals("{")) return braceOnNewLine(right.braceKind) ? line() : spaces(1, false);
        if (b.equals("}")) return lines(1, common.KEEP_BLANK_LINES_BEFORE_RBRACE);
        if (a.equals("{")) return line();
        if (a.equals(";")) return left.parentheses == 0 ? lines(1, keptBlankLines)
                : spaces(common.SPACE_AFTER_SEMICOLON ? 1 : 0, false);
        if (left.labelColon) return line();
        if (right.labelColon) return spaces(0, false);
        if (a.equals("}")) {
            if (Set.of(";", ",", ")", "]", ".", "(", "::").contains(b)) return spaces(0, false);
            switch (b) {
                case "else" -> {
                    return common.ELSE_ON_NEW_LINE ? line() : spaces(1, false);
                }
                case "catch" -> {
                    return common.CATCH_ON_NEW_LINE ? line() : spaces(1, false);
                }
                case "while" -> {
                    return common.WHILE_ON_NEW_LINE ? line() : spaces(1, false);
                }
                default -> {
                }
            }
            if (left.braceKind == 1 && left.braces == 0)
                return lines(common.BLANK_LINES_AROUND_METHOD + 1, common.KEEP_BLANK_LINES_IN_DECLARATIONS);
            return lines(1, keptBlankLines);
        }
        if (b.equals(",")) return spaces(common.SPACE_BEFORE_COMMA ? 1 : 0, false);
        if (a.equals("(") || b.equals(")"))
            return spaces(a.equals("(") && b.equals(")") ? 0 : common.SPACE_WITHIN_PARENTHESES ? 1 : 0, false);
        if (a.equals("[") || b.equals("]"))
            return spaces(a.equals("[") && b.equals("]") ? 0 : common.SPACE_WITHIN_BRACKETS ? 1 : 0, false);
        if (Set.of(";", ".", "::", "->").contains(b)
                || Set.of(".", "::", "->").contains(a)) return spaces(0, false);
        if (b.equals("(")) return spaces(spaceBeforeParentheses(a) ? 1 : 0, false);
        if (a.equals("]") && gap.contains("\n")) return line(); // Keep shader attribute lines separate.
        if (b.equals("[")) return spaces(a.equals("]") ? 1 : 0, true);
        if (a.equals(",")) return spaces(common.SPACE_AFTER_COMMA ? 1 : 0, true);
        if (right.angle && b.equals("<") || left.angle && a.equals("<") || right.angle && b.equals(">"))
            return spaces(0, false);
        if (left.angle && a.equals(">")) return spaces(b.equals(">") ? 0 : 1, true);
        if (left.prefix) return spaces(right.type == SlangLexer.OPERATOR ? 1 : 0, false);
        if (Set.of("++", "--").contains(b) && !right.prefix) return spaces(0, false);
        // Pointer declarations, dereferences, and unknown generic names need semantic information.
        // Preserve ambiguous gaps rather than inventing a different token sequence.
        if (Set.of("*", "&", "<", ">").contains(a) || Set.of("*", "&", "<", ">").contains(b))
            return Spacing.getReadOnlySpacing();
        if (left.type == SlangLexer.OPERATOR || right.type == SlangLexer.OPERATOR) {
            boolean space = operatorSpace(left.type == SlangLexer.OPERATOR ? a : b);
            // Removing whitespace between separate operators may create ++, --, etc.
            if (left.type == SlangLexer.OPERATOR && right.type == SlangLexer.OPERATOR) space = true;
            return spaces(space ? 1 : 0, true);
        }
        if (a.equals("]") && gap.contains("\n")) return line(); // Attributes preceding declarations.
        return spaces(1, true);
    }

    private boolean braceOnNewLine(int kind) {
        int value = kind == 1 ? custom.FUNCTION_BRACES : kind == 2 ? custom.TYPE_BRACES : custom.CONTROL_BRACES;
        return value == 0 ? newLineBraces : value == 2;
    }

    private boolean spaceBeforeParentheses(String token) {
        return switch (token) {
            case "if" -> common.SPACE_BEFORE_IF_PARENTHESES;
            case "for" -> common.SPACE_BEFORE_FOR_PARENTHESES;
            case "while" -> common.SPACE_BEFORE_WHILE_PARENTHESES;
            case "switch" -> common.SPACE_BEFORE_SWITCH_PARENTHESES;
            case "catch" -> common.SPACE_BEFORE_CATCH_PARENTHESES;
            default -> common.SPACE_BEFORE_METHOD_CALL_PARENTHESES;
        };
    }

    private boolean operatorSpace(String token) {
        return switch (token) {
            case "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=" -> common.SPACE_AROUND_ASSIGNMENT_OPERATORS;
            case "&&", "||" -> common.SPACE_AROUND_LOGICAL_OPERATORS;
            case "==", "!=" -> common.SPACE_AROUND_EQUALITY_OPERATORS;
            case "+", "-" -> common.SPACE_AROUND_ADDITIVE_OPERATORS;
            case "<<", ">>" -> common.SPACE_AROUND_SHIFT_OPERATORS;
            default -> true;
        };
    }

    private Spacing spaces(int count, boolean keepBreaks) {
        return Spacing.createSpacing(count, count, 0, keepBreaks && common.KEEP_LINE_BREAKS,
                common.KEEP_BLANK_LINES_IN_CODE);
    }

    private Spacing line() {
        return lines(1, common.KEEP_BLANK_LINES_IN_CODE);
    }

    private static Spacing lines(int count, int keepBlankLines) {
        return Spacing.createSpacing(0, 0, Math.max(1, count), true, Math.max(0, keepBlankLines));
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
                        int braces, int braceKind, boolean declarationContext,
                        boolean directive, boolean angle, boolean prefix, boolean labelColon,
                        Wrap wrap, Alignment alignment) implements BlockEx {
        @Override public @NotNull TextRange getTextRange() { return range; }
        @Override public @NotNull com.intellij.lang.Language getLanguage() { return SlangLanguage.INSTANCE; }
        @Override public @NotNull List<Block> getSubBlocks() { return List.of(); }
        @Override public @Nullable Wrap getWrap() { return wrap; }
        @Override public @Nullable Indent getIndent() { return Indent.getSpaceIndent(columns, true); }
        @Override public @Nullable Alignment getAlignment() { return alignment; }
        @Override public @Nullable Spacing getSpacing(Block left, @NotNull Block right) { return null; }
        @Override public @NotNull ChildAttributes getChildAttributes(int index) { return new ChildAttributes(Indent.getNoneIndent(), null); }
        @Override public boolean isIncomplete() { return false; }
        @Override public boolean isLeaf() { return true; }
    }
}
