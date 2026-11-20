package slanglsp.language.generics

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import slanglsp.language.SlangLexer

internal fun genericSymbolKey(owner: String?, name: String?): String = "${owner.orEmpty()}:$name"

/**
 * Recognizes Slang generic syntax without treating every less-than operator as a bracket. Names come from declarations,
 * not the lexer's capitalization-based highlighting.
 */
internal class GenericContext {
    @JvmRecord
    internal data class Token(val text: String, val type: IElementType?, val start: Int, val end: Int) {
        fun identifier(): Boolean {
            return type === SlangLexer.TYPE || type === SlangLexer.VARIABLE || type === SlangLexer.FUNCTION
        }
    }

    private val tokens: MutableList<Token>
    private val parents: MutableList<Int>
    private val typeScopes: MutableMap<String?, Int?>
    private val imports: Set<String>
    private val externalGeneric: (name: String, owner: String?) -> Boolean
    private val source: CharSequence

    constructor(text: CharSequence, externalGeneric: (name: String, owner: String?) -> Boolean = { _, _ -> false }) {
        this.externalGeneric = externalGeneric
        tokens = ArrayList()
        parents = ArrayList()
        typeScopes = HashMap()
        source = text
        val lexer = SlangLexer()
        lexer.start(text)
        while (lexer.tokenType != null) {
            ProgressManager.checkCanceled()
            val type = lexer.tokenType
            if (type !== TokenType.WHITE_SPACE && type !== SlangLexer.COMMENT && type !== SlangLexer.BLOCK_COMMENT) {
                tokens.add(
                    Token(
                        text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString(),
                        type,
                        lexer.tokenStart,
                        lexer.tokenEnd))
            }
            lexer.advance()
        }
        val scopes = mutableListOf<Int>()
        val imported = mutableSetOf<String>()
        for (i in tokens.indices) {
            if (word(i) == "import" && i + 1 < tokens.size) {
                imported.add(word(i + 1))
            }
            parents.add(scopes.lastOrNull() ?: -1)
            when (word(i)) {
                "{" -> scopes.add(i)
                "}" -> if (scopes.isNotEmpty()) scopes.removeLast()
                "struct",
                "class",
                "interface",
                "namespace" -> {
                    for (j in i + 2..<tokens.size) {
                        when (word(j)) {
                            ";",
                            "}" -> break
                            "{" -> {
                                typeScopes[word(i + 1)] = j
                                break
                            }
                        }
                    }
                }
            }
        }
        imports = imported.toSet()
        // Parameter names belong to their function body, not the surrounding namespace.
        for (i in 1..<tokens.size) {
            if (word(i) != "(") {
                continue
            }
            if (!isDeclarationName(nameBeforeParameters(i))) {
                continue
            }
            val close = afterParameterList(i)
            val body = (close..<tokens.size).firstOrNull { word(it) in PARAMETER_LIST_END } ?: tokens.size
            val scope = if (word(body) == "{") body else i
            for (parameter in i + 1..<close - 1) {
                parents[parameter] = scope
            }
        }
    }

    private fun nameBeforeParameters(open: Int): Int {
        val previous = open - 1
        if (word(previous) != ">") {
            return previous
        }
        var depth = 1
        for (index in previous - 1 downTo 0) {
            depth =
                when (word(index)) {
                    ">" -> depth + 1
                    "<" -> depth - 1
                    else -> depth
                }
            if (depth == 0) {
                return index - 1
            }
        }
        return -1
    }

    private fun afterParameterList(open: Int): Int {
        var depth = 1
        for (index in open + 1..<tokens.size) {
            depth =
                when (word(index)) {
                    "(" -> depth + 1
                    ")" -> depth - 1
                    else -> depth
                }
            if (depth == 0) {
                return index + 1
            }
        }
        return tokens.size
    }

    fun withExternalLookup(lookup: (name: String, owner: String?) -> Boolean): GenericContext {
        return GenericContext(this, lookup)
    }

    private constructor(parsed: GenericContext, lookup: (name: String, owner: String?) -> Boolean) {
        tokens = parsed.tokens
        parents = parsed.parents
        typeScopes = parsed.typeScopes
        imports = parsed.imports
        source = parsed.source
        externalGeneric = lookup
    }

