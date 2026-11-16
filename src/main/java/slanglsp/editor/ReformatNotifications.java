package slanglsp.editor;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.util.registry.Registry;
import org.jetbrains.annotations.NotNull;
import slanglsp.language.SlangLanguage;

import java.util.IdentityHashMap;
import java.util.Map;

/** FileInEditorProcessor captures the notification preference while the action runs. */
public final class ReformatNotifications implements AnActionListener {
    static final String NOTIFICATION_KEY = "editor.show.notification.after.reformat";
    private final Map<AnActionEvent, Boolean> previous = new IdentityHashMap<>();

    @Override
    public void beforeActionPerformed(@NotNull AnAction action, @NotNull AnActionEvent event) {
        String id = ActionManager.getInstance().getId(action);
        if (!"ReformatCode".equals(id) && !"ShowReformatFileDialog".equals(id)) {
            return;
        }
        var file = event.getData(CommonDataKeys.PSI_FILE);
        if (file == null || !file.getLanguage().isKindOf(SlangLanguage.INSTANCE)) {
            return;
        }
        var value = Registry.get(NOTIFICATION_KEY);
        previous.put(event, value.asBoolean());
        value.setValue(false);
    }

    @Override
    public void afterActionPerformed(@NotNull AnAction action, @NotNull AnActionEvent event,
                                     @NotNull AnActionResult result) {
        restoreNotification(event);
    }

    void restoreNotification(AnActionEvent event) {
        Boolean value = previous.remove(event);
        if (value != null) {
            Registry.get(NOTIFICATION_KEY).setValue(value);
        }
    }
}
