package slanglsp.editor;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection;

public final class InconsistentNamingInspection extends LocalInspectionTool implements ExternalAnnotatorBatchInspection {
    public static final String ID = "SlangInconsistentNaming";

    @Override
    public @org.jetbrains.annotations.NotNull String getShortName() {
        return ID;
    }
}
