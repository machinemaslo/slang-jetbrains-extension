package slanglsp.editor;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import slanglsp.language.SlangLexer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Recognizes Slang generic syntax without treating every less-than operator as a bracket.
 * Names come from declarations, not the lexer's capitalization-based highlighting.
 */
final class GenericContext {
    private static final Set<String> GENERIC_INTRODUCERS =
            Set.of("__generic", "extension", "__init", "__subscript");
    private static final Set<String> NAMED_DECLARATIONS =
            Set.of("struct", "interface", "class", "enum", "typealias", "func");
    private static final Set<String> BUILTIN_GENERICS = Set.of(
            "vector", "matrix", "Ptr", "Optional", "Tuple", "IFunc", "bit_cast",
            "DifferentialPair", "DifferentialPtrPair", "CoopVec",
            "Buffer", "RWBuffer", "RasterizerOrderedBuffer",
            "StructuredBuffer", "RWStructuredBuffer", "RasterizerOrderedStructuredBuffer",
            "AppendStructuredBuffer", "ConsumeStructuredBuffer", "ConstantBuffer", "ParameterBlock",
            "Texture1D", "Texture1DArray", "Texture2D", "Texture2DArray", "Texture2DMS", "Texture2DMSArray",
            "Texture3D", "TextureCube", "TextureCubeArray",
            "RWTexture1D", "RWTexture1DArray", "RWTexture2D", "RWTexture2DArray", "RWTexture3D",
            "RasterizerOrderedTexture1D", "RasterizerOrderedTexture1DArray",
            "RasterizerOrderedTexture2D", "RasterizerOrderedTexture2DArray", "RasterizerOrderedTexture3D",
            "InputPatch", "OutputPatch", "PointStream", "LineStream", "TriangleStream");

    record Token(String text, IElementType type, int start, int end) {
        boolean identifier() {
            return type == SlangLexer.TYPE || type == SlangLexer.VARIABLE || type == SlangLexer.FUNCTION;
        }
    }

    private final List<Token> tokens = new ArrayList<>();
    private final List<Integer> parents = new ArrayList<>();
    private final Map<String, Integer> typeScopes = new HashMap<>();
    private final BiPredicate<String, String> externalGeneric;
    private final CharSequence source;

    GenericContext(CharSequence text) {
        this(text, (name, owner) -> false);
    }

    GenericContext(CharSequence text, BiPredicate<String, String> externalGeneric) {
        this.externalGeneric = externalGeneric;
        source = text;
        var lexer = new SlangLexer();
        lexer.start(text);
        while (lexer.getTokenType() != null) {
            ProgressManager.checkCanceled();
            var type = lexer.getTokenType();
            if (type != TokenType.WHITE_SPACE && type != SlangLexer.COMMENT && type != SlangLexer.BLOCK_COMMENT) {
                tokens.add(new Token(text.subSequence(lexer.getTokenStart(), lexer.getTokenEnd()).toString(),
                        type, lexer.getTokenStart(), lexer.getTokenEnd()));
            }
            lexer.advance();
        }
        var scopes = new ArrayList<Integer>();
        for (int i = 0; i < tokens.size(); i++) {
            parents.add(scopes.isEmpty() ? -1 : scopes.getLast());
            if (word(i).equals("{")) {
                scopes.add(i);
            } else if (word(i).equals("}") && !scopes.isEmpty()) {
                scopes.removeLast();
            } else if (Set.of("struct", "class", "interface", "namespace").contains(word(i))) {
                for (int j = i + 2; j < tokens.size(); j++) {
                    if (word(j).equals(";") || word(j).equals("}")) {
                        break;
                    }
                    if (word(j).equals("{")) {
                        typeScopes.put(word(i + 1), j);
                        break;
                    }
                }
            }
        }
        // Parameter names belong to their function body, not the surrounding namespace.
        for (int i = 1; i < tokens.size(); i++) {
            if (!word(i).equals("(")) {
                continue;
            }
            int name = i - 1;
            if (word(name).equals(">")) {
                int depth = 1;
                while (--name >= 0 && depth > 0) {
                    if (word(name).equals(">")) {
                        depth++;
                    } else if (word(name).equals("<")) {
                        depth--;
                    }
                }
            }
            if (!isDeclarationName(name)) {
                continue;
            }
            int close = i + 1;
            int depth = 1;
            while (close < tokens.size() && depth > 0) {
                if (word(close).equals("(")) {
                    depth++;
                } else if (word(close).equals(")")) {
                    depth--;
                }
                close++;
            }
            int body = close;
            while (body < tokens.size() && !Set.of("{", "}", ";").contains(word(body))) {
                body++;
            }
            int scope = word(body).equals("{") ? body : i;
            for (int parameter = i + 1; parameter < close - 1; parameter++) {
                parents.set(parameter, scope);
            }
        }
    }

