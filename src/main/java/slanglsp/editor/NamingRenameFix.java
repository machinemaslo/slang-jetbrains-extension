package slanglsp.editor;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import slanglsp.SlangBundle;

/** Uses exactly the same verified, undoable rename plan as Refactor | Rename. */
final class NamingRenameFix implements IntentionAction {
    private final SmartPsiElementPointer<PsiElement> pointer;
    private final String oldName;
    private final String newName;

    NamingRenameFix(PsiElement element, String newName) {
        pointer = SmartPointerManager.createPointer(element);
        oldName = element.getText();
        this.newName = newName;
    }

    @Override
    public @NotNull String getText() {
        return SlangBundle.message("naming.rename", newName);
    }

    @Override
    public @NotNull String getFamilyName() {
        return SlangBundle.message("naming.inspection");
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        var element = pointer.getElement();
        return element != null && element.isWritable() && oldName.equals(element.getText())
                && !com.intellij.openapi.project.DumbService.isDumb(project);
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        new Task.Backgroundable(project, SlangBundle.message("rename.verifying.slang.rename"), true) {
            private RenamePlan plan;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                var element = ReadActions.compute(pointer::getElement);
                if (element == null || !ReadActions.compute(() -> oldName.equals(element.getText()))) {
                    throw new IllegalStateException(SlangBundle.message("rename.the.symbol.changed.run.rename.again"));
                }
                plan = RenamePlan.collect(element, newName);
            }

            @Override
            public void onSuccess() {
                if (!project.isDisposed()) {
                    try {
                        SlangRenameHandler.finishRename(project, plan, false);
                    } catch (IllegalStateException failure) {
                        onThrowable(failure);
                    }
                }
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                if (!project.isDisposed()) {
                    Messages.showErrorDialog(project,
                            error.getMessage() == null
                                    ? SlangBundle.message("rename.slangd.could.not.verify.the.rename")
                                    : error.getMessage(),
                            SlangBundle.message("rename.cannot.rename.slang.symbol"));
                }
            }
        }.queue();
    }
}
