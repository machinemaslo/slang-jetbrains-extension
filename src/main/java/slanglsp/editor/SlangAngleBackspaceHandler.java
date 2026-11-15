package slanglsp.editor;

import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import slanglsp.language.SlangLanguage;

public final class SlangAngleBackspaceHandler extends BackspaceHandlerDelegate {
    private static final Key<Boolean> DELETE_CLOSER = Key.create("slang.angles.deleteCloser");

    @Override
    public void beforeCharDeleted(char c, @NotNull PsiFile file, @NotNull Editor editor) {
        editor.putUserData(DELETE_CLOSER, c == '<' && file.getLanguage().isKindOf(SlangLanguage.INSTANCE)
                && AngleBrackets.emptyPair(editor));
    }

    @Override
    public boolean charDeleted(char c, @NotNull PsiFile file, @NotNull Editor editor) {
        boolean paired = Boolean.TRUE.equals(editor.getUserData(DELETE_CLOSER));
        editor.putUserData(DELETE_CLOSER, null);
        if (paired) {
            int offset = editor.getCaretModel().getOffset();
            var document = editor.getDocument();
            if (offset < document.getTextLength() && document.getCharsSequence().charAt(offset) == '>') {
                document.deleteString(offset, offset + 1);
                return true;
            }
        }
        return false;
    }
}
