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

    public static final IElementType MODULE_NAME = new IElementType("MODULE_NAME", SlangLanguage.INSTANCE);
    public static final IElementType KEYWORD = new IElementType("KEYWORD", SlangLanguage.INSTANCE);
    public static final IElementType TYPE = new IElementType("TYPE", SlangLanguage.INSTANCE);
    public static final IElementType FUNCTION = new IElementType("FUNCTION", SlangLanguage.INSTANCE);
    public static final IElementType VARIABLE = new IElementType("VARIABLE", SlangLanguage.INSTANCE);
    public static final IElementType STRING = new IElementType("STRING", SlangLanguage.INSTANCE);
    public static final IElementType NUMBER = new IElementType("NUMBER", SlangLanguage.INSTANCE);
    public static final IElementType COMMENT = new IElementType("COMMENT", SlangLanguage.INSTANCE);
    public static final IElementType BLOCK_COMMENT = new IElementType("BLOCK_COMMENT", SlangLanguage.INSTANCE);
    public static final IElementType TEXT = new IElementType("TEXT", SlangLanguage.INSTANCE);

    private static final Set<String> KEYWORDS = Set.of(
            "struct", "interface", "enum", "extension", "typealias", "associatedtype", "let", "var", "void", "return",
            "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "static", "const",
            "true", "false", "in", "out", "inout", "__init", "operator", "public", "private", "protected", "internal",
            "import", "module", "implementing", "property", "get", "set", "yield", "defer", "as", "is", "reinterpret",
            "sizeof", "alignof", "typeof", "groupshared", "uniform", "inline", "extern", "export", "class",
            "namespace", "using", "typedef", "this", "discard", "null", "nullptr", "mutating", "nonmutating"
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
        } else if (Character.isDigit(c)) {
            while (position < endOffset && (Character.isDigit(buffer.charAt(position)) || buffer.charAt(position) == '.')) position++;
            tokenType = NUMBER;
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
            } else if (KEYWORDS.contains(word) || word.matches("(int|float|uint|bool|half|double)[1-4]?(x[1-4])?")) {
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
            tokenType = TEXT;
        }
        if (tokenType != TokenType.WHITE_SPACE && tokenType != COMMENT && tokenType != BLOCK_COMMENT) {
            String text = buffer.subSequence(tokenStart, position).toString();
            state = tokenType == KEYWORD && (text.equals("import") || text.equals("module")) ? MODULE_PATH : 0;
        }
        tokenEnd = position;
    }

    @NotNull
    @Override
    public CharSequence getBufferSequence() { return buffer; }

    @Override
    public int getBufferEnd() { return endOffset; }
}
