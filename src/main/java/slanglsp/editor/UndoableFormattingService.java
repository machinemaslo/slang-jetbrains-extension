package slanglsp.editor;

import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import slanglsp.SlangBundle;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit formatting owns an undo command, rather than an undo-transparent document update. */
final class UndoableFormattingService {
    private static final Key<Object> LATEST_REQUEST = Key.create("slang.formatting.latestRequest");

    private UndoableFormattingService() {
    }

    static final class Result implements AsyncFormattingRequest {
        private final AsyncFormattingRequest request;
        private final Document document;
        private final long stamp;
        private final String originalText;
        private final String commandGroup = UUID.randomUUID().toString();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        Result(AsyncFormattingRequest request, Document document) {
            this.request = request;
            this.document = document;
            stamp = document.getModificationStamp();
            originalText = document.getText();
            document.putUserData(LATEST_REQUEST, commandGroup);
        }

        void cancel() {
            cancelled.set(true);
        }

        @Override
        public void onTextReady(@Nullable String updatedText) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }

            if (updatedText != null && !updatedText.equals(originalText)) {
                var project = getContext().getProject();

                // Always leave the originating action first, including synchronous/headless formatting.
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed() || cancelled.get()
                            || document.getUserData(LATEST_REQUEST) != commandGroup
                            || document.getModificationStamp() != stamp || !document.isWritable()) {
                        return;
                    }

                    WriteCommandAction.writeCommandAction(project)
                            .withName(SlangBundle.message("command.reformat.code"))
                            .withGroupId(commandGroup)
                            .run(() -> document.setText(updatedText));
                }, ModalityState.nonModal(), project.getDisposed());
            }

            // Complete the async service without its default undo-transparent update.
            request.onTextReady(null);
        }

        @Override
        public void onError(@NotNull String title, @NotNull String message) {
            if (completed.compareAndSet(false, true)) {
                request.onError(title, message);
            }
        }

        @Override
        public void onError(@NotNull String title, @NotNull String message, int offset) {
            if (completed.compareAndSet(false, true)) {
                request.onError(title, message, offset);
            }
        }

        @Override
        @NotNull
        public String getDocumentText() {
            return originalText;
        }

        @Override
        @Nullable
        public File getIOFile() {
            return request.getIOFile();
        }

        @Override
        @NotNull
        public List<TextRange> getFormattingRanges() {
            return request.getFormattingRanges();
        }

        @Override
        public boolean canChangeWhitespaceOnly() {
            return request.canChangeWhitespaceOnly();
        }

        @Override
        public boolean isQuickFormat() {
            return request.isQuickFormat();
        }

        @Override
        @NotNull
        public FormattingContext getContext() {
            return request.getContext();
        }
    }
}
