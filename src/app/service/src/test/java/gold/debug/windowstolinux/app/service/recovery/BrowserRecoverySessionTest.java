package gold.debug.windowstolinux.app.service.recovery;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.StoredAiProviderProfile;
import gold.debug.windowstolinux.app.service.ai.RecoveryAiUseCase;
import gold.debug.windowstolinux.app.service.contract.definition.RecoverySnapshot;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.app.windows.recovery.BrowserTerminalSession;
import gold.debug.windowstolinux.shared.ai.recovery.RecoveryModelClient;
import gold.debug.windowstolinux.shared.ai.transport.RoleChatResult;
import gold.debug.windowstolinux.shared.model.recovery.*;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.app.service.contract.definition.RecoverySnapshot.State.*;

class BrowserRecoverySessionTest {
    @TempDir Path directory;
    static class Browser implements BrowserTerminalSession {
        volatile long generation;
        volatile boolean closed;
        volatile int submitted;
        volatile String output = "ID=alpine; init=OpenRC; /bin/ash $";
        Runnable afterSubmit = () -> {};
        @Override public void open() { }
        @Override public boolean available() { return !closed; }
        @Override public List<TerminalTarget> targets() { return List.of(new TerminalTarget("terminal", "terminal")); }
        @Override public long bind(String id) { return ++generation; }
        @Override public TerminalObservation observe(long g) { if (!valid(g)) throw new IllegalStateException(); return new TerminalObservation(output, new byte[0], generation); }
        @Override public void submit(long g, String command) {
            if (!valid(g)) throw new IllegalStateException(); submitted++;
            var matcher = java.util.regex.Pattern.compile("WTL_DONE_[a-f0-9]+").matcher(command);
            assertTrue(matcher.find()); output = matcher.group() + ":0"; afterSubmit.run();
        }
        @Override public boolean valid(long g) { return !closed && g == generation; }
        @Override public void invalidate() { generation++; }
        @Override public void close() { closed = true; }
    }
    private BrowserRecoverySession create(DesktopPersistence db, Browser browser, AtomicReference<String> ssh, ReentrantLock lock) throws Exception {
        return create(db, browser, ssh, lock, System::nanoTime, () -> {});
    }
    private BrowserRecoverySession create(DesktopPersistence db, Browser browser, AtomicReference<String> ssh,
            ReentrantLock lock, java.util.function.LongSupplier clock, Runnable onDecision) throws Exception {
        var secrets = new DesktopSecretStoreService(db.encryptedSecrets());
        try (var store = secrets.open(CredentialStorageMode.MASTER_PASSWORD, "master-password".toCharArray())) { store.save("ai/key", "api-key".toCharArray()); }
        db.aiProfiles().saveVerified(new StoredAiProviderProfile("regular", "https://example.test/v1/chat/completions", "regular", "ai/key", "MASTER_PASSWORD"),"regular",java.time.Instant.now());
        db.aiProfiles().purposes().save(gold.debug.windowstolinux.shared.model.ai.AiPurposeType.DEPLOYMENT,java.util.List.of(new gold.debug.windowstolinux.shared.model.ai.AiPurposeAssignment("regular",true)));
        var ai = new RecoveryAiUseCase(db.aiProfiles(), secrets, new RecoveryModelClient((u,k,b) -> { onDecision.run(); return new RoleChatResult(200,
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"command\\\":\\\"uname -a\\\",\\\"reason\\\":\\\"inspect\\\",\\\"expected\\\":\\\"hello\\\",\\\"highImpact\\\":false}\"}}]}"); }));
        return new BrowserRecoverySession(browser, ai, () -> probeResult(ssh.get()), ignored -> {},
                new ServerProfile("server", "127.0.0.1", 22, "user", "ai/key", CredentialStorageMode.MASTER_PASSWORD), "master-password".toCharArray(), lock, clock);
    }
    private static RecoveryProbeResult probeResult(String code) {
        if (code.equals("recovered")) return RecoveryProbeResult.recovered();
        var type = switch (code) {
            case "connectionFailed" -> gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType.CONNECTION_FAILED;
            case "authentication" -> gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType.AUTHENTICATION_FAILED;
            case "identityConflict" -> gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType.HOST_KEY_REJECTED;
            default -> throw new IllegalArgumentException(code);
        };
        return RecoveryProbeResult.failed(gold.debug.windowstolinux.shared.linux.error.LinuxOperationException.create(type, "test probe"));
    }
    @Test void journalFailureCannotPreventCompletionOrExposeItsRawMessage() throws Exception {
        var browser = new Browser();
        var session = new BrowserRecoverySession(browser, null, RecoveryProbeResult::recovered,
                snapshot -> { throw new IllegalStateException("terminal-password=never-persist"); },
                new ServerProfile("server", "example.test", 22, "user", "key", CredentialStorageMode.MASTER_PASSWORD),
                new char[0], new ReentrantLock());
        session.run();
        var end = session.await();
        assertEquals(FAILED, end.state()); assertTrue(browser.closed);
        assertEquals("persistence.recovery.journal-failed", end.failure().orElseThrow().code());
        assertFalse(end.failure().orElseThrow().diagnostic().contains("never-persist"));
        assertTrue(end.action().isEmpty());
    }
    @Test void interruptedProbeEndsBeforeRetryAndCleanupErrorsKeepTheirModule() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var browser = new Browser() {
            @Override public void close() { super.close(); throw new gold.debug.windowstolinux.app.windows.recovery.BrowserRecoveryException(
                    gold.debug.windowstolinux.app.windows.recovery.BrowserRecoveryFailureType.CLEANUP_FAILED, null); }
        };
        var session = new BrowserRecoverySession(browser, null,
                () -> { calls.incrementAndGet(); return RecoveryProbeResult.failed(new InterruptedException()); }, ignored -> {},
                new ServerProfile("server", "example.test", 22, "user", "key", CredentialStorageMode.MASTER_PASSWORD),
                new char[0], new ReentrantLock());
        session.run();
        assertEquals(1, calls.get()); assertTrue(browser.closed);
        assertEquals("windows.browser.cleanup-failed", session.await().failure().orElseThrow().code());
    }
    private void until(java.util.function.BooleanSupplier predicate) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!predicate.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        assertTrue(predicate.getAsBoolean(), "state transition timed out");
    }
    private void bind(BrowserRecoverySession s) throws Exception { until(() -> s.snapshot().state() == WAITING_TERMINAL); s.bindTerminal("terminal", "server", true, false); until(() -> s.snapshot().state() == AWAITING_CONFIRMATION || s.snapshot().state() == INTERRUPTED); assertEquals(AWAITING_CONFIRMATION, s.snapshot().state(), s.snapshot().messageCode()); }
    @Test void approvedActionExecutesOnceAndSshRecoveryStopsFurtherCommands() throws Exception {
        var browser = new Browser(); var ssh = new AtomicReference<>("connectionFailed"); browser.afterSubmit = () -> ssh.set("recovered");
        try (var db = DesktopPersistence.open(directory); var s = create(db, browser, ssh, new ReentrantLock())) {
            var worker = new Thread(s); worker.start(); bind(s); String token = s.snapshot().confirmation();
            assertEquals(0, browser.submitted); s.confirmAction(token, false); s.confirmAction(token, false);
            RecoverySnapshot end = s.await(); worker.join(2000);
            assertEquals(RECOVERED, end.state()); assertFalse(end.resultUnknown()); assertEquals(1, browser.submitted); assertTrue(browser.closed); assertFalse(worker.isAlive());
        }
    }
    @Test void pauseRevokesConfirmationAndRequiresIdentityConsentAndFreshHandoff() throws Exception {
        var browser = new Browser(); var ssh = new AtomicReference<>("connectionFailed");
        try (var db = DesktopPersistence.open(directory); var s = create(db, browser, ssh, new ReentrantLock())) {
            var worker = new Thread(s); worker.start(); bind(s); String old = s.snapshot().confirmation();
            s.pause(); s.confirmAction(old, false); until(() -> s.snapshot().state() == PAUSED);
            assertThrows(IllegalArgumentException.class, () -> s.bindTerminal("terminal", "other-server", true, false));
            assertThrows(IllegalArgumentException.class, () -> s.bindTerminal("terminal", "server", false, false));
            s.resume(); bind(s); assertNotEquals(old, s.snapshot().confirmation());
            s.confirmAction(old, false); Thread.sleep(500); assertEquals(0, browser.submitted);
            s.close(); s.await(); worker.join(2000); assertFalse(worker.isAlive());
        }
    }
    @Test void disconnectAfterSubmissionPreservesUnknownAndDoesNotReplay() throws Exception {
        var browser = new Browser(); var ssh = new AtomicReference<>("connectionFailed"); browser.afterSubmit = () -> browser.generation++;
        try (var db = DesktopPersistence.open(directory); var s = create(db, browser, ssh, new ReentrantLock())) {
            var worker = new Thread(s); worker.start(); bind(s); String token = s.snapshot().confirmation(); s.confirmAction(token, false);
            until(() -> s.snapshot().state() == INTERRUPTED); assertTrue(s.snapshot().resultUnknown());
            s.confirmAction(token, true); s.bindTerminal("terminal", "server", true, false); Thread.sleep(500);
            assertEquals(1, browser.submitted); assertEquals(INTERRUPTED, s.snapshot().state());
            s.close(); s.await(); worker.join(2000); assertFalse(worker.isAlive());
        }
    }
    @Test void authenticationAndServerMutexStopBeforeBrowserHandoff() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            var browser = new Browser(); var s = create(db, browser, new AtomicReference<>("authentication"), new ReentrantLock());
            s.run(); assertEquals(FAILED, s.snapshot().state()); assertEquals("authentication", s.snapshot().messageCode());
            var lock = new ReentrantLock(); lock.lock();
            try {
                var busy = create(db, new Browser(), new AtomicReference<>("connectionFailed"), lock); var worker = new Thread(busy); worker.start();
                assertEquals("serverBusy", busy.await().messageCode()); worker.join(2000);
            } finally { lock.unlock(); }
        }
    }

    @Test void secondInitialProbeIdentityFailureEndsTheSession() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var browser = new Browser();
        char[] password = "test-only".toCharArray();
        var session = new BrowserRecoverySession(browser, null,
                () -> probeResult(attempts.incrementAndGet() == 1 ? "connectionFailed" : "identityConflict"), ignored -> {},
                new ServerProfile("server", "127.0.0.1", 22, "user", "key", CredentialStorageMode.MASTER_PASSWORD),
                password, new ReentrantLock());
        session.run();
        assertEquals(FAILED, session.await().state());
        assertEquals("identityConflict", session.snapshot().messageCode());
        assertTrue(browser.closed);
        assertEquals(0, browser.submitted);
        for (char value : password) assertEquals('\0', value);
    }

    @Test void userWaitDoesNotConsumeActiveBudgetButModelWorkDoes() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicLong(1);
        try (var db = DesktopPersistence.open(directory)) {
            var waiting = create(db, new Browser(), new AtomicReference<>("connectionFailed"), new ReentrantLock(), time::get, () -> {});
            var worker = new Thread(waiting); worker.start();
            try {
                bind(waiting); time.addAndGet(TimeUnit.MINUTES.toNanos(16)); Thread.sleep(500);
                assertEquals(AWAITING_CONFIRMATION, waiting.snapshot().state());
            } finally { waiting.close(); waiting.await(); worker.join(2000); }
            var active = create(db, new Browser(), new AtomicReference<>("connectionFailed"), new ReentrantLock(), time::get,
                    () -> time.addAndGet(TimeUnit.MINUTES.toNanos(16)));
            worker = new Thread(active); worker.start();
            try {
                until(() -> active.snapshot().state() == WAITING_TERMINAL); active.bindTerminal("terminal", "server", true, false);
                until(() -> active.snapshot().state() == INTERRUPTED); assertEquals("budgetReached", active.snapshot().messageCode());
            } finally { active.close(); active.await(); worker.join(2000); }
        }
    }
    @Test void thirtiethDecisionCanCompleteButDoesNotProduceAnotherProposal() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicLong(1); var browser = new Browser();
        try (var db = DesktopPersistence.open(directory); var s = create(db, browser, new AtomicReference<>("connectionFailed"),
                new ReentrantLock(), time::get, () -> {})) {
            var worker = new Thread(s); worker.start();
            try {
                bind(s);
                for (int i = 0; i < 30; i++) {
                    String token = s.snapshot().confirmation(); time.addAndGet(TimeUnit.SECONDS.toNanos(3)); s.confirmAction(token, false);
                    until(() -> s.snapshot().state() == INTERRUPTED || (s.snapshot().state() == AWAITING_CONFIRMATION && !s.snapshot().confirmation().equals(token)));
                }
                assertEquals(INTERRUPTED, s.snapshot().state()); assertEquals("budgetReached", s.snapshot().messageCode());
                assertEquals(30, s.snapshot().decisions()); assertEquals(30, browser.submitted);
            } finally { s.close(); s.await(); worker.join(2000); }
        }
    }
}
