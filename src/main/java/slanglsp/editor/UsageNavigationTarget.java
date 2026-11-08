package slanglsp.editor;

import slanglsp.SlangBundle;

import com.intellij.find.findUsages.FindUsagesOptions;
import com.intellij.ide.DataManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.UsageInfo2UsageAdapter;
import com.intellij.usageView.UsageViewBundle;
import com.redhat.devtools.lsp4ij.features.LSPPsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Resolve the result count before opening a chooser, so single usages navigate without a flashing list. */
final class UsageNavigationTarget extends LSPPsiElement {
    private final SmartPsiElementPointer<PsiElement> declaration;
    private final Editor editor;

    UsageNavigationTarget(PsiElement element, Editor editor) {
        super(element.getContainingFile(), element.getTextRange());
        declaration = SmartPointerManager.createPointer(element);
        this.editor = editor;
    }

    @Override public void navigate(boolean requestFocus) {
        PsiElement target = declaration.getElement();
        if (target == null || !canNavigate() || getProject().isDisposed()) return;
        var project = getProject();
        int caretOffset = editor.getCaretModel().getOffset();
        String title = SlangBundle.message("usages.progress", target.getText());
        long modificationCount = com.intellij.psi.util.PsiModificationTracker.getInstance(project).getModificationCount();
        var dataContext = DataManager.getInstance().getDataContext(editor.getComponent());
        var scope = new FindUsagesOptions(project, dataContext).searchScope;
        new Task.Backgroundable(project, title, true) {
            private final List<UsageInfo> usages = new ArrayList<>();

            @Override public void run(@NotNull ProgressIndicator indicator) {
                UsageSearch.process(target, scope, usage -> { usages.add(usage); return true; });
            }

            @Override public void onSuccess() {
                if (project.isDisposed() || !canNavigate() || editor.getCaretModel().getOffset() != caretOffset
                        || modificationCount != com.intellij.psi.util.PsiModificationTracker.getInstance(project).getModificationCount()) return;
                if (usages.stream().anyMatch(usage -> !usage.isValid())) return;
                if (usages.isEmpty()) {
                    HintManager.getInstance().showInformationHint(editor,
                            UsageViewBundle.message("no.usages.found.in", scope.getDisplayName()));
                } else if (usages.size() == 1) {
                    new UsageInfo2UsageAdapter(usages.getFirst()).navigate(requestFocus);
                } else {
                    var items = usages.stream().map(UsageInfo2UsageAdapter::new).toList();
                    JBPopupFactory.getInstance().createPopupChooserBuilder(items)
                            .setTitle(title)
                            .setRenderer(SimpleListCellRenderer.create("", usage -> {
                                var file = usage.getFile();
                                return (file == null ? "" : file.getPresentableUrl() + ": ")
                                        + usage.getPresentation().getPlainText();
                            }))
                            .setItemChosenCallback(usage -> usage.navigate(requestFocus))
                            .createPopup().showInBestPositionFor(editor);
                }
            }
        }.queue();
    }

    @Override public boolean canNavigate() {
        return editor != null && !editor.isDisposed() && declaration.getElement() != null;
    }
    @Override public boolean canNavigateToSource() { return canNavigate(); }
}
