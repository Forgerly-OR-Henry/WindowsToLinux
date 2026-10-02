package gold.debug.windowstolinux.app.service.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.PlatformPersistence;
import gold.debug.windowstolinux.app.db.init.DatabaseProtection;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.error.ApplicationServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSecurityServiceTest {
    @TempDir
    Path directory;

    private PlatformPersistence openPlatform() throws Exception {
        var testProtection = new DatabaseProtection() {
            public byte[] protect(byte[] clear) {
                return clear.clone();
            }

            public byte[] unprotect(byte[] encrypted) {
                return encrypted.clone();
            }
        };
        return new PlatformPersistence(PlatformDatabase.open(directory, testProtection));
    }

    private char[] password() {
        return "testpass".toCharArray();
    }

    private void initialize(DesktopSecurityService security) throws Exception {
        security.setupAndUnlock(password(), password());
    }

    @Test
    void setupAndEveryNewServiceStartLocked() throws Exception {
        try (var db = openPlatform(); var security = new DesktopSecurityService(db.security())) {
            assertFalse(security.securityState().unlocked());
            assertFalse(security.securityState().configured());
            assertThrows(ApplicationServiceException.class, security::beginTask);
            assertThrows(ApplicationServiceException.class,
                () -> security.setupAndUnlock("1234567".toCharArray(), "1234567".toCharArray()));
            assertFalse(security.securityState().configured());
            char[] first = password(), second = "does not match".toCharArray();
            assertThrows(ApplicationServiceException.class, () -> security.setupAndUnlock(first, second));
            assertArrayEquals(new char[first.length], first);
            assertArrayEquals(new char[second.length], second);
            initialize(security);
            assertTrue(security.securityState().unlocked());
            security.lock();
            assertFalse(security.securityState().unlocked());
            assertThrows(SecretStoreException.class, () -> security.unlock("wrong password".toCharArray()));
            security.unlock(password());
        }
        try (var db = openPlatform(); var security = new DesktopSecurityService(db.security())) {
            assertTrue(security.securityState().configured());
            assertFalse(security.securityState().unlocked());
        }
    }

    @Test
    void acceptedTasksRetainIndependentCapabilitiesUntilLastClose() throws Exception {
        try (var db = openPlatform(); var security = new DesktopSecurityService(db.security())) {
            initialize(security);
            try (var operation = security.enterOperation(); var store = security.openCredentials()) {
                store.save("ssh/test", "secret".toCharArray());
            }
            var first = security.beginTask();
            var second = security.beginTask();
            security.lock();
            assertThrows(ApplicationServiceException.class, security::beginTask);
            assertThrows(ApplicationServiceException.class, security::openCredentials);
            assertEquals(2, security.securityState().activeTasks());
            first.execute(() -> {
                try (var store = security.openCredentials()) {
                    assertArrayEquals("secret".toCharArray(), store.read("ssh/test").orElseThrow());
                }
                return null;
            });
            first.close();
            assertEquals(1, security.securityState().activeTasks());
            second.execute(() -> {
                try (var store = security.openCredentials()) {
                    assertTrue(store.read("ssh/test").isPresent());
                }
                return null;
            });
            second.close();
            assertEquals(0, security.securityState().activeTasks());
            assertFalse(security.securityState().unlocked());
        }
    }

    @Test
    void humanDecisionWaitsForUnlockAndRechecksAfterLockDuringPrompt() throws Exception {
        try (var db = openPlatform(); var security = new DesktopSecurityService(db.security())) {
            initialize(security);
            var worker = Executors.newSingleThreadExecutor();
            try (var task = security.beginTask()) {
                AtomicInteger prompts = new AtomicInteger();
                CountDownLatch pending = new CountDownLatch(1);
                var confirmation = security.<String>confirm(value -> {
                    int count = prompts.incrementAndGet();
                    if (count == 1) {
                        security.lock();
                        pending.countDown();
                    }
                    return count > 1;
                });
                Future<Boolean> result = worker.submit(() -> task.execute(() -> confirmation.test("same-action")));
                assertTrue(pending.await(3, TimeUnit.SECONDS));
                assertFalse(result.isDone());
                assertEquals(1, prompts.get());
                security.unlock(password());
                assertTrue(result.get(5, TimeUnit.SECONDS));
                assertEquals(2, prompts.get());
            } finally {
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void securityChangesAndTaskStartAreMutuallyExclusive() throws Exception {
        try (var db = openPlatform(); var security = new DesktopSecurityService(db.security())) {
            initialize(security);
            try (var task = security.beginTask()) {
                char[] old = password(), replacement = "replacement-password".toCharArray(),
                    repeat = replacement.clone();
                assertThrows(ApplicationServiceException.class,
                    () -> security.changeMasterPassword(old, replacement, repeat));
                assertArrayEquals(new char[old.length], old);
                assertArrayEquals(new char[replacement.length], replacement);
                assertThrows(ApplicationServiceException.class,
                    () -> security.setCredentialEncryption(password(), false));
            }
            assertThrows(ApplicationServiceException.class,
                () -> security.changeMasterPassword(password(), "1234567".toCharArray(), "1234567".toCharArray()));
            security.changeMasterPassword(password(), "new-pass".toCharArray(), "new-pass".toCharArray());
            security.lock();
            assertThrows(SecretStoreException.class, () -> security.unlock(password()));
            security.unlock("new-pass".toCharArray());
            security.setCredentialEncryption("new-pass".toCharArray(), false);
            assertFalse(security.securityState().encryptionEnabled());
        }
    }

    @Test
    void cancellationReleasesWaitingTaskWithoutApprovingAndCloseWaitsForExecution() throws Exception {
        try (var db = openPlatform(); var security = new DesktopSecurityService(db.security())) {
            initialize(security);
            var worker = Executors.newSingleThreadExecutor();
            var task = security.beginTask();
            CountDownLatch entered = new CountDownLatch(1), finished = new CountDownLatch(1);
            AtomicBoolean called = new AtomicBoolean();
            try {
                security.lock();
                Future<?> future = worker.submit(() -> {
                    try {
                        task.execute(() -> {
                            entered.countDown();
                            return security.<String>confirm(value -> {
                                called.set(true);
                                return true;
                            }).test("action");
                        });
                    } catch (Exception expected) {
                    } finally {
                        task.close();
                        finished.countDown();
                    }
                });
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                task.close();
                assertEquals(1, security.securityState().activeTasks());
                future.cancel(true);
                assertTrue(finished.await(3, TimeUnit.SECONDS));
                assertFalse(called.get());
                assertEquals(0, security.securityState().activeTasks());
            } finally {
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
                task.close();
            }
        }
    }

    @Test
    void concurrentTaskAdmissionPreservesSecurityMutationExclusion() throws Exception {
        try (var db = openPlatform(); var security = new DesktopSecurityService(db.security())) {
            initialize(security);
            var pool = Executors.newFixedThreadPool(2);
            var gate = new java.util.concurrent.CyclicBarrier(2);
            var accepted = new java.util.concurrent.atomic.AtomicReference<DesktopSecurityService.Task>();
            try {
                var task = pool.submit(() -> {
                    gate.await();
                    try {
                        accepted.set(security.beginTask());
                        return true;
                    } catch (ApplicationServiceException denied) {
                        return false;
                    }
                });
                var change = pool.submit(() -> {
                    gate.await();
                    try {
                        security.setCredentialEncryption(password(), false);
                        return true;
                    } catch (ApplicationServiceException denied) {
                        return false;
                    }
                });
                boolean taskWon = task.get(10, TimeUnit.SECONDS), changeWon = change.get(10, TimeUnit.SECONDS);
                assertTrue(taskWon || changeWon);
                assertEquals(!changeWon, security.securityState().encryptionEnabled());
                security.lock();
                assertThrows(ApplicationServiceException.class, security::beginTask);
                if (taskWon)
                    accepted.get().execute(() -> {
                        try (var store = security.openCredentials()) {
                            store.save("race", "retained".toCharArray());
                            assertArrayEquals("retained".toCharArray(), store.read("race").orElseThrow());
                        }
                        return null;
                    });
                assertFalse(security.securityState().unlocked());
            } finally {
                if (accepted.get() != null)
                    accepted.get().close();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            }
            assertEquals(0, security.securityState().activeTasks());
        }
    }

    @Test
    void failedNativePrivacyDoesNotClaimThatTheSessionIsLocked() throws Exception {
        try (var db = openPlatform(); var security = new DesktopSecurityService(db.security())) {
            initialize(security);
            var hidden = new AtomicBoolean();
            try (var registration = security.observeLock(locked -> {
                if (locked)
                    throw new IllegalStateException("native fixture failed");
                hidden.set(locked);
            })) {
                assertThrows(ApplicationServiceException.class, security::lock);
                assertTrue(security.securityState().unlocked());
                assertFalse(hidden.get());
            }
            security.lock();
            assertFalse(security.securityState().unlocked());
        }
    }
}
