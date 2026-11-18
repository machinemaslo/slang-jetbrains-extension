package slanglsp.editor;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection;
import org.jetbrains.annotations.NotNull;

/** Runs through Inspect Code and the IDE's Analyze Code commit check. */
public final class BatchDiagnosticsInspection extends LocalInspectionTool implements ExternalAnnotatorBatchInspection {
    public static final String ID = "SlangBatchDiagnostics";

    @Override
    public @NotNull String getShortName() {
        return ID;
    }
}
