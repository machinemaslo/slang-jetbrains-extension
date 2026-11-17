package slanglsp.editor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Separate JVM used to exercise process cancellation/timeout on Windows as well as Unix. */
public final class UnresponsiveDiagnosticProcess {
    public static void main(String[] arguments) throws Exception {
        Files.writeString(Path.of(arguments[0]), Long.toString(ProcessHandle.current().pid()));
        new CountDownLatch(1).await();
    }
}
