package slanglsp.language

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader.getIcon
import javax.swing.Icon
import slanglsp.SlangBundle

object SlangFileType : LanguageFileType(SlangLanguage.INSTANCE) {
    override fun getName(): String {
        return "Slang"
    }

    override fun getDescription(): String {
        return SlangBundle.message("file.description")
    }

    override fun getDefaultExtension(): String {
        return "slang"
    }

    override fun getIcon(): Icon {
        return getIcon("/icons/slang.svg", SlangFileType::class.java)
    }
}
