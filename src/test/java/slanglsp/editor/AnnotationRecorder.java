package slanglsp.editor;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Records the public annotation-builder protocol without IDE-internal annotation implementations. */
final class AnnotationRecorder extends ArrayList<AnnotationRecorder.RecordedAnnotation> {
    static final class Fix {
        final IntentionAction quickFix;
        TextRange textRange;

        Fix(IntentionAction quickFix) {
            this.quickFix = quickFix;
        }
    }

    static final class RecordedAnnotation {
        private TextRange range;
        private final String message;
        private final List<Fix> fixes = new ArrayList<>();

        RecordedAnnotation(String message) {
            this.message = message;
        }

        int getStartOffset() {
            return range.getStartOffset();
        }

        int getEndOffset() {
            return range.getEndOffset();
        }

        @Nullable String getMessage() {
            return message;
        }

        @Nullable String getTooltip() {
            return message;
        }

        @NotNull List<Fix> getQuickFixes() {
            return fixes;
        }
    }

    AnnotationHolder holder() {
        return proxy(AnnotationHolder.class, (object, method, args) -> switch (method.getName()) {
            case "newAnnotation" -> builder(new RecordedAnnotation((String) args[1]));
            case "newSilentAnnotation" -> builder(new RecordedAnnotation(null));
            default -> throw new AssertionError("Unexpected annotation-holder call: " + method);
        });
    }

    private AnnotationBuilder builder(RecordedAnnotation annotation) {
        return proxy(AnnotationBuilder.class, (object, method, args) -> switch (method.getName()) {
            case "range" -> {
                annotation.range = (TextRange) args[0];
                yield object;
            }
            case "highlightType" -> object;
            case "newFix" -> {
                var fix = new Fix((IntentionAction) args[0]);
                yield proxy(AnnotationBuilder.FixBuilder.class, (fixObject, fixMethod, fixArgs) ->
                        switch (fixMethod.getName()) {
                            case "range" -> {
                                fix.textRange = (TextRange) fixArgs[0];
                                yield fixObject;
                            }
                            case "registerFix" -> {
                                annotation.fixes.add(fix);
                                yield object;
                            }
                            default -> throw new AssertionError("Unexpected fix-builder call: " + fixMethod);
                        });
            }
            case "create" -> {
                add(annotation);
                yield null;
            }
            default -> throw new AssertionError("Unexpected annotation-builder call: " + method);
        });
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
