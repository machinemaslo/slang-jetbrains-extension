package slanglsp.discovery.buildsystems

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import org.w3c.dom.Element
import org.w3c.dom.NodeList

internal fun JsonElement?.literal(): String? = (this as? JsonPrimitive)?.asString

internal fun JsonElement?.items(): List<JsonElement> =
    when (this) {
        is JsonArray -> toList()
        is JsonPrimitive -> listOf(this)
        else -> emptyList()
    }

internal fun NodeList.elements(): Sequence<Element> =
    (0 until length).asSequence().map(::item).filterIsInstance<Element>()

internal fun Element.childElements(): Sequence<Element> =
    generateSequence(firstChild) { it.nextSibling }.filterIsInstance<Element>()
