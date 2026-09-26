package gold.debug.windowstolinux.app.linux.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;

class BrowserRecoveryEvidenceTest {
    private static final RecoveryTarget TARGET = new RecoveryTarget("server", "example.test", 22);

    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Safe fixture", Map.of(), Optional.of(UUID.randomUUID()));
    }

    @Test
    void probesRetainOriginalEvidenceAndNeverRetryUnclassifiedOrCancelledFailures() {
        var expected = Map.of("SSH_CONNECTION_FAILED", RecoveryProbeResult.StatusType.RETRYABLE_CONNECTION,
                "SSH_CONNECT_TIMEOUT", RecoveryProbeResult.StatusType.RETRYABLE_CONNECTION, "SSH_AUTHENTICATION_FAILED",
                RecoveryProbeResult.StatusType.AUTHENTICATION_REJECTED, "SSH_HOST_KEY_REJECTED",
                RecoveryProbeResult.StatusType.IDENTITY_CONFLICT, "SSH_HOST_TRUST_FAILED",
                RecoveryProbeResult.StatusType.IDENTITY_CONFLICT, "SERVER_CREDENTIAL_MISSING",
                RecoveryProbeResult.StatusType.CREDENTIAL_UNAVAILABLE, "RESCUE_SSH_CLEANUP",
                RecoveryProbeResult.StatusType.FAILED);
        expected.forEach((event, status) -> {
            var original = problem(ProblemCode.IO_FAILURE, event);
            var result = RecoveryProbeResult.failed(original);
            assertSame(original, result.failure().orElseThrow());
            assertEquals(status, result.status());
            assertEquals(RecoveryProbeResult.StatusType.INTERRUPTED,
                    RecoveryProbeResult.failed(problem(ProblemCode.CANCELLED, event)).status());
        });
        var browser = new Browser();
        var original = problem(ProblemCode.IO_FAILURE, "SSH_AUTHENTICATION_FAILED");
        var session = new BrowserRecoverySession(browser, null, () -> RecoveryProbeResult.failed(original), s -> {
        }, TARGET, new ReentrantLock());
        session.run();
        assertSame(original, session.snapshot().failure().orElseThrow());
        assertEquals(0, browser.opens);
        assertTrue(browser.closed);
    }

    @Test
    void journalFailurePreservesWrappedProblemWithoutCopyingExceptionText() throws Exception {
        var original = problem(ProblemCode.IO_FAILURE, "DATABASE_COMMIT_UNCONFIRMED");
        var browser = new Browser();
        var session = new BrowserRecoverySession(browser, null, RecoveryProbeResult::recovered, s -> {
            throw new IllegalStateException("terminal-password=private", new ApiException(original));
        }, TARGET, new ReentrantLock());
        session.run();
        assertEquals(RecoverySnapshot.State.FAILED, session.await().state());
        assertEquals("journalUnavailable", session.snapshot().messageCode());
        assertSame(original, session.snapshot().failure().orElseThrow());
        assertTrue(browser.closed);
    }

    @Test
    void cleanupRetainsOriginalBrowserProblemAndDoesNotReportRecovered() throws Exception {
        var original = new BrowserRecoveryException(BrowserRecoveryException.Reason.CLEANUP_FAILED,
                new IllegalStateException("private terminal output"));
        var browser = new Browser() {
            @Override
            public void close() {
                throw original;
            }
        };
        var session = new BrowserRecoverySession(browser, null, RecoveryProbeResult::recovered, s -> {
        }, TARGET, new ReentrantLock());
        session.run();
        assertSame(original.problem(), session.await().failure().orElseThrow());
        assertEquals(RecoverySnapshot.State.FAILED, session.snapshot().state());
        assertEquals("cleanupIncomplete", session.snapshot().messageCode());
    }

    @Test
    void modelFailureRetainsOriginalProblemAndCooperativeCancellationKeepsOriginalIdentity() throws Exception {
        var original = problem(ProblemCode.UNKNOWN, "AI_TRANSPORT_UNKNOWN");
        var browser = new Browser();
        var cancelled = new AtomicBoolean();
        var id = UUID.randomUUID();
        var task = new TaskContext() {
            public UUID id() {
                return id;
            }

            public CancellationToken cancellation() {
                return cancelled::get;
            }

            public void progress(String text, Optional<Double> fraction) {
                fail("No task progress in core");
            }

            public ResourceLease retain(String kind, String localId, String description) {
                throw new AssertionError("Physical ownership belongs to the browser adapter");
            }
        };
        var models = new RecoveryModels() {
            public Result observe(TerminalObservation observation) throws Exception {
                throw new ApiException(original);
            }

            public Result decide(String observation) {
                throw new AssertionError("No proposal after uncertain response");
            }
        };
        var session = new BrowserRecoverySession(browser, models,
                () -> RecoveryProbeResult.failed(problem(ProblemCode.IO_FAILURE, "SSH_CONNECTION_FAILED")), s -> {
                }, TARGET, task);
        var worker = new Thread(session);
        worker.start();
        try {
            until(() -> session.snapshot().state() == RecoverySnapshot.State.WAITING_TERMINAL);
            session.bindTerminal("terminal", "server", true, false);
            until(() -> session.snapshot().state() == RecoverySnapshot.State.INTERRUPTED);
            assertSame(original, session.snapshot().failure().orElseThrow());
            cancelled.set(true);
            session.awaitCleanup(5000);
            assertEquals(id.toString(), session.snapshot().id());
            assertEquals(RecoverySnapshot.State.CANCELLED, session.snapshot().state());
            assertSame(original, session.snapshot().failure().orElseThrow());
            assertTrue(browser.closed);
        } finally {
            session.close();
            worker.join(5000);
            assertFalse(worker.isAlive());
        }
    }

    private static void until(java.util.function.BooleanSupplier predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!predicate.getAsBoolean() && System.nanoTime() < deadline)
            Thread.sleep(10);
        assertTrue(predicate.getAsBoolean());
    }

    private static class Browser implements BrowserTerminalSession {
        int opens;

        boolean closed;
        public void open() {
            opens++;
        }

        public boolean available() {
            return true;
        }

        public List<TerminalTarget> targets() {
            return List.of(new TerminalTarget("terminal", "fixture"));
        }

        public long bind(String id) {
            return 1;
        }

        public TerminalObservation observe(long generation) {
            return new TerminalObservation("text", new byte[0], 1);
        }

        public void submit(long generation, String command) {
            throw new AssertionError("No command approved");
        }

        public boolean valid(long generation) {
            return generation == 1;
        }

        public void invalidate() {
        }

        public void close() {
            closed = true;
        }
    }
}
