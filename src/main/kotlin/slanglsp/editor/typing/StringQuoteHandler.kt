package slanglsp.editor.typing

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import slanglsp.language.SlangLexer

class StringQuoteHandler : SimpleTokenSetQuoteHandler(SlangLexer.STRING)
