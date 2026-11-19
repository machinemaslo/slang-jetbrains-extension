package slanglsp.editor.inspections.diagnostics

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection

/** Runs through Inspect Code and the IDE's Analyze Code commit check. */
class BatchDiagnosticsInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
    override fun getShortName(): String {
        return ID
    }

    companion object {
        const val ID: String = "SlangBatchDiagnostics"
    }
}
