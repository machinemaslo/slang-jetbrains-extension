package slanglsp.language

import com.intellij.lang.Language

class SlangLanguage private constructor() : Language("Slang") {
    companion object {
        @JvmField val INSTANCE = SlangLanguage()
    }
}
