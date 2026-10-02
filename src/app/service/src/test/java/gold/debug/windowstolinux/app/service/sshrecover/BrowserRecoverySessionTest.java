package gold.debug.windowstolinux.app.service.sshrecover;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;

import static gold.debug.windowstolinux.app.linux.ssh.recover.BrowserRecoverySnapshot.State.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import gold.debug.windowstolinux.app.linux.ssh.recover.BrowserRecoveryException;
import gold.debug.windowstolinux.app.linux.ssh.recover.BrowserRecoverySession;
import gold.debug.windowstolinux.app.linux.ssh.recover.RecoveryModels;
import gold.debug.windowstolinux.app.linux.ssh.recover.RecoveryProbeResult;
import gold.debug.windowstolinux.app.linux.ssh.recover.RecoveryTarget;
import gold.debug.windowstolinux.app.linux.ssh.recover.TerminalObservation;
import gold.debug.windowstolinux.app.linux.ssh.recover.TerminalTarget;
import gold.debug.windowstolinux.app.linux.ssh.recover.BrowserTerminalSession;
import gold.debug.windowstolinux.app.linux.ssh.recover.BrowserRecoverySnapshot;

import org.junit.jupiter.api.Test;

class BrowserRecoverySessionTest {
    static class Browser implements BrowserTerminalSession {
        volatile long generation;

        volatile boolean closed;

        volatile int submitted;

        volatile String output = "ID=alpine; init=OpenRC; /bin/ash $";

        Runnable afterSubmit = () -> {
        };

        @Override
        public void open() {
        }

        @Override
        public boolean available() {
            return !closed;
        }

        @Override
        public List<TerminalTarget> targets() {
            return List.of(new TerminalTarget("terminal", "terminal"));
        }

        @Override
        public long bind(String id) {
            return ++generation;
        }

        @Override
        public TerminalObservation observe(long g) {
            if (!valid(g))
                throw new IllegalStateException();
            return new TerminalObservation(output, new byte[0], generation);
        }

        @Override
        public void submit(long g, String command) {
            if (!valid(g))
                throw new IllegalStateException();
            submitted++;
            var matcher = java.util.regex.Pattern.compile("WTL_DONE_[a-f0-9]+").matcher(command);
            assertTrue(matcher.find());
            output = matcher.group() + ":0";
            afterSubmit.run();
        }

        @Override
        public boolean valid(long g) {
            return !closed && g == generation;
        }

