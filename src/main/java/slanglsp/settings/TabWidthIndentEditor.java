package slanglsp.settings;

import com.intellij.application.options.SmartIndentOptionsEditor;
import com.intellij.openapi.application.ApplicationBundle;
import slanglsp.SlangBundle;

import javax.swing.*;
import java.awt.*;

/** Retains the platform indent controls with clearer tab-stop terminology. */
final class TabWidthIndentEditor extends SmartIndentOptionsEditor {
    @Override
    public JPanel createPanel() {
        JPanel panel = super.createPanel();
        renameTabLabel(panel);
        return panel;
    }

    private static void renameTabLabel(Container container) {
        String original = ApplicationBundle.message("editbox.indent.tab.size");
        for (Component component : container.getComponents()) {
            if (component instanceof JLabel label && original.equals(label.getText())) {
                label.setText(SlangBundle.message("settings.code.style.tab.width"));
            }
            if (component instanceof Container child) {
                renameTabLabel(child);
            }
        }
    }
}
