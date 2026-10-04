package gold.debug.windowstolinux.app.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.PlatformStorage;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.security.StoredSecret;
import gold.debug.windowstolinux.app.db.security.StoredSecurityProfile;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.app.secret.PasswordKeyCrypto;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretVaultTest {
    @TempDir
    Path directory;

    private char[] password() {
        return "testpass".toCharArray();
    }

    private PlatformStorage open() throws Exception {
        return new PlatformStorage(PlatformDatabase.open(directory, SecretVaultH2Test.TEST_ONLY_PROTECTION));
    }

    @Test
    void setupUnlockChangeAndReferenceLifetimePreserveCredentialCiphertext() throws Exception {
        try (var db = open()) {
            var vault = new SecretVault(db.security());
            assertFalse(vault.configured());
            char[] supplied = password();
            var session = vault.initialize(supplied);
            assertArrayEquals(new char[supplied.length], supplied);
            session.save("ssh/test", "secret value".toCharArray());
            byte[] before;
            try (var stored = db.security().find("ssh/test").orElseThrow()) {
                before = stored.payload();
                assertEquals("AES256_GCM_V1", stored.storageFormat());
            }
            var task = session.fork();
            session.close();
            assertThrows(IllegalStateException.class, () -> session.read("ssh/test"));
            assertArrayEquals("secret value".toCharArray(), task.read("ssh/test").orElseThrow());
            assertThrows(SecretStoreException.class, () -> vault.unlock("incorrect password".toCharArray()));
            vault.changePassword(password(), "new-pass".toCharArray());
            assertThrows(SecretStoreException.class, () -> vault.unlock(password()));
            try (var replacement = vault.unlock("new-pass".toCharArray())) {
                assertArrayEquals("secret value".toCharArray(), replacement.read("ssh/test").orElseThrow());
            }
            try (var stored = db.security().find("ssh/test").orElseThrow()) {
                assertArrayEquals(before, stored.payload());
            }
            task.close();
            assertThrows(IllegalStateException.class, task::fork);
        }
    }

    @Test
    void saltsAreIndependentAndMalformedFormatsFailClosed() throws Exception {
        try (var db = open()) {
            var vault = new SecretVault(db.security());
            try (var ignored = vault.initialize(password())) {
            }
            var profile = db.security().profile().orElseThrow();
            String[] pieces = profile.passwordVerifier().split("\\$");
            assertFalse(Arrays.equals(Base64.getDecoder().decode(pieces[4]), profile.keyDerivationSalt()));
            assertThrows(Exception.class,
                () -> PasswordKeyCrypto.unlock(
                    new StoredSecurityProfile(true, profile.passwordVerifier(), profile.wrappedDataKey(),
                        profile.keyWrapAlgorithm(), profile.keyWrapNonce(),
                        profile.keyDerivationAlgorithm(), profile.keyDerivationSalt(), "unknown", 1),
                    password()));
            try (var invalid = new StoredSecret("a", "UNKNOWN", new byte[]{1}, null)) {
                assertThrows(Exception.class, () -> PasswordKeyCrypto.decode(invalid, new byte[32], true));
            }
            assertThrows(SecretStoreException.class, () -> vault.changePassword(password(), "1234567".toCharArray()));
            try (var ignored = vault.unlock(password())) {
            }
        }
    }

    @Test
    void ciphertextTamperingAndCredentialSubstitutionFailAuthentication() throws Exception {
        try (var db = open()) {
            var vault = new SecretVault(db.security());
            try (var session = vault.initialize(password())) {
                session.save("a", "first secret".toCharArray());
                session.save("b", "second secret".toCharArray());
                try (var c = db.database().openConnection(); var s = c.createStatement()) {
                    s.execute("UPDATE credential SET credential_id='swapped' WHERE credential_id='a'");
                    assertThrows(SecretStoreException.class, () -> session.read("swapped"));
                    try (var stored = db.security().find("b").orElseThrow();
                         var update = c
                             .prepareStatement("UPDATE credential SET payload=? WHERE credential_id='b'")) {
                        update.setBytes(1, new byte[stored.payload().length]);
                        update.executeUpdate();
                    }
                    assertThrows(SecretStoreException.class, () -> session.read("b"));
                }
            }
        }
    }

    @Test
    void encryptionConversionIsAtomicAndDoesNotChangeBusinessRevisions() throws Exception {
        try (var db = open()) {
            var vault = new SecretVault(db.security());
            try (var session = vault.initialize(password())) {
                session.save("a", "first secret".toCharArray());
                session.save("b", "second secret".toCharArray());
                try (var c = db.database().openConnection(); var s = c.createStatement()) {
                    s.execute("INSERT INTO mod_registry(mod_id,updated_at) VALUES('fixture.mod',0)");
                    s.execute(
                        "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at,validated_at) VALUES('fixture.mod','1',RANDOM_UUID(),'fixture_schema','fixture_user','READY',0,0)");
                    s.execute(
                        "INSERT INTO mod_credential_reference SELECT RANDOM_UUID(),'a',mod_id,schema_version,generation,'business','fixture','ACTIVE',0 FROM mod_schema WHERE mod_id='fixture.mod'");
                }
                assertThrows(SecretStoreException.class, () -> session.save("a", "changed".toCharArray()));
                try (var c = db.database().openConnection(); var s = c.createStatement()) {
                    s.execute(
                        "ALTER TABLE credential ADD CONSTRAINT fail_second CHECK(credential_id<>'b' OR storage_format<>'PLAIN_V1')");
                    assertThrows(SecretStoreException.class, () -> vault.setEncryption(password(), false));
                    assertTrue(vault.encryptionEnabled());
                    for (String id : List.of("a", "b"))
                        try (var stored = db.security().find(id).orElseThrow()) {
                            assertEquals("AES256_GCM_V1", stored.storageFormat());
                        }
                    s.execute("ALTER TABLE credential DROP CONSTRAINT fail_second");
                }
                vault.setEncryption(password(), false);
                assertFalse(vault.encryptionEnabled());
                assertArrayEquals("first secret".toCharArray(), session.read("a").orElseThrow());
                try (var stored = db.security().find("a").orElseThrow()) {
                    assertEquals("PLAIN_V1", stored.storageFormat());
                }
                vault.setEncryption(password(), true);
                assertTrue(vault.encryptionEnabled());
                try (var c = db.database().openConnection();
                     var s = c.createStatement();
                     var rows = s.executeQuery(
                         "SELECT credential_id,state FROM mod_credential_reference WHERE business_key='business'")) {
                    assertTrue(rows.next());
                    assertEquals("a", rows.getString(1));
                    assertEquals("ACTIVE", rows.getString(2));
                    assertFalse(rows.next());
                }
                assertArrayEquals("first secret".toCharArray(), session.read("a").orElseThrow());
            }
        }
    }

    @Test
    void credentialAndOwnerWritesRollbackTogether() throws Exception {
        try (var db = open()) {
            var vault = new SecretVault(db.security());
            try (var session = vault.initialize(password())) {
                assertThrows(SQLException.class, () -> session.transaction(() -> {
                    session.save("a", "first secret".toCharArray());
                    db.servers().saveServerProfile(new StoredServerProfile("server", "example.test", 22, "user", "a"));
                    throw new SQLException("simulate failed configuration");
                }));
                assertTrue(db.security().find("a").isEmpty());
                assertTrue(db.servers().findServerProfile("server").isEmpty());
            }
        }
    }

    @Test
    void taskCompletionCanCloseItsForkWhileAnotherForkWritesCredentials() throws Exception {
        try (var db = open();
             var writer = new SecretVault(db.security()).initialize(password());
             var finishing = writer.fork()) {
            var ledger = new TaskLedger(db.database());
            var task = ledger.enqueue(UUID.randomUUID(), "fixture.secret", "1.0.0",
                new TaskRequest("credential-close", "Credential close", List.of(), true),
                List.of(new TaskResource("MOD", "fixture.secret", "fixture.secret", false)));
            assertTrue(ledger.tryStart(task));
            var beforeEndLocked = new CountDownLatch(1);
            var writerInTransaction = new CountDownLatch(1);
            var workers = Executors.newFixedThreadPool(2);
            try {
                var completed = workers.submit(() -> ledger.complete(task, TaskResult.SUCCEEDED, "{}", false, () -> {
                    beforeEndLocked.countDown();
                    awaitBarrier(writerInTransaction);
                    finishing.close();
                }));
                var written = workers.submit(() -> {
                    awaitBarrier(beforeEndLocked);
                    char[] value = "controlled credential".toCharArray();
                    try {
                        writer.transaction(() -> {
                            writerInTransaction.countDown();
                            writer.save("ssh/concurrent", value);
                        });
                        return null;
                    } finally {
                        Arrays.fill(value, '\0');
                    }
                });

                assertTrue(completed.get(15, TimeUnit.SECONDS));
                var snapshot = ledger.queries().snapshot(task);
                assertEquals(TaskState.ENDED, snapshot.state());
                assertEquals(Optional.of(TaskResult.SUCCEEDED), snapshot.outcome());
                assertTrue(snapshot.blockers().isEmpty());
                try (var connection = db.database().openConnection();
                     var leases = connection.prepareStatement(
                         "SELECT COUNT(*) FROM platform_task_resource WHERE task_id=? AND released_at IS NULL")) {
                    leases.setObject(1, task);
                    try (var rows = leases.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getLong(1));
                    }
                }
                written.get(15, TimeUnit.SECONDS);
                char[] found = writer.read("ssh/concurrent").orElseThrow();
                try {
                    assertArrayEquals("controlled credential".toCharArray(), found);
                } finally {
                    Arrays.fill(found, '\0');
                }
                assertThrows(IllegalStateException.class, finishing::fork);
            } finally {
                beforeEndLocked.countDown();
                writerInTransaction.countDown();
                stopWorkers(workers);
            }
        }
    }

    @Test
    void closeWaitsForItsOwnTransactionAndTheFinalForkClearsSharedKey() throws Exception {
        try (var db = open();
             var session = new SecretVault(db.security()).initialize(password());
             var retained = session.fork()) {
            var material = privateField(session, "material");
            byte[] key = (byte[]) privateField(material, "key");
            assertSame(material, privateField(retained, "material"));
            assertEquals(2, privateField(material, "references"));
            assertFalse(Arrays.equals(new byte[key.length], key));
            var transactionEntered = new CountDownLatch(1);
            var allowCommit = new CountDownLatch(1);
            var closeAttempted = new CountDownLatch(1);
            var writerThread = new AtomicReference<Thread>();
            var closeThread = new AtomicReference<Thread>();
            var workers = Executors.newFixedThreadPool(2);
            try {
                var written = workers.submit(() -> {
                    writerThread.set(Thread.currentThread());
                    char[] value = "retained credential".toCharArray();
                    try {
                        session.transaction(() -> {
                            session.save("ssh/own-transaction", value);
                            transactionEntered.countDown();
                            awaitBarrier(allowCommit);
                        });
                        return null;
                    } finally {
                        Arrays.fill(value, '\0');
                    }
                });
                awaitBarrier(transactionEntered);
                var closed = workers.submit(() -> {
                    closeThread.set(Thread.currentThread());
                    closeAttempted.countDown();
                    session.close();
                });
                awaitBarrier(closeAttempted);
                awaitBlockedClose(closeThread.get(), writerThread.get());
                assertFalse(closed.isDone());
                assertEquals(2, privateField(material, "references"));
                assertFalse(Arrays.equals(new byte[key.length], key));
                allowCommit.countDown();
                written.get(10, TimeUnit.SECONDS);
                closed.get(10, TimeUnit.SECONDS);
                assertThrows(IllegalStateException.class, session::fork);
                assertThrows(IllegalStateException.class, () -> session.read("ssh/own-transaction"));
                assertEquals(1, privateField(material, "references"));
                assertFalse(Arrays.equals(new byte[key.length], key));

                try (var child = retained.fork()) {
                    assertEquals(2, privateField(material, "references"));
                    retained.close();
                    assertEquals(1, privateField(material, "references"));
                    assertFalse(Arrays.equals(new byte[key.length], key));
                    char[] found = child.read("ssh/own-transaction").orElseThrow();
                    try {
                        assertArrayEquals("retained credential".toCharArray(), found);
                    } finally {
                        Arrays.fill(found, '\0');
                    }
                }
                assertEquals(0, privateField(material, "references"));
                assertArrayEquals(new byte[key.length], key);
                session.close();
                retained.close();
                assertEquals(0, privateField(material, "references"));
                assertThrows(IllegalStateException.class, retained::fork);
            } finally {
                allowCommit.countDown();
                stopWorkers(workers);
            }
        }
    }

    private static void awaitBarrier(CountDownLatch barrier) {
        try {
            assertTrue(barrier.await(10, TimeUnit.SECONDS), "Controlled transaction barrier timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Controlled transaction barrier interrupted", failure);
        }
    }

    private static void awaitBlockedClose(Thread closing, Thread writer) {
        var threads = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            var info = threads.getThreadInfo(closing.threadId(), 32);
            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                && info.getLockOwnerId() == writer.threadId()
                && Arrays.stream(info.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals(SecretVault.Session.class.getName())
                    && frame.getMethodName().equals("close")))
                return;
            if (closing.getState() == Thread.State.TERMINATED)
                fail("Session close crossed its active transaction");
            Thread.onSpinWait();
        }
        fail("Session close did not reach its active transaction monitor");
    }

    private static Object privateField(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void stopWorkers(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "Credential workers did not exit");
    }
}
