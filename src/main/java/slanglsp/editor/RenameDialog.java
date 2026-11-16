package slanglsp.editor;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import slanglsp.SlangBundle;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;

/** Explicit preview is available without making it a mandatory second confirmation. */
final class RenameDialog extends DialogWrapper {
    private final String oldName;
    private final JBTextField name;
    private boolean previewRequested;
    private final Action preview = new AbstractAction(SlangBundle.message("rename.preview")) {
        @Override
        public void actionPerformed(ActionEvent event) {
            if (doValidate() == null) {
                previewRequested = true;
                close(OK_EXIT_CODE);
            }
        }
    };

    RenameDialog(Project project, String oldName) {
        super(project);
        this.oldName = oldName;
        name = new JBTextField(oldName, 30);
        setTitle(SlangBundle.message("rename.rename.slang.symbol"));
        setOKButtonText(SlangBundle.message("rename.action"));
        init();
        name.selectAll();
        name.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                updateActions();
            }
        });
        updateActions();
    }

    private void updateActions() {
        boolean valid = doValidate() == null;
        setOKActionEnabled(valid);
        preview.setEnabled(valid);
    }

    String replacement() {
        return name.getText();
    }

    boolean previewRequested() {
        return previewRequested;
    }

    @Override
    protected ValidationInfo doValidate() {
        if (!RenamePlan.validName(replacement())) {
            return new ValidationInfo(
                    SlangBundle.message("rename.enter.a.slang.identifier.not.a.keyword.or.built.in.type"), name);
        }
        return oldName.equals(replacement())
                ? new ValidationInfo(SlangBundle.message("rename.choose.a.different.identifier"), name)
                : null;
    }

    @Override
    protected JComponent createCenterPanel() {
        var panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.add(new JBLabel(SlangBundle.message("rename.prompt", oldName)), BorderLayout.NORTH);
        panel.add(name, BorderLayout.CENTER);
        return panel;
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return name;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[] {getOKAction(), preview, getCancelAction()};
    }
}
