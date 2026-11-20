package slanglsp.editor.navigation.cpp

import com.intellij.openapi.progress.ProgressManager

/** Recognizes explicit layout checks, not general C++ data flow or generator behavior. */
internal object ReflectionSource {
    data class TypeReference(val name: String, val offset: Int, val typeOnly: Boolean = false)
    data class Site(val offset: Int, val namesType: Boolean)
    data class Evidence(val types: List<TypeReference>, val site: Site?)

    val lookupCalls = listOf("findTypeByName", "spReflection_FindTypeByName")
    val layoutCalls = listOf(
        "getTypeLayout", "spReflection_GetTypeLayout", "getFieldByIndex", "spReflectionTypeLayout_GetFieldByIndex",
    )
    val programCalls = listOf("getGlobalParamsVarLayout", "spGetReflection", "getLayout")

    fun inspect(source: CharSequence, slangName: String): Evidence {
        if ((!source.contains("slang") && !source.contains("spReflection") && !source.contains("spGetReflection")) ||
            reflectionCalls.none { source.contains(it) }) {
            return Evidence(emptyList(), null)
        }
        val tokens = sourceTokens(source)

        if (!usesSlang(tokens)) {
            return Evidence(emptyList(), null)
        }

        var depth = 0
        var start = 0
        var site: Site? = null
        val types = linkedSetOf<TypeReference>()
        val scopes = mutableListOf(mutableMapOf<String, LayoutValue>())

        for ((i, token) in tokens.withIndex()) {
            ProgressManager.checkCanceled()
            val text = token.text
            if (tokens.getOrNull(i + 1)?.text == "(") {
                val namedLookup =
                    (text == lookupCalls[0] && tokens.getOrNull(i + 2)?.text == "\"$slangName\"") ||
                        (text == lookupCalls[1] && tokens.getOrNull(i + 4)?.text == "\"$slangName\"")
                if (namedLookup && site?.namesType != true) {
                    site = Site(token.offset, true)
                } else if (site == null && text in reflectionCalls) {
                    site = Site(token.offset, false)
                }
            }

            when (text) {
                "(" -> depth++
                ")" -> depth = (depth - 1).coerceAtLeast(0)
            }

            if (text.startsWith("#")) {
                start = i + 1
                if (!includeDirective.matches(text)) {
                    // Never carry a binding through a macro definition or a preprocessor branch.
                    scopes.forEach { it.clear() }
                }
            } else if (depth == 0 && text in statementEnds) {
                val statement = tokens.subList(start, i)
                val bindings = scopes.last()
                if (statement.size <= MAX_STATEMENT_TOKENS) {
                    for ((index, operator) in statement.withIndex()) {
                        if (operator.text == "==" || operator.text == "!=") {
                            val left = operand(statement, index, -1)
                            val right = operand(statement, index, 1)
                            match(left, right, slangName, bindings)?.let(types::add)
                            match(right, left, slangName, bindings)?.let(types::add)
                        }
                    }
                    if (text == ";") {
                        updateBinding(statement, slangName, scopes)
                    }
                }
                start = i + 1
                if (text == "{") {
                    if (scopes.size >= MAX_SCOPES) {
                        return Evidence(types.toList(), site)
                    }
                    scopes.add(bindings.toMutableMap())
                } else if (text == "}" && scopes.size > 1) {
                    scopes.removeLast()
                }
            }
        }

        return Evidence(types.toList(), site)
    }

    private fun usesSlang(tokens: List<SourceToken>): Boolean =
        tokens.indices.any { i ->
            val text = tokens[i].text
            includeDirective.matches(text) ||
                text in legacyReflectionCalls ||
                (text == "slang" && tokens.getOrNull(i + 1)?.text == "::" &&
                    tokens.getOrNull(i + 2)?.text in slangTypes)
        }

    private fun operand(tokens: List<SourceToken>, comparison: Int, direction: Int): List<SourceToken> {
        var i = comparison + direction
        var depth = 0

        while (i in tokens.indices) {
            val text = tokens[i].text
            if (text == if (direction > 0) "(" else ")") {
                depth++
            } else if (text == if (direction > 0) ")" else "(") {
                if (depth == 0) {
                    break
                }
                depth--
            } else if (depth == 0 && text in operandEnds) {
                break
            }
            i += direction
        }

        return if (direction > 0) tokens.subList(comparison + 1, i) else tokens.subList(i + 1, comparison)
    }

