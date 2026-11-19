package slanglsp.editor.typing

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService.Companion.isDumb
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.Key
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import slanglsp.analysis.AnalysisTiming
import slanglsp.language.generics.GenericContext
import slanglsp.language.generics.genericSymbolKey

/** Indexed, local declaration lookup: typing never waits for a language-server request. */
internal object GenericResolver {
    private val PARSED = Key.create<Parsed>("slang.generics.parsed")

    fun context(editor: Editor): GenericContext {
        return withIndex(editor, parsed(editor.document))
    }

    @JvmStatic
    fun parsed(document: Document): GenericContext {
        val stamp = document.modificationStamp
        document.getUserData(PARSED)?.let { cached ->
            if (cached.stamp == stamp) {
                return cached.context
            }
        }
        val parsed =
            AnalysisTiming.start("generic parse", "").use {
                GenericContext(document.immutableCharSequence)
            }
        if (document.modificationStamp == stamp) {
            document.putUserData(PARSED, Parsed(stamp, parsed))
        }
        return parsed
    }

    fun context(editor: Editor, text: CharSequence): GenericContext {
        // Surround-selection uses speculative text, not the current document.
        return withIndex(editor, GenericContext(text))
    }

    private fun withIndex(editor: Editor, parsed: GenericContext): GenericContext {
        val project = editor.project ?: return parsed
        val imports = parsed.imports()
        if (imports.isEmpty() || isDumb(project)) {
            return parsed
        }
        val resolved = mutableMapOf<String, Boolean>()
        return parsed.withExternalLookup { name, owner ->
            resolved.getOrPut("$name:$owner") {
                try {
                    val symbol = genericSymbolKey(owner, name)
                    val index = FileBasedIndex.getInstance()
                    val scope = GlobalSearchScope.allScope(project)
                    imports.any { module ->
                        index
                            .getContainingFiles(
                                GenericDeclarations.NAME, GenericDeclarations.key(module, symbol), scope)
                            .isNotEmpty()
                    }
                } catch (_: IndexNotReadyException) {
                    false
                }
            }
        }
    }

    @JvmRecord private data class Parsed(val stamp: Long, val context: GenericContext)
}
