package slanglsp.editor;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection;
import org.jetbrains.annotations.NotNull;

public final class UnusedSymbolsInspection extends LocalInspectionTool implements ExternalAnnotatorBatchInspection {
    public static final String ID = "SlangUnusedSymbols";

    @Override
    public @NotNull String getShortName() {
        return ID;
    }
}