        @Override
        public void invalidate() {
            generation++;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private BrowserRecoverySession create(Browser browser, AtomicReference<String> ssh,
                                          ReentrantLock lock) throws Exception {
        return create(browser, ssh, lock, System::nanoTime, () -> {
        });
    }

    private BrowserRecoverySession create(Browser browser, AtomicReference<String> ssh,
                                          ReentrantLock lock, java.util.function.LongSupplier clock, Runnable onDecision) throws Exception {
        // This suite verifies the controller. Real host authorization and model transport remain in RecoveryGraphTest/PlatformAiTest.
        var ai = new RecoveryModels() {
            public Result observe(TerminalObservation observation) {
                return new Result("ok", observation.text(), Map.of());
            }

            public Result decide(String observation) {
                onDecision.run();
                return new Result("ok", "{\"command\":\"uname -a\",\"reason\":\"inspect\",\"expected\":\"hello\",\"highImpact\":false}", Map.of());
            }
        };
        return new BrowserRecoverySession(browser, ai, () -> probeResult(ssh.get()), ignored -> {
        }, new RecoveryTarget("server", "127.0.0.1", 22), lock, clock);
    }

    private static RecoveryProbeResult probeResult(String code) {
        if (code.equals("recovered"))
            return RecoveryProbeResult.recovered();
        String event = switch (code) {
            case "connectionFailed" -> "SSH_CONNECTION_FAILED";
            case "authentication" -> "SSH_AUTHENTICATION_FAILED";
            case "identityConflict" -> "SSH_HOST_KEY_REJECTED";
            default -> throw new IllegalArgumentException(code);
        };
        return RecoveryProbeAdapter.failed(new ApiException(new Problem(
            code.equals("connectionFailed") ? ProblemCode.IO_FAILURE : ProblemCode.ACCESS_DENIED,
            event, "test probe", Map.of(), Optional.of(UUID.randomUUID()))));
    }

    private Thread worker(Runnable session) {
        return new Thread(session);
    }

    @Test
    void failedPreDispatchJournalPreventsApprovedTerminalInput() throws Exception {
        var browser = new Browser();
        var models = new RecoveryModels() {
            public Result observe(TerminalObservation observation) {
                return new Result("ok", observation.text(), Map.of());
            }

            public Result decide(String observation) {
                return new Result("ok",
                    "{\"command\":\"uname -a\",\"reason\":\"inspect\",\"expected\":\"facts\",\"highImpact\":false}",
                    Map.of());
            }
        };
        var submittedJournal = new java.util.concurrent.atomic.AtomicInteger();
        var session = new BrowserRecoverySession(browser, models, () -> probeResult("connectionFailed"), snapshot -> {
            if (snapshot.messageCode().equals("submitted")) {
                submittedJournal.incrementAndGet();
                throw new IllegalStateException("journal failed before terminal input");
            }
        }, new RecoveryTarget("server", "example.test", 22), new ReentrantLock());
        var worker = new Thread(session);
        try (session) {
            worker.start();
            bind(session);
            session.confirmAction(session.snapshot().confirmation(), false);
            session.awaitCleanup(5000);
            assertEquals(FAILED, session.snapshot().state());
            assertEquals("journalUnavailable", session.snapshot().messageCode());
            assertEquals(1, submittedJournal.get());
            assertEquals(0, browser.submitted);
            assertTrue(browser.closed);
        } finally {
            session.close();
            worker.join(5000);
            assertFalse(worker.isAlive());
        }
    }

    @Test
    void journalFailureCannotPreventCompletionOrExposeItsRawMessage() throws Exception {
        var browser = new Browser();
        var session = new BrowserRecoverySession(browser, null, RecoveryProbeResult::recovered, snapshot -> {
            throw new IllegalStateException("terminal-password=never-persist");
        }, new RecoveryTarget("server", "example.test", 22), new ReentrantLock());
        session.run();
        var end = session.await();
        assertEquals(FAILED, end.state());
        assertTrue(browser.closed);
        assertEquals("RESCUE_JOURNAL", end.failure().orElseThrow().eventCode());
        assertFalse(end.failure().orElseThrow().message().contains("never-persist"));
        assertTrue(end.action().isEmpty());
    }

    @Test
    void interruptedProbeEndsBeforeRetryAndCleanupErrorsKeepTheirModule() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var browser = new Browser() {
            @Override
            public void close() {
                super.close();
                throw new gold.debug.windowstolinux.app.linux.ssh.recover.BrowserRecoveryException(
                    gold.debug.windowstolinux.app.linux.ssh.recover.BrowserRecoveryException.ReasonType.CLEANUP_FAILED,
                    null);
            }
        };
        var session = new BrowserRecoverySession(browser, null, () -> {
            calls.incrementAndGet();
            return RecoveryProbeAdapter.failed(new InterruptedException());
        }, ignored -> {
        }, new RecoveryTarget("server", "example.test", 22), new ReentrantLock());
        session.run();
        assertEquals(1, calls.get());
        assertTrue(browser.closed);
        assertEquals("linux.browser.cleanup-failed", session.await().failure().orElseThrow().eventCode());
    }

