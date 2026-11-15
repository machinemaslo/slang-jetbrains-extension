package slanglsp.editor;

import com.intellij.codeInspection.LocalInspectionTool;

/** Profile entry paired with the background formatter annotator. */
public final class SlangFormattingInspection extends LocalInspectionTool
        implements com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection {
    public static final String SHORT_NAME = "SlangFormatting";
}
