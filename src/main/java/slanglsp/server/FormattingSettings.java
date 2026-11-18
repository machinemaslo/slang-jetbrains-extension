package slanglsp.server;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.ProcessCanceledException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Slangd has server-wide formatting options, including during startup configuration requests. */
@Service(Service.Level.PROJECT)
public final class FormattingSettings {
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Map<String, Object> active = Map.of();

    public AutoCloseable open(Map<String, Object> settings, BooleanSupplier cancelled)
            throws InterruptedException, TimeoutException {
        var options = new HashMap<String, Object>();
        settings.forEach((key, value) -> {
            if (key.startsWith("slang.format.")) options.put(key, value);
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!lock.tryLock(50, TimeUnit.MILLISECONDS)) {
            ProgressManager.checkCanceled();
            if (cancelled.getAsBoolean()) {
                throw new ProcessCanceledException();
            }
            if (System.nanoTime() >= deadline) {
                throw new TimeoutException("Waiting for Slang formatting settings timed out");
            }
        }
        var previous = active;
        active = java.util.Collections.unmodifiableMap(options);
        return () -> {
            active = previous;
            lock.unlock();
        };
    }

    void apply(Map<String, Object> settings) {
        settings.putAll(active);
    }
}
