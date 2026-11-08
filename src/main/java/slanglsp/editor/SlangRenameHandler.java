package slanglsp.editor;

import slanglsp.SlangBundle;
import slanglsp.language.SlangLanguage;
import slanglsp.server.BuiltinFiles;

import com.intellij.ide.TitledHandler;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidator;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.refactoring.rename.RenameHandler;
import com.intellij.usages.*;
import com.redhat.devtools.lsp4ij.features.rename.LSPRenameHandler;
import org.jetbrains.annotations.NotNull;

/** Native Rename fallback only when no server advertises textDocument/rename. */
public final class SlangRenameHandler implements RenameHandler, TitledHandler {
    private static PsiElement target(PsiFile file, Editor editor) {
        if (file == null || editor == null || editor.isDisposed() || file.getLanguage() != SlangLanguage.INSTANCE
                || file.getVirtualFile() instanceof BuiltinFiles.BuiltinFile || !file.isWritable()) return null;
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);
        if (!SlangFindUsagesProvider.isIdentifier(element) && offset > 0) element = file.findElementAt(offset - 1);
        return SlangFindUsagesProvider.isIdentifier(element) ? element : null;
    }

    @Override public boolean isAvailableOnDataContext(@NotNull DataContext context) {
        Project project = CommonDataKeys.PROJECT.getData(context);
        if (project == null || project.isDisposed() || DumbService.isDumb(project)) return false;
        PsiFile file = CommonDataKeys.PSI_FILE.getData(context);
        if (target(file, CommonDataKeys.EDITOR.getData(context)) == null) return false;
        // Let LSP4IJ handle prepareRename, workspace edits and future native support.
        return !new LSPRenameHandler().isAvailableOnDataContext(context);
    }

    @Override public void invoke(@NotNull Project project, Editor editor, PsiFile file, DataContext context) {
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        PsiElement element = target(file, editor);
        if (element == null) return;
        if (new LSPRenameHandler().isAvailableOnDataContext(context)) {
            new LSPRenameHandler().invoke(project, editor, file, context);
            return;
        }
        String name = element.getText();
        String replacement = Messages.showInputDialog(project,
                SlangBundle.message("rename.prompt", name), SlangBundle.message("rename.rename.slang.symbol"), null, name,
                new InputValidator() {
                    @Override public boolean checkInput(String input) {
                        return RenamePlan.validName(input) && !name.equals(input);
                    }
                    @Override public boolean canClose(String input) { return checkInput(input); }
                });
        if (replacement == null) return;
        var pointer = SmartPointerManager.createPointer(element);
        new Task.Backgroundable(project, SlangBundle.message("rename.verifying.slang.rename"), true) {
            private RenamePlan plan;

            @Override public void run(@NotNull ProgressIndicator indicator) {
                PsiElement source = ReadActions.compute(pointer::getElement);
                if (source == null) throw new IllegalStateException(SlangBundle.message("rename.the.symbol.changed.run.rename.again"));
                plan = RenamePlan.collect(source, replacement);
            }

            @Override public void onSuccess() {
                if (project.isDisposed()) return;
                try {
                    showPreview(project, plan);
                } catch (IllegalStateException failure) {
                    showError(project, failure.getMessage());
                }
            }

            @Override public void onThrowable(@NotNull Throwable error) {
                if (!project.isDisposed()) showError(project, error.getMessage() == null
                        ? SlangBundle.message("rename.slangd.could.not.verify.the.rename") : error.getMessage());
            }
        }.queue();
    }

    static UsageView showPreview(Project project, RenamePlan plan) {
        var presentation = new UsageViewPresentation();
        presentation.setTabText(SlangBundle.message("rename.tab", plan.oldName));
        presentation.setTabName(SlangBundle.message("rename.command", plan.oldName, plan.newName));
        presentation.setCodeUsagesString(SlangBundle.message("rename.verified.occurrences.including.declaration"));
        Usage[] usages = java.util.Arrays.stream(plan.usages()).map(UsageInfo2UsageAdapter::new).toArray(Usage[]::new);
        UsageView view = UsageViewManager.getInstance(project).showUsages(UsageTarget.EMPTY_ARRAY, usages, presentation);
        view.setAdditionalComponent(new javax.swing.JLabel(
                SlangBundle.message("rename.project.slang.files.only.inactive.code.and.name.conflicts.cannot.be.checked.by.this.fallback")));
        view.addPerformOperationAction(() -> {
            if (!view.getExcludedUsages().isEmpty()) {
                showError(project, SlangBundle.message("rename.include.every.verified.occurrence.before.renaming.the.symbol"));
                return;
            }
            try {
                plan.apply();
                view.close();
            } catch (IllegalStateException failure) {
                showError(project, failure.getMessage());
            }
        }, SlangBundle.message("rename.tab", plan.oldName), SlangBundle.message("rename.files.changed.run.rename.again"), SlangBundle.message("rename.action"), false);
        return view;
    }

    private static void showError(Project project, String message) {
        Messages.showErrorDialog(project, message, SlangBundle.message("rename.cannot.rename.slang.symbol"));
    }

    @Override public void invoke(@NotNull Project project, PsiElement @NotNull [] elements, DataContext context) {
        Editor editor = CommonDataKeys.EDITOR.getData(context);
        PsiFile file = CommonDataKeys.PSI_FILE.getData(context);
        if (editor != null && file != null) invoke(project, editor, file, context);
    }

    @Override public String getActionTitle() { return SlangBundle.message("rename.rename.slang.symbol"); }
}