    private fun match(
        reflected: List<SourceToken>,
        host: List<SourceToken>,
        slangName: String,
        bindings: Map<String, LayoutValue>,
    ): TypeReference? {
        val value = evaluate(reflected, slangName, bindings) ?: return null
        val call = call(unwrapped(host)) ?: return null
        if (call.prefix.size != 1) {
            return null
        }

        val expected = when (call.name) {
            "sizeof" -> LayoutValue.SIZE
            "alignof" -> LayoutValue.ALIGNMENT
            "offsetof" -> LayoutValue.OFFSET
            else -> return null
        }
        if (value != expected || call.args.size != (if (expected == LayoutValue.OFFSET) 2 else 1)) {
            return null
        }

        val argument = call.args.first()
        val type = argument.dropWhile { it.text in typeModifiers }
        val segments = if (type.firstOrNull()?.text == "::") type.drop(1) else type
        if (segments.size % 2 != 1 ||
            segments.withIndex().any { (i, token) ->
                if (i % 2 == 0) !identifier.matches(token.text) else token.text != "::"
            }) {
            return null
        }
        // Unlike alignof/offsetof, sizeof also accepts an expression such as sizeof(variable).
        val typeOnly = call.name != "sizeof" || argument.take(argument.size - type.size).any { it.text in tagKeywords }
        return TypeReference(type.joinToString("") { it.text }, type.last().offset, typeOnly)
    }

    private fun updateBinding(
        statement: List<SourceToken>,
        slangName: String,
        scopes: List<MutableMap<String, LayoutValue>>,
    ) {
        val equals = statement.indexOfFirst { it.text == "=" }
        if (equals <= 0) {
            return
        }
        val left = statement.subList(0, equals)
        val name = left.last().text
        if (!identifier.matches(name)) {
            return
        }
        if (left.any { !identifier.matches(it.text) && it.text !in typePunctuation }) {
            // Do not infer a value from a complex assignment (e.g. an unbraced `if`).
            scopes.forEach { it.remove(name) }
            return
        }

        val bindings = scopes.last()
        val value = evaluate(statement.subList(equals + 1, statement.size), slangName, bindings)
        if (left.size == 1) {
            // An assignment in a nested block can also change its enclosing binding.
            scopes.forEach { it.remove(name) }
        } else {
            bindings.remove(name)
        }
        if (value != null && bindings.size < MAX_BINDINGS) {
            bindings[name] = value
        }
    }

    private fun evaluate(tokens: List<SourceToken>, slangName: String, bindings: Map<String, LayoutValue>): LayoutValue? {
        val expression = unwrapped(tokens)
        if (expression.size == 1) {
            val text = expression.single().text
            return if (text == "\"$slangName\"") LayoutValue.NAME else bindings[text]
        }
        val call = call(expression) ?: return null
        val isMember = call.prefix.size >= 3 && call.prefix[call.prefix.lastIndex - 1].text in memberOperators
        val isFree = call.prefix.size == 1 || (call.prefix.size == 2 && call.prefix.first().text == "::")
        val receiver = if (isMember) call.prefix.dropLast(2) else emptyList()
        fun argument(index: Int): LayoutValue? = call.args.getOrNull(index)?.let { evaluate(it, slangName, bindings) }
        fun member(value: LayoutValue): Boolean = isMember && evaluate(receiver, slangName, bindings) == value

        return when (call.name) {
            "findTypeByName" -> LayoutValue.TYPE.takeIf { isMember && call.args.size == 1 && argument(0) == LayoutValue.NAME }
            "spReflection_FindTypeByName" -> LayoutValue.TYPE.takeIf { isFree && argument(1) == LayoutValue.NAME }
            "getTypeLayout" -> LayoutValue.LAYOUT.takeIf { isMember && argument(0) == LayoutValue.TYPE }
            "spReflection_GetTypeLayout" -> LayoutValue.LAYOUT.takeIf { isFree && argument(1) == LayoutValue.TYPE }
            "getSize", "getStride" -> LayoutValue.SIZE.takeIf { member(LayoutValue.LAYOUT) }
            "spReflectionTypeLayout_GetSize", "spReflectionTypeLayout_GetStride" ->
                LayoutValue.SIZE.takeIf { isFree && argument(0) == LayoutValue.LAYOUT }
            "getAlignment" -> LayoutValue.ALIGNMENT.takeIf { member(LayoutValue.LAYOUT) }
            "spReflectionTypeLayout_GetAlignment" ->
                LayoutValue.ALIGNMENT.takeIf { isFree && argument(0) == LayoutValue.LAYOUT }
            "getFieldByIndex" -> LayoutValue.FIELD.takeIf { member(LayoutValue.LAYOUT) }
            "spReflectionTypeLayout_GetFieldByIndex" ->
                LayoutValue.FIELD.takeIf { isFree && argument(0) == LayoutValue.LAYOUT }
            "getOffset" -> LayoutValue.OFFSET.takeIf { member(LayoutValue.FIELD) }
            "spReflectionVariableLayout_GetOffset" ->
                LayoutValue.OFFSET.takeIf { isFree && argument(0) == LayoutValue.FIELD }
            else -> null
        }
    }

