package slanglsp.editor.navigation.cpp

import com.intellij.openapi.progress.ProgressManager
import java.nio.file.Path

/** A candidate needs both layout witnesses, not just a changed file or a similar type name. */
internal object ReflectionProbeMatcher {
    data class Candidate(val file: Path, val offset: Int, val name: String)

    data class Struct(val name: String, val offset: Int, val fields: List<String>)

    /** Simple field declarators only. This is candidate evidence, not proof of reflection. */
    fun structs(source: String): List<Struct> = definitions(source).mapNotNull { definition ->
        val fields = definition.body.map { member ->
            val declarator = member.takeWhile { it !in fieldEnds }
            declarator.lastOrNull()?.takeIf { declarator.size >= 2 && identifier.matches(it) }
                ?: return@mapNotNull null
        }
        Struct(definition.name.text, definition.name.offset, fields)
    }

    enum class Shape(val first: Int, val second: Int) {
        // Both probes preserve the alignment of ordinary 16-byte padded layouts.
        ARRAY(44, 68),
        SCALARS(8, 12),
    }

    fun inject(source: String, declarationOffset: Int, shape: Shape, count: Int): String? {
        val declaration = definitions(source, fieldsOnly = false).singleOrNull { it.name.offset == declarationOffset }
            ?: return null
        val names = generateSequence(0) { it + 1 }.map { "slangReflectionProbe$it" }.filter { it !in source }
        val insertion = when (shape) {
            Shape.ARRAY -> "\n    public uint ${names.first()}[$count];\n"
            Shape.SCALARS -> names.take(count).joinToString(separator = "", prefix = "\n") { "    public float $it;\n" }
        }
        val offset = declaration.endOffset
        return source.substring(0, offset) + insertion + source.substring(offset)
    }

    fun match(
        baseline: Map<Path, String>,
        first: Map<Path, String>,
        second: Map<Path, String>,
        shape: Shape = Shape.ARRAY,
    ): Candidate? {
        val originals = baseline.flatMap { (path, text) ->
            definitions(text).map { definition ->
                definition.body to Candidate(path, definition.name.offset, definition.name.text)
            }
        }.groupBy({ it.first }, { it.second })
        val firstWitnesses = witnesses(first, shape, shape.first, originals.keys)
        val secondWitnesses = witnesses(second, shape, shape.second, originals.keys)
        val common = firstWitnesses.keys.intersect(secondWitnesses.keys)
        val witness = common.singleOrNull() ?: return null
        if (firstWitnesses[witness] != 1 || secondWitnesses[witness] != 1) {
            return null
        }
        return originals[witness.body]?.singleOrNull()
    }

    private data class Witness(val body: List<List<String>>, val insertion: Int)

    private fun witnesses(
        artifacts: Map<Path, String>,
        shape: Shape,
        count: Int,
        originals: Set<List<List<String>>>,
    ): Map<Witness, Int> {
        val definitions = artifacts.values.flatMap { definitions(it) }.map { it.body }
        val unchanged = definitions.filterTo(hashSetOf()) { it in originals }
        val memberCounts = originals.mapTo(hashSetOf()) { it.size }
        val width = if (shape == Shape.ARRAY) 1 else count
        val hits = mutableMapOf<Witness, Int>()
        for (body in definitions) {
            if (body in unchanged || body.size - width !in memberCounts) {
                continue
            }
            for (index in 0..body.size - width) {
                ProgressManager.checkCanceled()
                val carriesProbe = when (shape) {
                    Shape.ARRAY -> hasArrayLength(body[index], count)
                    Shape.SCALARS -> hasScalarRun(body.subList(index, index + count))
                }
                if (!carriesProbe) {
                    continue
                }
                val withoutProbe = body.subList(0, index) + body.subList(index + width, body.size)
                if (withoutProbe in originals && withoutProbe !in unchanged) {
                    val witness = Witness(withoutProbe, index)
                    hits.merge(witness, 1, Int::plus)
                }
            }
        }
        return hits
    }