    int tokenAt(int offset) {
        int low = 0;
        int high = tokens.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Token token = tokens.get(middle);
            if (offset < token.start) {
                high = middle - 1;
            } else if (offset >= token.end) {
                low = middle + 1;
            } else {
                return middle;
            }
        }
        return -1;
    }

    boolean isOpening(int index) {
        if (index < 1 || !word(index).equals("<") || tokens.get(index).type != SlangLexer.OPERATOR) {
            return false;
        }
        int lineStart = tokens.get(index).start;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        int first = lineStart;
        while (first < source.length() && (source.charAt(first) == ' ' || source.charAt(first) == '\t')) {
            first++;
        }
        if (first < source.length() && source.charAt(first) == '#') {
            return false;
        }
        Token name = tokens.get(index - 1);
        if (GENERIC_INTRODUCERS.contains(name.text) || name.text.equals("reinterpret")) {
            return true;
        }
        if (!name.identifier()) {
            return false;
        }
        if (isDeclarationName(index - 1) && !Set.of("let", "var", "each", "typename").contains(word(index - 2))) {
            return true;
        }

        // A value declaration shadows a generic name (including a built-in type).
        // Ignore declarations in completed sibling blocks.
        Boolean declared = null;
        int declarationDepth = -1;
        var scopes = enclosingScopes(index);
        boolean qualified = word(index - 2).equals(".") || word(index - 2).equals(":");
        String owner = qualified ? memberOwner(index - 1) : null;
        if (qualified && (owner == null || owner.isEmpty())) {
            return false;
        }
        Integer memberScope = typeScopes.get(owner);
        for (int i = 0; i < tokens.size(); i++) {
            ProgressManager.checkCanceled();
            if (!word(i).equals(name.text) || i == index - 1 || !isDeclarationName(i)) {
                continue;
            }
            int scope = enclosingScope(i);
            if (qualified ? memberScope == null || scope != memberScope : scope != -1 && !scopes.contains(scope)) {
                continue;
            }
            int depth = scope == -1 ? 0 : scopes.indexOf(scope) + 1;
            boolean generic = word(i + 1).equals("<") || hasGenericModifier(i);
            boolean typeOrFunction = NAMED_DECLARATIONS.contains(word(i - 1))
                    || word(i + 1).equals("(") || generic;
            if (!typeOrFunction && i > index) {
                continue;
            }
            if (depth >= declarationDepth) {
                if (depth == declarationDepth && declared && typeOrFunction) {
                    continue;
                }
                declared = generic;
                declarationDepth = depth;
            }
        }
        if (declared != null) {
            return declared;
        }
        return (!qualified && BUILTIN_GENERICS.contains(name.text))
                || externalGeneric.test(name.text, owner);
    }

    private String memberOwner(int nameIndex) {
        int receiver = nameIndex - 2;
        if (word(nameIndex - 1).equals(":") && word(nameIndex - 2).equals(":")) {
            receiver--;
        } else if (!word(nameIndex - 1).equals(".")) {
            return null;
        }
        if (receiver < 0) {
            return null;
        }
        String name = word(receiver);
        if (name.equals("this")) {
            var scopes = enclosingScopes(nameIndex);
            for (int i = scopes.size() - 1; i >= 0; i--) {
                for (var entry : typeScopes.entrySet()) {
                    if (entry.getValue().equals(scopes.get(i))) {
                        return entry.getKey();
                    }
                }
            }
            return "";
        }
        if (typeScopes.containsKey(name)) {
            return name;
        }
        // Resolve a directly declared receiver, including generic receiver types.
        var scopes = enclosingScopes(nameIndex);
        for (int i = receiver - 1; i >= 0; i--) {
            if (word(i).equals(name) && isDeclarationName(i)
                    && (enclosingScope(i) == -1 || scopes.contains(enclosingScope(i)))) {
                int type = i - 1;
                if (word(type).equals(">")) {
                    int depth = 1;
                    while (--type >= 0 && depth > 0) {
                        if (word(type).equals(">")) {
                            depth++;
                        } else if (word(type).equals("<")) {
                            depth--;
                        }
                    }
                }
                if (word(type).equals("let") || word(type).equals("var")) {
                    return word(i + 1).equals(":") ? word(i + 2) : "";
                }
                return word(type);
            }
        }
        return name;
    }

    boolean declaresGeneric(String name, String owner) {
        Integer scope = owner == null ? Integer.valueOf(-1) : typeScopes.get(owner);
        if (scope == null) {
            return false;
        }
        for (int i = 1; i < tokens.size(); i++) {
            if (word(i).equals(name) && enclosingScope(i) == scope && isDeclarationName(i)
                    && (word(i + 1).equals("<") || hasGenericModifier(i))) {
                return true;
            }
        }
        return false;
    }

    Set<String> imports() {
        var result = new java.util.HashSet<String>();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (word(i).equals("import")) {
                result.add(word(i + 1));
            }
        }
        return result;
    }

    String moduleName() {
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (word(i).equals("module")) {
                return word(i + 1);
            }
        }
        return null;
    }

    private boolean isDeclarationName(int index) {
        if (index < 1 || !tokens.get(index).identifier()) {
            return false;
        }
        String previous = word(index - 1);
        if (NAMED_DECLARATIONS.contains(previous) || previous.equals("let") || previous.equals("var")) {
            return true;
        }
        Token type = tokens.get(index - 1);
        if (type.type == SlangLexer.BUILTIN_TYPE || previous.equals("void")) {
            return true;
        }
        if (type.identifier()) {
            // Exclude language keywords not classified by older lexer keyword tables.
            return !Set.of("return", "throw", "case", "import", "module", "where", "new",
                    "else", "yield", "discard", "break", "continue").contains(previous);
        }
        if (previous.equals(">")) {
            int depth = 1;
            for (int i = index - 2; i >= 0; i--) {
                if (word(i).equals(">")) {
                    depth++;
                } else if (word(i).equals("<") && --depth == 0) {
                    return i > 0 && knownGenericType(word(i - 1));
                }
                if (word(i).equals(";") || word(i).equals("{") || word(i).equals("}")) {
                    break;
                }
            }
        }
        return false;
    }

    private boolean knownGenericType(String name) {
        if (BUILTIN_GENERICS.contains(name)) {
            return true;
        }
        for (int i = 1; i < tokens.size(); i++) {
            if (word(i).equals(name) && word(i + 1).equals("<")
                    && Set.of("struct", "class", "interface", "typealias").contains(word(i - 1))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasGenericModifier(int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (word(i).equals("__generic")) {
                return true;
            }
            if (word(i).equals(";") || word(i).equals("{") || word(i).equals("}")) {
                break;
            }
        }
        return false;
    }

    private List<Integer> enclosingScopes(int index) {
        var scopes = new ArrayList<Integer>();
        int scope = enclosingScope(index);
        while (scope >= 0) {
            scopes.addFirst(scope);
            scope = enclosingScope(scope);
        }
        return scopes;
    }

    private int enclosingScope(int index) {
        return index >= 0 && index < parents.size() ? parents.get(index) : -1;
    }

    int closingIndex(int opening) {
        int depth = 1;
        int parentheses = 0;
        int brackets = 0;
        for (int i = opening + 1; i < tokens.size(); i++) {
            ProgressManager.checkCanceled();
            String word = word(i);
            if (word.equals("(")) {
                parentheses++;
            } else if (word.equals(")")) {
                if (--parentheses < 0) {
                    return -1;
                }
            } else if (word.equals("[")) {
                brackets++;
            } else if (word.equals("]")) {
                if (--brackets < 0) {
                    return -1;
                }
            } else if (parentheses == 0 && brackets == 0) {
                if (word.equals("<") && isOpening(i)) {
                    depth++;
                } else if (word.equals(">") && --depth == 0) {
                    return i;
                } else if (word.equals(";") || word.equals("{") || word.equals("}")) {
                    return -1;
                }
            }
        }
        return -1;
    }

    boolean isClosing(int index) {
        if (index < 0 || !word(index).equals(">")) {
            return false;
        }
        for (int i = index - 1; i >= 0; i--) {
            if (word(i).equals("<") && isOpening(i) && closingIndex(i) == index) {
                return true;
            }
            if (word(i).equals(";") || word(i).equals("{") || word(i).equals("}")) {
                break;
            }
        }
        return false;
    }

    boolean needsClosing(int opening) {
        if (closingIndex(opening) < 0) {
            return true;
        }
        // A closer belonging to an outer generic must not be stolen by a newly typed inner one.
        for (int i = opening - 1; i >= 0; i--) {
            if (word(i).equals("<") && isOpening(i) && closingIndex(i) < 0) {
                return true;
            }
            if (word(i).equals(";") || word(i).equals("{") || word(i).equals("}")) {
                break;
            }
        }
        return false;
    }

    private String word(int index) {
        return index >= 0 && index < tokens.size() ? tokens.get(index).text : "";
    }
}
