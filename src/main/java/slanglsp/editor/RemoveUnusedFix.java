package slanglsp.editor;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.annotations.NotNull;
import slanglsp.SlangBundle;

final class RemoveUnusedFix implements IntentionAction {
    private final UnusedSymbolsAnnotator.Input input;
    private final UnusedSymbolsAnnotator.Candidate candidate;

    RemoveUnusedFix(UnusedSymbolsAnnotator.Input input, UnusedSymbolsAnnotator.Candidate candidate) {
        this.input = input;
        this.candidate = candidate;
    }

    @Override
    public @NotNull String getText() {
        return candidate.removalText();
    }

    @Override
    public @NotNull String getFamilyName() {
        return SlangBundle.message("unused.remove");
    }

    private boolean current() {
        return input.file().isValid() && input.file().isWritable() && input.document().isWritable()
                && input.document().getModificationStamp() == input.stamp()
                && PsiModificationTracker.getInstance(input.file().getProject()).getModificationCount() == input.projectStamp();
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return file == input.file() && current() && !com.intellij.openapi.project.DumbService.isDumb(project);
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        var range = UnusedRemoval.range(input.text(), candidate);
        if (range == null || !current()) {
            return;
        }
        new Task.Backgroundable(project, getText(), true) {
            private boolean unused;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                var element = ReadActions.compute(() -> current()
                        ? input.file().findElementAt(candidate.range().getStartOffset()) : null);
                unused = element != null && UsageSearch.isUnused(element);
            }

            @Override
            public void onSuccess() {
                if (project.isDisposed()) {
                    return;
                }
                if (unused) {
                    WriteCommandAction.runWriteCommandAction(project, getText(), null, () -> {
                        if (current()) {
                            input.document().deleteString(range.getStartOffset(), range.getEndOffset());
                            PsiDocumentManager.getInstance(project).commitAllDocuments();
                        }
                    });
                }
                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart();
            }
        }.queue();
    }
}
