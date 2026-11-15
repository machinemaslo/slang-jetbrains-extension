package slanglsp.editor;

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler;
import slanglsp.language.SlangLexer;

public final class SlangQuoteHandler extends SimpleTokenSetQuoteHandler {
    public SlangQuoteHandler() {
        super(SlangLexer.STRING);
    }
}
