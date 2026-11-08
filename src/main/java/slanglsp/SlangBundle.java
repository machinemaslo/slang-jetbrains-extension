package slanglsp;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

public final class SlangBundle {
    private static final String BUNDLE = "messages.SlangBundle";
    private static final DynamicBundle INSTANCE = new DynamicBundle(SlangBundle.class, BUNDLE);

    private SlangBundle() {
    }

    public static @NotNull @Nls String message(@PropertyKey(resourceBundle = BUNDLE) String key, Object... params) {
        return INSTANCE.getMessage(key, params);
    }
}