    private void until(java.util.function.BooleanSupplier predicate) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!predicate.getAsBoolean() && System.nanoTime() < end)
            Thread.sleep(20);
        assertTrue(predicate.getAsBoolean(), "state transition timed out");
    }

    private void bind(BrowserRecoverySession s) throws Exception {
        until(() -> s.snapshot().state() == WAITING_TERMINAL);
        s.bindTerminal("terminal", "server", true, false);
        until(() -> s.snapshot().state() == AWAITING_CONFIRMATION || s.snapshot().state() == INTERRUPTED);
        assertEquals(AWAITING_CONFIRMATION, s.snapshot().state(), s.snapshot().messageCode());
    }

    @Test
    void approvedActionExecutesOnceAndSshRecoveryStopsFurtherCommands() throws Exception {
        var browser = new Browser();
        var ssh = new AtomicReference<>("connectionFailed");
        browser.afterSubmit = () -> ssh.set("recovered");
        try (var s = create(browser, ssh, new ReentrantLock())) {
            var worker = worker(s);
            worker.start();
            bind(s);
            String token = s.snapshot().confirmation();
            assertEquals(0, browser.submitted);
            s.confirmAction(token, false);
            s.confirmAction(token, false);
            BrowserRecoverySnapshot end = s.await();
            worker.join(2000);
            assertEquals(RECOVERED, end.state());
            assertFalse(end.resultUnknown());
            assertEquals(1, browser.submitted);
            assertTrue(browser.closed);
            assertFalse(worker.isAlive());
        }
    }

    @Test
    void pauseRevokesConfirmationAndRequiresIdentityConsentAndFreshHandoff() throws Exception {
        var browser = new Browser();
        var ssh = new AtomicReference<>("connectionFailed");
        try (var s = create(browser, ssh, new ReentrantLock())) {
            var worker = worker(s);
            worker.start();
            bind(s);
            String old = s.snapshot().confirmation();
            s.pause();
            s.confirmAction(old, false);
            until(() -> s.snapshot().state() == PAUSED);
            assertThrows(IllegalArgumentException.class, () -> s.bindTerminal("terminal", "other-server", true, false));
            assertThrows(IllegalArgumentException.class, () -> s.bindTerminal("terminal", "server", false, false));
            s.resume();
            bind(s);
            assertNotEquals(old, s.snapshot().confirmation());
            s.confirmAction(old, false);
            Thread.sleep(500);
            assertEquals(0, browser.submitted);
            s.close();
            s.await();
            worker.join(2000);
            assertFalse(worker.isAlive());
        }
    }

    @Test
    void disconnectAfterSubmissionPreservesUnknownAndDoesNotReplay() throws Exception {
        var browser = new Browser();
        var ssh = new AtomicReference<>("connectionFailed");
        browser.afterSubmit = () -> browser.generation++;
        try (var s = create(browser, ssh, new ReentrantLock())) {
            var worker = worker(s);
            worker.start();
            bind(s);
            String token = s.snapshot().confirmation();
            s.confirmAction(token, false);
            until(() -> s.snapshot().state() == INTERRUPTED);
            assertTrue(s.snapshot().resultUnknown());
            s.confirmAction(token, true);
            s.bindTerminal("terminal", "server", true, false);
            Thread.sleep(500);
            assertEquals(1, browser.submitted);
            assertEquals(INTERRUPTED, s.snapshot().state());
            s.close();
            s.await();
            worker.join(2000);
            assertFalse(worker.isAlive());
        }
    }

    @Test
    void authenticationAndServerMutexStopBeforeBrowserHandoff() throws Exception {
        {
            var browser = new Browser();
            var s = create(browser, new AtomicReference<>("authentication"), new ReentrantLock());
            s.run();
            assertEquals(FAILED, s.snapshot().state());
            assertEquals("authentication", s.snapshot().messageCode());
            var lock = new ReentrantLock();
            lock.lock();
            try {
                var busy = create(new Browser(), new AtomicReference<>("connectionFailed"), lock);
                var worker = worker(busy);
                worker.start();
                assertEquals("serverBusy", busy.await().messageCode());
                worker.join(2000);
            } finally {
                lock.unlock();
            }
        }
    }

    @Test
    void secondInitialProbeIdentityFailureEndsTheSession() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var browser = new Browser();
        char[] password = "test-only".toCharArray();
        var session = new BrowserRecoverySession(browser, null,
            () -> probeResult(attempts.incrementAndGet() == 1 ? "connectionFailed" : "identityConflict"),
            ignored -> {
            }, new RecoveryTarget("server", "127.0.0.1", 22), new ReentrantLock());
        session.run();
        assertEquals(FAILED, session.await().state());
        assertEquals("identityConflict", session.snapshot().messageCode());
        assertTrue(browser.closed);
        assertEquals(0, browser.submitted);

    }

    @Test
    void userWaitDoesNotConsumeActiveBudgetButModelWorkDoes() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicLong(1);
        {
            var waiting = create(new Browser(), new AtomicReference<>("connectionFailed"), new ReentrantLock(),
                time::get, () -> {
                });
            var worker = worker(waiting);
            worker.start();
            try {
                bind(waiting);
                time.addAndGet(TimeUnit.MINUTES.toNanos(16));
                Thread.sleep(500);
                assertEquals(AWAITING_CONFIRMATION, waiting.snapshot().state());
            } finally {
                waiting.close();
                waiting.await();
                worker.join(2000);
            }
            var active = create(new Browser(), new AtomicReference<>("connectionFailed"), new ReentrantLock(),
                time::get, () -> time.addAndGet(TimeUnit.MINUTES.toNanos(16)));
            worker = worker(active);
            worker.start();
            try {
                until(() -> active.snapshot().state() == WAITING_TERMINAL);
                active.bindTerminal("terminal", "server", true, false);
                until(() -> active.snapshot().state() == INTERRUPTED);
                assertEquals("budgetReached", active.snapshot().messageCode());
            } finally {
                active.close();
                active.await();
                worker.join(2000);
            }
        }
    }

    @Test
    void thirtiethDecisionCanCompleteButDoesNotProduceAnotherProposal() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicLong(1);
        var browser = new Browser();
        try (var s = create(browser, new AtomicReference<>("connectionFailed"), new ReentrantLock(), time::get,
            () -> {
            })) {
            var worker = worker(s);
            worker.start();
            try {
                bind(s);
                for (int i = 0; i < 30; i++) {
                    String token = s.snapshot().confirmation();
                    time.addAndGet(TimeUnit.SECONDS.toNanos(3));
                    s.confirmAction(token, false);
                    until(() -> s.snapshot().state() == INTERRUPTED || (s.snapshot().state() == AWAITING_CONFIRMATION
                        && !s.snapshot().confirmation().equals(token)));
                }
                assertEquals(INTERRUPTED, s.snapshot().state());
                assertEquals("budgetReached", s.snapshot().messageCode());
                assertEquals(30, s.snapshot().decisions());
                assertEquals(30, browser.submitted);
            } finally {
                s.close();
                s.await();
                worker.join(2000);
            }
        }
    }
}