    fun tokenAt(offset: Int): Int {
        var low = 0
        var high = tokens.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val token = tokens[middle]
            when {
                offset < token.start -> high = middle - 1
                offset >= token.end -> low = middle + 1
                else -> return middle
            }
        }
        return -1
    }

    fun isOpening(index: Int): Boolean {
        if (index < 1 || word(index) != "<" || tokens[index].type !== SlangLexer.OPERATOR) {
            return false
        }
        var lineStart = tokens[index].start
        while (lineStart > 0 && source[lineStart - 1] != '\n') {
            lineStart--
        }
        var first = lineStart
        while (first < source.length && (source[first] == ' ' || source[first] == '\t')) {
            first++
        }
        if (first < source.length && source[first] == '#') {
            return false
        }
        val name = tokens[index - 1]
        if (GENERIC_INTRODUCERS.contains(name.text) || name.text == "reinterpret") {
            return true
        }
        if (!name.identifier()) {
            return false
        }
        if (isDeclarationName(index - 1) &&
            !mutableSetOf<String?>("let", "var", "each", "typename").contains(word(index - 2))) {
            return true
        }

        // A value declaration shadows a generic name (including a built-in type).
        // Ignore declarations in completed sibling blocks.
        var declared: Boolean? = null
        var declarationDepth = -1
        val scopes = enclosingScopes(index)
        val qualified = word(index - 2) == "." || word(index - 2) == ":"
        val owner = if (qualified) memberOwner(index - 1) else null
        if (qualified && owner.isNullOrEmpty()) {
            return false
        }
        val memberScope = typeScopes[owner]
        for (i in tokens.indices) {
            ProgressManager.checkCanceled()
            if ((word(i) != name.text) || i == index - 1 || !isDeclarationName(i)) {
                continue
            }
            val scope = enclosingScope(i)
            if (if (qualified) {
                memberScope == null || scope != memberScope
            } else {
                scope != -1 && !scopes.contains(scope)
            }) {
                continue
            }
            val depth = if (scope == -1) 0 else scopes.indexOf(scope) + 1
            val generic = word(i + 1) == "<" || hasGenericModifier(i)
            val typeOrFunction = NAMED_DECLARATIONS.contains(word(i - 1)) || word(i + 1) == "(" || generic
            if (!typeOrFunction && i > index) {
                continue
            }
            if (depth >= declarationDepth) {
                if (depth == declarationDepth && declared == true && typeOrFunction) {
                    continue
                }
                declared = generic
                declarationDepth = depth
            }
        }
        return declared ?: ((!qualified && name.text in BUILTIN_GENERICS) || externalGeneric(name.text, owner))
    }

    private fun memberOwner(nameIndex: Int): String? {
        var receiver = nameIndex - 2
        if (word(nameIndex - 1) == ":" && word(nameIndex - 2) == ":") {
            receiver--
        } else if (word(nameIndex - 1) != ".") {
            return null
        }
        if (receiver < 0) {
            return null
        }
        val name = word(receiver)
        if (name == "this") {
            val scopes = enclosingScopes(nameIndex)
            for (i in scopes.indices.reversed()) {
                for ((owner, scope) in typeScopes) {
                    if (scope == scopes[i]) {
                        return owner
                    }
                }
            }
            return ""
        }
        if (typeScopes.containsKey(name)) {
            return name
        }
        // Resolve a directly declared receiver, including generic receiver types.
        val scopes = enclosingScopes(nameIndex)
        for (i in receiver - 1 downTo 0) {
            if (word(i) == name &&
                isDeclarationName(i) &&
                (enclosingScope(i) == -1 || scopes.contains(enclosingScope(i)))) {
                var type = i - 1
                if (word(type) == ">") {
                    var depth = 1
                    while (--type >= 0 && depth > 0) {
                        if (word(type) == ">") {
                            depth++
                        } else if (word(type) == "<") {
                            depth--
                        }
                    }
                }
                if (word(type) == "let" || word(type) == "var") {
                    return if (word(i + 1) == ":") word(i + 2) else ""
                }
                return word(type)
            }
        }
        return name
    }

    fun genericDeclarations(): MutableSet<String?> {
        val owners = HashMap<Int?, String?>()
        typeScopes.forEach { (name, scope) -> owners[scope] = name }
        val result = HashSet<String?>()
        for (i in 1..<tokens.size) {
            if (isDeclarationName(i) && (word(i + 1) == "<" || hasGenericModifier(i))) {
                val scope = enclosingScope(i)
                if (scope == -1 || owners.containsKey(scope)) {
                    result.add(genericSymbolKey(owners[scope], word(i)))
                }
            }
        }
        return result
    }

    fun imports(): Set<String> {
        return imports
    }

    fun moduleName(): String? {
        var i = 0
        while (i + 1 < tokens.size) {
            if (word(i) == "module") {
                return word(i + 1)
            }
            i++
        }
        return null
    }

    private fun isDeclarationName(index: Int): Boolean {
        if (index < 1 || !tokens[index].identifier()) {
            return false
        }
        val previous = word(index - 1)
        if (NAMED_DECLARATIONS.contains(previous) || previous == "let" || previous == "var") {
            return true
        }
        val type = tokens[index - 1]
        if (type.type === SlangLexer.BUILTIN_TYPE || previous == "void") {
            return true
        }
        if (type.identifier()) {
            // Exclude language keywords not classified by older lexer keyword tables.
            return !mutableSetOf<String?>(
                    "return",
                    "throw",
                    "case",
                    "import",
                    "module",
                    "where",
                    "new",
                    "else",
                    "yield",
                    "discard",
                    "break",
                    "continue")
                .contains(previous)
        }
        if (previous == ">") {
            var depth = 1
            for (i in index - 2 downTo 0) {
                if (word(i) == ">") {
                    depth++
                } else if (word(i) == "<" && --depth == 0) {
                    return i > 0 && knownGenericType(word(i - 1))
                }
                if (word(i) == ";" || word(i) == "{" || word(i) == "}") {
                    break
                }
            }
        }
        return false
    }

    private fun knownGenericType(name: String?): Boolean {
        if (BUILTIN_GENERICS.contains(name)) {
            return true
        }
        for (i in 1..<tokens.size) {
            if (word(i) == name &&
                word(i + 1) == "<" &&
                mutableSetOf<String?>("struct", "class", "interface", "typealias").contains(word(i - 1))) {
                return true
            }
        }
        return false
    }

    private fun hasGenericModifier(index: Int): Boolean {
        for (i in index - 1 downTo 0) {
            if (word(i) == "__generic") {
                return true
            }
            if (word(i) == ";" || word(i) == "{" || word(i) == "}") {
                break
            }
        }
        return false
    }

    private fun enclosingScopes(index: Int): MutableList<Int?> {
        val scopes = ArrayList<Int?>()
        var scope = enclosingScope(index)
        while (scope >= 0) {
            scopes.addFirst(scope)
            scope = enclosingScope(scope)
        }
        return scopes
    }

    private fun enclosingScope(index: Int): Int {
        return parents.getOrElse(index) { -1 }
    }

    fun closingIndex(opening: Int): Int {
        var depth = 1
        var parentheses = 0
        var brackets = 0
        for (i in opening + 1..<tokens.size) {
            ProgressManager.checkCanceled()
            val word = word(i)
            if (word == "(") {
                parentheses++
            } else if (word == ")") {
                if (--parentheses < 0) {
                    return -1
                }
            } else if (word == "[") {
                brackets++
            } else if (word == "]") {
                if (--brackets < 0) {
                    return -1
                }
            } else if (parentheses == 0 && brackets == 0) {
                when (word) {
                    "<" if isOpening(i) -> {
                        depth++
                    }
                    ">" if --depth == 0 -> {
                        return i
                    }
                    ";",
                    "{",
                    "}" -> {
                        return -1
                    }
                }
            }
        }
        return -1
    }

    fun isClosing(index: Int): Boolean {
        if (index < 0 || word(index) != ">") {
            return false
        }
        for (i in index - 1 downTo 0) {
            if (word(i) == "<" && isOpening(i) && closingIndex(i) == index) {
                return true
            }
            if (word(i) == ";" || word(i) == "{" || word(i) == "}") {
                break
            }
        }
        return false
    }

    fun needsClosing(opening: Int): Boolean {
        if (closingIndex(opening) < 0) {
            return true
        }
        // A closer belonging to an outer generic must not be stolen by a newly typed inner one.
        for (i in opening - 1 downTo 0) {
            if (word(i) == "<" && isOpening(i) && closingIndex(i) < 0) {
                return true
            }
            if (word(i) == ";" || word(i) == "{" || word(i) == "}") {
                break
            }
        }
        return false
    }

    private fun word(index: Int): String {
        return tokens.getOrNull(index)?.text.orEmpty()
    }

    companion object {
        private val PARAMETER_LIST_END = setOf("{", "}", ";")
        private val GENERIC_INTRODUCERS = mutableSetOf<String?>("__generic", "extension", "__init", "__subscript")
        private val NAMED_DECLARATIONS =
            mutableSetOf<String?>("struct", "interface", "class", "enum", "typealias", "func")
        private val BUILTIN_GENERICS =
            mutableSetOf<String?>(
                "vector",
                "matrix",
                "Ptr",
                "Optional",
                "Tuple",
                "IFunc",
                "bit_cast",
                "DifferentialPair",
                "DifferentialPtrPair",
                "CoopVec",
                "Buffer",
                "RWBuffer",
                "RasterizerOrderedBuffer",
                "StructuredBuffer",
                "RWStructuredBuffer",
                "RasterizerOrderedStructuredBuffer",
                "AppendStructuredBuffer",
                "ConsumeStructuredBuffer",
                "ConstantBuffer",
                "ParameterBlock",
                "Texture1D",
                "Texture1DArray",
                "Texture2D",
                "Texture2DArray",
                "Texture2DMS",
                "Texture2DMSArray",
                "Texture3D",
                "TextureCube",
                "TextureCubeArray",
                "RWTexture1D",
                "RWTexture1DArray",
                "RWTexture2D",
                "RWTexture2DArray",
                "RWTexture3D",
                "RasterizerOrderedTexture1D",
                "RasterizerOrderedTexture1DArray",
                "RasterizerOrderedTexture2D",
                "RasterizerOrderedTexture2DArray",
                "RasterizerOrderedTexture3D",
                "InputPatch",
                "OutputPatch",
                "PointStream",
                "LineStream",
                "TriangleStream")
    }
}
