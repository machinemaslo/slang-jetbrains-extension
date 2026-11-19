package slanglsp.editor.typing

import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import slanglsp.language.SlangFileType
import slanglsp.language.generics.GenericContext

/** Exact module/owner/name lookup; typing never parses other project documents. */
class GenericDeclarationsIndex : ScalarIndexExtension<String?>() {
    override fun getName(): ID<String?, Void?> {
        return GenericDeclarations.NAME
    }

    override fun getIndexer(): DataIndexer<String?, Void?, FileContent?> {
        return DataIndexer { input: FileContent? ->
            val context = GenericContext(input!!.contentAsText)
            val declarations = context.genericDeclarations()
            val result = HashMap<String?, Void?>()
            val filename = input.file.nameWithoutExtension
            for (symbol in declarations) {
                result[GenericDeclarations.key(filename, symbol!!)] = null
                if (context.moduleName() != null) {
                    result[GenericDeclarations.key(context.moduleName(), symbol)] = null
                }
            }
            result
        }
    }

    override fun getKeyDescriptor(): KeyDescriptor<String?> {
        return EnumeratorStringDescriptor.INSTANCE
    }

    override fun getInputFilter(): FileBasedIndex.InputFilter {
        return DefaultFileTypeSpecificInputFilter(SlangFileType)
    }

    override fun getVersion(): Int {
        return 1
    }

    override fun dependsOnFileContent(): Boolean {
        return true
    }
}

internal object GenericDeclarations {
    val NAME: ID<String?, Void?> = ID.create("slang.generic.declarations")

    fun key(module: String?, symbol: String): String {
        return "$module:$symbol"
    }
}
