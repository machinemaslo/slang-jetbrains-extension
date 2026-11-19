package slanglsp.editor.inspections.naming

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection

class InconsistentNamingInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
    override fun getShortName(): String {
        return ID
    }

    companion object {
        const val ID: String = "SlangInconsistentNaming"
    }
}
