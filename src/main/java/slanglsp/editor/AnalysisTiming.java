package slanglsp.editor;

import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.atomic.AtomicInteger;

/** Enable #slanglsp.editor.AnalysisTiming in the IDE's Debug Log Settings. */
final class AnalysisTiming implements AutoCloseable {
    private static final Logger LOG = Logger.getInstance(AnalysisTiming.class);
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private final String operation;
    private final int id;
    private final long started = System.nanoTime();

    private AnalysisTiming(String operation, String file) {
        this.operation = operation;
        id = NEXT_ID.incrementAndGet();
        LOG.debug("Slang analysis #" + id + " started: " + operation + " file=" + file
                + " thread=" + Thread.currentThread().getName());
    }

    static AnalysisTiming start(String operation, String file) {
        return LOG.isDebugEnabled() ? new AnalysisTiming(operation, file) : null;
    }

    @Override
    public void close() {
        LOG.debug("Slang analysis #" + id + " finished: " + operation
                + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000);
    }
}
