package slanglsp.editor.spellchecking

import com.intellij.spellchecker.BundledDictionaryProvider

class BundledDictionary : BundledDictionaryProvider {
    override fun getBundledDictionaries(): Array<String> {
        return arrayOf("/dictionaries/slang.dic")
    }
}
