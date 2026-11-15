package slanglsp.editor;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.TextRange;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import slanglsp.SlangBundle;

import java.util.List;

/** The daemon schedules external annotators after editing; no processes run under a read action. */
public final class SlangFormattingAnnotator
        extends ExternalAnnotator<SlangFormattingAnnotator.Input, SlangFormattingAnnotator.Result> {
    public static final class Input {
        private final Document document;
        private final long stamp;
        private final String text;
        private final ClangFormat.Options options;

        Input(Document document, long stamp, String text, ClangFormat.Options options) {
            this.document = document;
            this.stamp = stamp;
            this.text = text;
            this.options = options;
        }
    }

    public record Result(Input input, int offset) {}

    @Override
    public String getPairedBatchInspectionShortName() {
        return SlangFormattingInspection.SHORT_NAME;
    }

    @Override
    public Input collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
        return hasErrors ? null : collectInformation(file);
    }

    @Override
    public Input collectInformation(@NotNull PsiFile file) {
        var key = HighlightDisplayKey.find(SlangFormattingInspection.SHORT_NAME);
        var profile = InspectionProjectProfileManager.getInstance(file.getProject()).getCurrentProfile();
        if (key == null || !profile.isToolEnabled(key, file) || file.getVirtualFile() == null
                || !ProjectFileIndex.getInstance(file.getProject()).isInContent(file.getVirtualFile())) return null;
        var document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null || document.getTextLength() == 0 || document.getTextLength() > 1_000_000) return null;
        var options = ClangFormat.options(file);
        return options == null ? null : new Input(document, document.getModificationStamp(), document.getText(), options);
    }

    @Override
    public Result doAnnotate(Input input) {
        if (input.document.getModificationStamp() != input.stamp) return null;
        try {
            String formatted = ClangFormat.format(input.options, input.text, List.of(), handler -> {});
            if (formatted.equals(input.text) || input.document.getModificationStamp() != input.stamp) return null;
            int offset = 0;
            while (offset < Math.min(formatted.length(), input.text.length())
                    && formatted.charAt(offset) == input.text.charAt(offset)) offset++;
            return new Result(input, Math.min(offset, input.text.length() - 1));
        } catch (ProcessCanceledException failure) {
            throw failure;
        } catch (Exception failure) {
            // Missing/broken tools and invalid configs are reported by explicit formatting,
            // not as code-style violations on otherwise valid source.
            return null;
        }
    }

    @Override
    public void apply(@NotNull PsiFile file, Result result, @NotNull AnnotationHolder holder) {
        if (result == null || result.input.document.getModificationStamp() != result.input.stamp
                || !result.input.options.equals(ClangFormat.options(file))) return;
        var key = HighlightDisplayKey.find(SlangFormattingInspection.SHORT_NAME);
        var profile = InspectionProjectProfileManager.getInstance(file.getProject()).getCurrentProfile();
        if (key == null || !profile.isToolEnabled(key, file)) return;
        holder.newAnnotation(profile.getErrorLevel(key, file).getSeverity(),
                        SlangBundle.message("inspection.formatting.message"))
                .range(new TextRange(result.offset, result.offset + 1))
                .highlightType(ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
                .withFix(new Reformat()).create();
    }

    static final class Reformat implements IntentionAction {
        @Override
        public @NotNull String getText() {
            return SlangBundle.message("inspection.formatting.fix");
        }

        @Override
        public @NotNull String getFamilyName() {
            return getText();
        }

        @Override
        public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            return file != null && file.isWritable() && ClangFormat.options(file) != null;
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
            new ReformatCodeProcessor(project, file, null, false).run();
        }

        @Override
        public boolean startInWriteAction() {
            return false;
        }
    }
}
