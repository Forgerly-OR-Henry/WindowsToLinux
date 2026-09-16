package gold.debug.windowstolinux.shared.linux.sshd.session;

import org.apache.sshd.client.SshClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Isolated
class LoopbackSshServerTest {
    @TempDir Path directory;

    private void connect(LoopbackSshServer fixture) throws Exception {
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier((session, address, key) -> true); client.start();
            try (var session = client.connect("fixture", "127.0.0.1", fixture.server().getPort())
                    .verify(Duration.ofSeconds(5)).getSession()) {
                session.addPasswordIdentity("fixture"); session.auth().verify(Duration.ofSeconds(5));
                assertTrue(session.isAuthenticated());
                SshSessionLifecycleExecutor.closeQuietly(session);
            } finally { SshSessionLifecycleExecutor.closeQuietly(client); }
            assertTrue(client.isClosed());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getName().contains(client.toString())) {
                    long remaining = deadline - System.nanoTime();
                    assertTrue(remaining > 0, "Client thread shutdown timed out");
                    TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
                    assertFalse(thread.isAlive(), "Leaked client thread: " + thread.getName());
                }
            }
        }
    }

    @Test void rearmedAcceptMustCompleteBeforeExecutorsCanClose() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try (var fixture = new LoopbackSshServer(directory, server -> { })) {
            connect(fixture);
            assertEquals(1, fixture.pendingAccepts(), "the rearmed accept must remain tracked");
            fixture.beforeCompletion(() -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            });
            Future<?> closing = closer.submit(() -> { fixture.close(); return null; });
            try {
                assertTrue(fixture.listenerStopped.await(5, TimeUnit.SECONDS));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(closing.isDone());
                assertFalse(fixture.executorsShutdown(), "callback still needs its executors");
                assertEquals(1, fixture.pendingAccepts());
            } finally { release.countDown(); }
            closing.get(10, TimeUnit.SECONDS);
            assertEquals(0, fixture.pendingAccepts());
            assertTrue(fixture.executorsShutdown());
        } finally {
            release.countDown(); closer.shutdownNow();
            assertTrue(closer.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void backgroundExceptionFailsFixtureAndRestoresOriginalHandler() throws Exception {
        var handler = Thread.getDefaultUncaughtExceptionHandler();
        var injected = new IllegalStateException("injected background failure");
        var failure = assertThrows(AssertionError.class, () -> {
            try (var fixture = new LoopbackSshServer(directory, server -> { })) {
                Thread worker = new Thread(() -> { throw injected; }); worker.start(); worker.join(5000);
                assertFalse(worker.isAlive());
            }
        });
        assertSame(injected, failure.getCause());
        assertSame(handler, Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test void originalAssertionRemainsPrimaryWhenBackgroundCleanupAlsoFails() {
        var handler = Thread.getDefaultUncaughtExceptionHandler();
        AssertionError primary = new AssertionError("original assertion");
        var failure = assertThrows(AssertionError.class, () -> {
            try (var scope = new BackgroundExceptionScope()) {
                Thread worker = new Thread(() -> { throw new IllegalStateException("worker"); });
                worker.start(); worker.join(5000); assertFalse(worker.isAlive());
                throw primary;
            }
        });
        assertSame(primary, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("worker", failure.getSuppressed()[0].getCause().getMessage());
        assertSame(handler, Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test void fiftyRealConnectionsAndServerShutdownsLeaveNoResourcesOrExceptions() throws Exception {
        for (int iteration = 0; iteration < 50; iteration++) {
            try (var fixture = new LoopbackSshServer(directory, server -> { })) { connect(fixture); }
        }
    }
}