    private fun unwrapped(tokens: List<SourceToken>): List<SourceToken> {
        var result = tokens
        while (result.firstOrNull()?.text == "(" && openingParen(result) == 0) {
            result = result.subList(1, result.lastIndex)
        }
        return result
    }

    private fun openingParen(tokens: List<SourceToken>): Int {
        if (tokens.lastOrNull()?.text != ")") {
            return -1
        }
        var depth = 0
        for (i in tokens.indices.reversed()) {
            when (tokens[i].text) {
                ")" -> depth++
                "(" -> if (--depth == 0) return i
            }
        }
        return -1
    }

    private fun call(tokens: List<SourceToken>): Call? {
        val open = openingParen(tokens)
        if (open <= 0 || !identifier.matches(tokens[open - 1].text)) {
            return null
        }
        val args = mutableListOf<List<SourceToken>>()
        var start = open + 1
        var depth = 0
        for (i in start until tokens.lastIndex) {
            when (tokens[i].text) {
                "(" -> depth++
                ")" -> depth--
                "," -> if (depth == 0) {
                    args.add(tokens.subList(start, i))
                    start = i + 1
                }
            }
        }
        if (start < tokens.lastIndex) {
            args.add(tokens.subList(start, tokens.lastIndex))
        }
        return Call(tokens.subList(0, open), args)
    }

    private data class Call(val prefix: List<SourceToken>, val args: List<List<SourceToken>>) {
        val name: String get() = prefix.last().text
    }

    private enum class LayoutValue { NAME, TYPE, LAYOUT, SIZE, ALIGNMENT, FIELD, OFFSET }

    private const val MAX_STATEMENT_TOKENS = 256
    private const val MAX_SCOPES = 128
    private const val MAX_BINDINGS = 128
    private val reflectionCalls = lookupCalls + layoutCalls + programCalls
    private val legacyReflectionCalls = reflectionCalls.filter { it.startsWith("sp") }.toSet()
    private val slangTypes = setOf(
        "ProgramLayout", "ShaderReflection", "TypeReflection", "TypeLayoutReflection", "VariableLayoutReflection",
        "IComponentType", "IModule",
    )
    private val statementEnds = setOf(";", "{", "}")
    private val memberOperators = setOf(".", "->")
    private val tagKeywords = setOf("struct", "class")
    private val typeModifiers = tagKeywords + setOf("const", "volatile")
    private val typePunctuation = setOf("::", "*", "&", "&&")
    private val operandEnds = setOf(",", "&&", "||", "=", "==", "!=", "return")
    private val identifier = Regex("[A-Za-z_][A-Za-z_0-9]*")
    private val includeDirective =
        Regex("""#[ \t]*(?:include|import)[ \t]*[<"](?:[^>"\n]*/)?slang(?:-reflection|-deprecated)?\.h[>"].*""")
}
