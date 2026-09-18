package slanglsp;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.TokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public class SlangLexer extends LexerBase {
    private CharSequence buffer;
    private int endOffset;
    private int position;
    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;
    private int state;
    private int tokenState;
    private static final int MODULE_PATH = 1;
    private static final java.util.regex.Pattern BUILTIN_TYPE_PATTERN =
            java.util.regex.Pattern.compile("(int|float|uint|bool|half|double)[1-4]?(x[1-4])?");

    static boolean isBuiltinType(String word) {
        return BUILTIN_TYPE_PATTERN.matcher(word).matches();
    }

    public static final IElementType MODULE_NAME = new IElementType("MODULE_NAME", SlangLanguage.INSTANCE);
    public static final IElementType KEYWORD = new IElementType("KEYWORD", SlangLanguage.INSTANCE);
    public static final IElementType BUILTIN_TYPE = new IElementType("BUILTIN_TYPE", SlangLanguage.INSTANCE);
    public static final IElementType DIRECTIVE = new IElementType("DIRECTIVE", SlangLanguage.INSTANCE);
    public static final IElementType OPERATOR = new IElementType("OPERATOR", SlangLanguage.INSTANCE);
    public static final IElementType BRACES = new IElementType("BRACES", SlangLanguage.INSTANCE);
    public static final IElementType BRACKETS = new IElementType("BRACKETS", SlangLanguage.INSTANCE);
    public static final IElementType PARENTHESES = new IElementType("PARENTHESES", SlangLanguage.INSTANCE);
    public static final IElementType COMMA = new IElementType("COMMA", SlangLanguage.INSTANCE);
    public static final IElementType DOT = new IElementType("DOT", SlangLanguage.INSTANCE);
    public static final IElementType SEMICOLON = new IElementType("SEMICOLON", SlangLanguage.INSTANCE);
    public static final IElementType TYPE = new IElementType("TYPE", SlangLanguage.INSTANCE);
    public static final IElementType FUNCTION = new IElementType("FUNCTION", SlangLanguage.INSTANCE);
    public static final IElementType VARIABLE = new IElementType("VARIABLE", SlangLanguage.INSTANCE);
    public static final IElementType STRING = new IElementType("STRING", SlangLanguage.INSTANCE);
    public static final IElementType NUMBER = new IElementType("NUMBER", SlangLanguage.INSTANCE);
    public static final IElementType COMMENT = new IElementType("COMMENT", SlangLanguage.INSTANCE);
    public static final IElementType BLOCK_COMMENT = new IElementType("BLOCK_COMMENT", SlangLanguage.INSTANCE);
    public static final IElementType TEXT = new IElementType("TEXT", SlangLanguage.INSTANCE);

    // Lexical keywords mirror the official VS Code grammar; slangd does not emit all of them.
    private static final Set<String> KEYWORDS = Set.of(
            "struct", "interface", "enum", "extension", "typealias", "associatedtype", "let",
            "var", "void", "return", "if", "else", "for", "while",
            "do", "switch", "case", "default", "break", "continue", "static",
            "const", "true", "false", "in", "out", "inout", "__init",
            "operator", "public", "private", "protected", "internal", "import", "module",
            "implementing", "property", "get", "set", "yield", "defer", "as",
            "is", "reinterpret", "sizeof", "alignof", "typeof", "groupshared", "uniform",
            "inline", "extern", "export", "class", "namespace", "using", "typedef",
            "this", "discard", "null", "nullptr", "mutating", "nonmutating", "try",
            "throw", "throws", "catch", "spirv_asm", "__target_switch", "__stage_switch", "__intrinsic_asm",
            "__GPU_FOREACH", "dynamic_uniform", "shared", "volatile", "coherent", "restrict", "readonly",
            "writeonly", "override", "__extern_cpp", "param", "require", "row_major", "column_major",
            "nointerpolation", "noperspective", "linear", "sample", "centroid", "precise", "ref",
            "__ref", "__constref", "dyn", "some", "implicit", "noncopyable", "constexpr",
            "highp", "lowp", "mediump", "__builtin", "__global", "point", "line",
            "triangle", "lineadj", "triangleadj", "vertices", "indices", "primitives", "payload",
            "__prefix", "__postfix", "__exported", "layout", "hitAttributeEXT", "__intrinsic_op", "__target_intrinsic",
            "__specialized_for_target", "__glsl_extension", "__glsl_version", "__spirv_version", "__wgsl_extension", "__cuda_sm_version", "__builtin_type",
            "__builtin_requirement", "__magic_type", "__magic_enum", "__intrinsic_type", "__implicit_conversion", "__attributeTarget", "This",
            "countof", "no_diff", "fwd_diff", "bwd_diff", "__fwd_diff", "__bwd_diff", "__dispatch_kernel",
            "each", "expand", "optional", "nonempty", "__return_val", "__first", "__last",
            "__trimFirst", "__trimLast", "__packBranch", "__getAddress", "__floatAsInt"
    );

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.endOffset = endOffset;
        this.position = startOffset;
        this.state = initialState;
        advance();
    }

    @Override
    public int getState() { return tokenState; }

    @Nullable
    @Override
    public IElementType getTokenType() { return tokenType; }

    @Override
    public int getTokenStart() { return tokenStart; }

    @Override
    public int getTokenEnd() { return tokenEnd; }

    @Override
    public void advance() {
        if (position >= endOffset) {
            tokenType = null;
            return;
        }

        tokenState = state;
        tokenStart = position;
        char c = buffer.charAt(position);

        if (Character.isWhitespace(c)) {
            while (position < endOffset && Character.isWhitespace(buffer.charAt(position))) position++;
            tokenType = TokenType.WHITE_SPACE;
        } else if (c == '/' && position + 1 < endOffset && buffer.charAt(position + 1) == '/') {
            while (position < endOffset && buffer.charAt(position) != '\n') position++;
            tokenType = COMMENT;
        } else if (c == '/' && position + 1 < endOffset && buffer.charAt(position + 1) == '*') {
            position += 2;
            while (position < endOffset) {
                if (buffer.charAt(position) == '*' && position + 1 < endOffset && buffer.charAt(position + 1) == '/') {
                    position += 2;
                    break;
                }
                position++;
            }
            tokenType = BLOCK_COMMENT;
        } else if (c == '"' || c == '\'') {
            char quote = c;
            position++;
            while (position < endOffset && buffer.charAt(position) != quote) {
                if (buffer.charAt(position) == '\\' && position + 1 < endOffset) position++;
                position++;
            }
            if (position < endOffset) position++;
            tokenType = STRING;
        } else if (isDigit(c) || (c == '.' && isDigit(peek(1)))) {
            readNumber();
            tokenType = NUMBER;
        } else if (c == '#') {
            position++;
            while (position < endOffset && (buffer.charAt(position) == ' ' || buffer.charAt(position) == '\t')) position++;
            while (position < endOffset && Character.isLetter(buffer.charAt(position))) position++;
            tokenType = DIRECTIVE;
        } else if (Character.isLetter(c) || c == '_') {
            while (position < endOffset && (Character.isLetterOrDigit(buffer.charAt(position)) || buffer.charAt(position) == '_')) position++;
            String word = buffer.subSequence(tokenStart, position).toString();
            if (state == MODULE_PATH) {
                while (position + 1 < endOffset && buffer.charAt(position) == '.'
                        && (Character.isLetter(buffer.charAt(position + 1)) || buffer.charAt(position + 1) == '_')) {
                    position++;
                    while (position < endOffset && (Character.isLetterOrDigit(buffer.charAt(position))
                            || buffer.charAt(position) == '_')) position++;
                }
                tokenType = MODULE_NAME;
            } else if (isBuiltinType(word)) {
                tokenType = BUILTIN_TYPE;
            } else if (KEYWORDS.contains(word)) {
                tokenType = KEYWORD;
            } else {
                int peek = position;
                while (peek < endOffset && Character.isWhitespace(buffer.charAt(peek))) peek++;
                boolean isFunction = peek < endOffset && buffer.charAt(peek) == '(';
                
                if (isFunction) {
                    tokenType = FUNCTION;
                } else if (Character.isUpperCase(word.charAt(0))) {
                    tokenType = TYPE;
                } else {
                    tokenType = VARIABLE;
                }
            }
        } else {
            position++;
            if (c == '.' && peek(0) == '.') {
                position++;
                if (peek(0) == '.') position++;
            }
            tokenType = switch (c) {
                case '{', '}' -> BRACES;
                case '[', ']' -> BRACKETS;
                case '(', ')' -> PARENTHESES;
                case ',' -> COMMA;
                case '.' -> DOT;
                case ';' -> SEMICOLON;
                case '+', '-', '*', '/', '%', '=', '!', '<', '>', '&', '|', '^', '~', '?', ':' -> OPERATOR;
                default -> TEXT;
            };
        }
        if (tokenType != TokenType.WHITE_SPACE && tokenType != COMMENT && tokenType != BLOCK_COMMENT) {
            String text = buffer.subSequence(tokenStart, position).toString();
            state = tokenType == KEYWORD && (text.equals("import") || text.equals("module")) ? MODULE_PATH : 0;
        }
        tokenEnd = position;
    }

    private char peek(int distance) {
        int index = position + distance;
        return index < endOffset ? buffer.charAt(index) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void readNumber() {
        int base = 10;
        if (peek(0) == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            base = 16;
            position += 2;
        } else if (peek(0) == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
            base = 2;
            position += 2;
        }
        readDigits(base);
        // Preserve scalar swizzles and range punctuation instead of swallowing their dots.
        if (peek(0) == '.' && peek(1) != '.' && peek(1) != 'x' && peek(1) != 'r') {
            position++;
            readDigits(base);
        }
        char exponent = peek(0);
        if ((base == 10 && (exponent == 'e' || exponent == 'E'))
                || (base == 16 && (exponent == 'p' || exponent == 'P'))) {
            position++;
            if (peek(0) == '+' || peek(0) == '-') position++;
            readDigits(10);
        }
        // Like slangd, keep suffixes in the literal and let the compiler validate them.
        while (isDigit(peek(0)) || (peek(0) >= 'a' && peek(0) <= 'z')
                || (peek(0) >= 'A' && peek(0) <= 'Z') || peek(0) == '_') position++;
    }

    private void readDigits(int base) {
        while (isDigit(peek(0)) || (base == 16 && ((peek(0) >= 'a' && peek(0) <= 'f')
                || (peek(0) >= 'A' && peek(0) <= 'F')))) position++;
    }

    @NotNull
    @Override
    public CharSequence getBufferSequence() { return buffer; }

    @Override
    public int getBufferEnd() { return endOffset; }
}