    private fun hasScalarRun(fields: List<List<String>>): Boolean {
        fun shape(field: List<String>): List<String>? {
            val end = field.indexOfFirst { it == "=" || it == "{" }.takeIf { it >= 0 } ?: field.size
            if (end < 2 || !identifier.matches(field[end - 1]) || "[" in field.take(end)) {
                return null
            }
            return field.take(end - 1) + field.drop(end)
        }
        val first = shape(fields.first()) ?: return false
        return fields.drop(1).all { shape(it) == first }
    }

    private fun hasArrayLength(member: List<String>, length: Int): Boolean {
        val declarator = member.takeWhile { it != "=" && it != "{" }
        return declarator.indices.any { i ->
            val bound = declarator[i].trimEnd('u', 'U', 'l', 'L').toIntOrNull()
            if (bound != length) {
                false
            } else {
                val previous = declarator.getOrNull(i - 1)
                val next = declarator.getOrNull(i + 1)
                (previous == "[" && next == "]") ||
                    (previous == "," && next == ">" && "array" in declarator.take(i))
            }
        }
    }

    private data class Definition(val name: SourceToken, val endOffset: Int, val body: List<List<String>>)

    private fun definitions(source: String, fieldsOnly: Boolean = true): List<Definition> {
        if (!source.contains("struct") && !source.contains("class")) {
            return emptyList()
        }
        val tokens = sourceTokens(source)
        return tokens.indices.mapNotNull { index ->
            ProgressManager.checkCanceled()
            if (tokens[index].text == "struct" || tokens[index].text == "class") {
                definition(tokens, index + 1, fieldsOnly)
            } else {
                null
            }
        }
    }

    private fun definition(tokens: List<SourceToken>, afterKeyword: Int, fieldsOnly: Boolean): Definition? {
        val nameIndex = skipAlignment(tokens, afterKeyword) ?: return null
        val name = tokens.getOrNull(nameIndex)?.takeIf { identifier.matches(it.text) } ?: return null
        var opening = skipAlignment(tokens, nameIndex + 1) ?: return null
        // Do not interpret `struct Name variable {…}` or a forward declaration as a type definition.
        if (tokens.getOrNull(opening)?.text == "final") {
            opening++
        }
        if (tokens.getOrNull(opening)?.text == ":") {
            opening = (opening + 1 until minOf(tokens.size, opening + 48))
                .firstOrNull { tokens[it].text in headerEnds } ?: return null
        }
        if (tokens.getOrNull(opening)?.text != "{") {
            return null
        }

        val members = mutableListOf<List<String>>()
        var start = opening + 1
        var depth = 0
        for (index in start until minOf(tokens.size, start + MAX_BODY_TOKENS)) {
            when (tokens[index].text) {
                "(", "[", "{" -> depth++
                ")", "]", "}" -> {
                    if (depth == 0) {
                        return if (tokens[index].text == "}" && (!fieldsOnly || start == index)) {
                            Definition(name, tokens[index].offset, members)
                        } else {
                            null
                        }
                    }
                    depth--
                }
                ";" -> if (depth == 0) {
                    members.add(tokens.subList(start, index).map { it.text })
                    start = index + 1
                }
            }
        }
        return null
    }

    private fun skipAlignment(tokens: List<SourceToken>, begin: Int): Int? {
        if (tokens.getOrNull(begin)?.text != "alignas") {
            return begin
        }
        if (tokens.getOrNull(begin + 1)?.text != "(") {
            return null
        }
        var depth = 1
        for (i in begin + 2 until minOf(tokens.size, begin + 32)) {
            when (tokens[i].text) {
                "(" -> depth++
                ")" -> if (--depth == 0) return i + 1
            }
        }
        return null
    }

    private const val MAX_BODY_TOKENS = 8192
    private val identifier = Regex("[A-Za-z_][A-Za-z_0-9]*")
    private val fieldEnds = setOf("=", "{", "[", ":")
    private val headerEnds = setOf("{", ";", "(", "=")
}
