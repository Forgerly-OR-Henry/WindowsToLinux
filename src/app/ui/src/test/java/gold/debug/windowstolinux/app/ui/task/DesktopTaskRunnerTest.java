package gold.debug.windowstolinux.app.ui.task;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.service.security.DesktopTaskAuthorization;
import gold.debug.windowstolinux.app.service.security.SecurityAccess;
import org.junit.jupiter.api.Test;

class DesktopTaskRunnerTest {
    record Event<T>(T value, Exception failure, boolean edt) {
    }

    record Pending<T>(DesktopTaskHandle handle, CompletableFuture<Event<T>> completion) {
        Event<T> await() throws Exception {
            var event = completion.get(8, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
            });
            return event;
        }
    }

    static SecurityAccess boundary(AtomicInteger closes, RuntimeException closeFailure) {
        return (SecurityAccess) Proxy.newProxyInstance(SecurityAccess.class.getClassLoader(),
            new Class<?>[]{SecurityAccess.class}, (proxy, method, args) -> {
                if (!method.getName().equals("beginTask"))
                    throw new AssertionError("Unexpected security call: " + method.getName());
                return new DesktopTaskAuthorization() {
                    public <T> T execute(Callable<T> operation) throws Exception {
                        return operation.call();
                    }

                    public void close() {
                        closes.incrementAndGet();
                        if (closeFailure != null)
                            throw closeFailure;
                    }
                };
            });
    }

    static <T> Pending<T> submit(Callable<T> operation) throws Exception {
        var completion = new CompletableFuture<Event<T>>();
        var handle = new AtomicReference<DesktopTaskHandle>();
        SwingUtilities.invokeAndWait(() -> handle.set(DesktopTaskRunner.submit(operation,
            value -> completion.complete(new Event<>(value, null, SwingUtilities.isEventDispatchThread())),
            failure -> completion.complete(new Event<>(null, failure, SwingUtilities.isEventDispatchThread())))));
        return new Pending<>(handle.get(), completion);
    }

    @Test
    void authorizationCloseFailureStillFinishesHandleAndActivity() throws Exception {
        var closes = new AtomicInteger();
        var failure = new IllegalStateException("fixture release failed");
        var security = boundary(closes, failure);
        DesktopTaskRunner.bindSecurity(security);
        try {
            var event = submit(() -> "completed work").await();
            assertSame(failure, event.failure().getCause());
            assertTrue(event.edt());
            assertEquals(1, closes.get());
            assertFalse(DesktopTaskRunner.hasActiveTasks());
        } finally {
            DesktopTaskRunner.unbindSecurity(security);
        }
    }

    @Test
    void callbackFailureRemainsPrimaryWithOneSuppressedReleaseFailure() throws Exception {
        var closes = new AtomicInteger();
        var original = new IllegalArgumentException("fixture work failed");
        var cleanup = new IllegalStateException("fixture release failed");
        var security = boundary(closes, cleanup);
        DesktopTaskRunner.bindSecurity(security);
        try {
            var event = submit(() -> {
                throw original;
            }).await();
            assertSame(original, event.failure().getCause());
            assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
            assertEquals(1, closes.get());
            assertFalse(DesktopTaskRunner.hasActiveTasks());
        } finally {
            DesktopTaskRunner.unbindSecurity(security);
        }
    }

    @Test
    void completedHandleCannotInterruptItsWorkerReusedByAnotherTask() throws Exception {
        var closes = new AtomicInteger();
        var security = boundary(closes, new IllegalStateException("fixture release failed"));
        var originalWorker = new AtomicReference<Thread>();
        Pending<String> original;
        DesktopTaskRunner.bindSecurity(security);
        try {
            original = submit(() -> {
                originalWorker.set(Thread.currentThread());
                return "original";
            });
            assertNotNull(original.await().failure());
        } finally {
            DesktopTaskRunner.unbindSecurity(security);
        }
        // Occupy all ten JDK 21 SwingWorker slots, including the original worker. / 占用 JDK 21 的十个工作槽，包含原工作线程。
        var started = new CountDownLatch(10);
        var release = new CountDownLatch(1);
        var reused = new AtomicBoolean();
        var pending = new ArrayList<Pending<String>>();
        try {
            for (int i = 0; i < 10; i++)
                pending.add(submit(() -> {
                    if (Thread.currentThread() == originalWorker.get())
                        reused.set(true);
                    started.countDown();
                    if (!release.await(8, TimeUnit.SECONDS))
                        throw new AssertionError("Fixture release timed out");
                    return "uninterrupted";
                }));
            assertTrue(started.await(8, TimeUnit.SECONDS));
            assertTrue(reused.get());
            original.handle().cancel();
        } finally {
            release.countDown();
            for (var next : pending) {
                var event = next.await();
                assertNull(event.failure());
                assertEquals("uninterrupted", event.value());
            }
        }
        assertEquals(1, closes.get());
        assertFalse(DesktopTaskRunner.hasActiveTasks());
    }

    @Test
    void successfulWorkClosesAuthorizationBeforeEdtDelivery() throws Exception {
        var closes = new AtomicInteger();
        var security = boundary(closes, null);
        DesktopTaskRunner.bindSecurity(security);
        try {
            var event = submit(() -> "success").await();
            assertNull(event.failure());
            assertEquals("success", event.value());
            assertTrue(event.edt());
            assertEquals(1, closes.get());
            assertFalse(DesktopTaskRunner.hasActiveTasks());
        } finally {
            DesktopTaskRunner.unbindSecurity(security);
        }
    }
}
