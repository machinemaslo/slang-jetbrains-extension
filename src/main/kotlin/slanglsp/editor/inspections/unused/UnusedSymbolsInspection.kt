package slanglsp.editor.inspections.unused

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection

class UnusedSymbolsInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
    override fun getShortName(): String {
        return ID
    }

    companion object {
        const val ID: String = "SlangUnusedSymbols"
    }
}
