package slanglsp.editor.navigation.cpp

import com.google.gson.JsonParser
import com.intellij.openapi.progress.ProgressManager
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** A compilation database can describe shader generation as well as C++ compilation. */
internal object ReflectionInvocationDiscovery {
    data class Entry(val manifest: Path, val directory: Path, val arguments: List<String>?)

    fun databaseTargets(manifest: Path, shader: Path): List<ReflectionProbeRunner.Target> {
        if (!Files.isRegularFile(manifest) || Files.size(manifest) > 8 * 1024 * 1024) return emptyList()
        val json = Files.newBufferedReader(manifest).use { JsonParser.parseReader(it) }
        if (!json.isJsonArray) return emptyList()
        return buildList {
            for (element in json.asJsonArray) {
                ProgressManager.checkCanceled()
                if (!element.isJsonObject) continue
                val entry = element.asJsonObject
                fun string(key: String): String? = entry[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                try {
                    val directory = manifest.parent.resolve(string("directory") ?: continue).normalize()
                    if (directory.resolve(string("file") ?: continue).normalize() != shader) continue
                    val arguments = entry["arguments"]?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }
                    val command = arguments?.joinToString(" ") { if (' ' in it) "\"$it\"" else it } ?: string("command") ?: continue
                    // Without "output" a named output flag must be present; a C++ TU entry is never eligible.
                    add(ReflectionProbeRunner.Target(string("output") ?: "", command, Entry(manifest, directory, arguments)))
                    if (size >= 24) break
                } catch (_: InvalidPathException) {
                    continue
                }
            }
        }
    }
}
