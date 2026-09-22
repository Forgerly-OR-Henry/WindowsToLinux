package gold.debug.windowstolinux.shared.linux.sshd.session;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Exclusive test scope: an uncaught worker exception is a test failure. */
public final class BackgroundExceptionScope implements AutoCloseable {
    private static final Semaphore EXCLUSIVE = new Semaphore(1);

    private final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

    private final Thread.UncaughtExceptionHandler previous;

    private boolean closed;

    public BackgroundExceptionScope() throws InterruptedException {
        if (!EXCLUSIVE.tryAcquire(10, TimeUnit.SECONDS))
            throw new AssertionError("Background exception scope is busy");
        previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> failures.add(failure));
    }

    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        try {
            if (!failures.isEmpty()) {
                AssertionError failure = new AssertionError("Uncaught background exception", failures.remove());
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
            EXCLUSIVE.release();
        }
    }
}
