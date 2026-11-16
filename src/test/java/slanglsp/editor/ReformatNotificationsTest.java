package slanglsp.editor;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import org.junit.jupiter.api.Test;
import slanglsp.language.SlangFileType;

import static org.junit.jupiter.api.Assertions.*;

class ReformatNotificationsTest {
    @Test
    void suppressesOnlySlangFormattingAndRestoresPreference() throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var fixture = factory.createCodeInsightFixture(factory.createLightFixtureBuilder("ReformatNotifications").getFixture());
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                var value = Registry.get(ReformatNotifications.NOTIFICATION_KEY);
                boolean original = value.asBoolean();
                var listener = new ReformatNotifications();
                try {
                    for (boolean enabled : new boolean[] {true, false}) {
                        value.setValue(enabled);
                        var file = fixture.configureByText(SlangFileType.INSTANCE, "void f() {}");
                        var context = SimpleDataContext.builder().add(CommonDataKeys.PSI_FILE, file).build();
                        for (String id : new String[] {"ReformatCode", "ShowReformatFileDialog"}) {
                            var action = ActionManager.getInstance().getAction(id);
                            assertNotNull(action);
                            var event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, context);
                            listener.beforeActionPerformed(action, event);
                            assertFalse(value.asBoolean());
                            listener.restoreNotification(event);
                            assertEquals(enabled, value.asBoolean());
                        }
                    }
                    value.setValue(true);
                    var file = fixture.configureByText("notes.txt", "plain text");
                    var action = ActionManager.getInstance().getAction("ReformatCode");
                    var event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN,
                            SimpleDataContext.builder().add(CommonDataKeys.PSI_FILE, file).build());
                    listener.beforeActionPerformed(action, event);
                    assertTrue(value.asBoolean());
                    listener.restoreNotification(event);
                    assertTrue(value.asBoolean());
                } finally {
                    value.setValue(original);
                }
            });
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }
}
