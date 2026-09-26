package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformServerReferenceTest.callbackReturned;
import static gold.debug.windowstolinux.app.main.startup.PlatformServerReferenceTest.completed;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.schema.*;
import gold.debug.windowstolinux.app.service.database.TaskModDatabase;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.secret.*;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformSecretServiceTest {
    @TempDir
    Path root;

    @Test
    void arbitraryBinaryAndEmptyValuesUseProtectedStorageAndAllCallbackPathsClearTheirBuffers() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var secrets = runtime.secrets(OWNER, VERSION, Optional.empty());
            var observed = new AtomicReference<byte[]>();
            byte[] input = new byte[512];
            for (int i = 0; i < input.length; i++)
                input[i] = (byte) i;
            var secret = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var created = secrets.create("部署/二进制", input, task);
                assertEquals(512, secrets.<Integer>use(created, created.purpose(), task, bytes -> {
                    observed.set(bytes);
                    assertArrayEquals(input, bytes);
                    return bytes.length;
                }));
                assertArrayEquals(new byte[input.length], observed.get());
                var error = assertThrows(ApiException.class,
                        () -> secrets.use(created, created.purpose(), task, bytes -> {
                            observed.set(bytes);
                            throw new IllegalStateException("private-callback-detail");
                        }));
                assertEquals(ProblemCode.IO_FAILURE, error.problem().code());
                assertNull(error.getCause());
                assertFalse(error.toString().contains("private-callback-detail"));
                assertArrayEquals(new byte[input.length], observed.get());
                assertEquals(ProblemCode.CONTRACT_VIOLATION,
                        assertThrows(ApiException.class, () -> secrets.use(created, created.purpose(), task, bytes -> {
                            observed.set(bytes);
                            return bytes;
                        })).problem().code());
                assertArrayEquals(new byte[input.length], observed.get());
                var empty = secrets.create("empty", new byte[0], task);
                assertEquals(0, secrets.<Integer>use(empty, "empty", task, bytes -> bytes.length));
                return success(created);
            }));
            assertEquals((byte) 255, input[255]);
            try (var stored = runtime.persistence().security().find(secret.id()).orElseThrow()) {
                assertEquals("AES256_GCM_V1", stored.storageFormat());
                assertFalse(Arrays.equals(input, stored.payload()));
            }
            assertEquals(0, runtime.persistence().database().openConnections());
        }
    }

    @Test
    void protectedIdentityAndPurposeRemainUsableAfterARealDatabaseRestart() throws Exception {
        SecretRef saved;
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var secrets = runtime.secrets(OWNER, VERSION, Optional.empty());
            saved = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(),
                    task -> success(secrets.create("restart", new byte[]{0, -1, 42}, task))));
        }
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            sql(runtime.persistence().database(), "UPDATE mod_registry SET last_boot_id=? WHERE mod_id=?",
                    runtime.persistence().database().bootId(), OWNER);
            var secrets = runtime.secrets(OWNER, VERSION, Optional.empty());
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                assertEquals(42, secrets.<Integer>use(saved, "restart", task, bytes -> {
                    assertArrayEquals(new byte[]{0, -1, 42}, bytes);
                    return (int) bytes[2];
                }));
                return success(null);
            }));
        }
    }

    @Test
    void exactOwnerPurposeAndHostTaskIdentityAreRequiredAndStatelessReferencesAreRejected() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var db = runtime.persistence().database();
            sql(db, "INSERT INTO mod_registry(mod_id,updated_at) VALUES('other.mod',1)");
            sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,manifest_json,origin_kind,installed_at,state) VALUES('other.mod',?,'fixture',?,?,'CLASS',1,'READY')",
                    VERSION, "0".repeat(64),
                    PlatformModIntentsTest.manifest("other.mod", VERSION, Map.of(), null, List.of()));
            sql(db, "UPDATE mod_registry SET enabled=TRUE,selected_version=?,selection_initialized=TRUE,actual_version=?,last_runtime_state='RUNNING',last_boot_id=? WHERE mod_id='other.mod'",
                    VERSION, VERSION, db.bootId());
            var otherSecrets = runtime.secrets("other.mod", VERSION, Optional.empty());
            var foreign = completed(runtime.tasks().bind("other.mod", VERSION, false).submit(request(),
                    task -> success(otherSecrets.create("purpose", new byte[]{99}, task))));
            var secrets = runtime.secrets(OWNER, VERSION, Optional.empty());
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var captured = new AtomicReference<TaskContext>();
            var saved = completed(binding.submit(request(), task -> {
                captured.set(task);
                var own = secrets.create("purpose", new byte[]{1}, task);
                for (var candidate : List.of(new SecretRef("credential", "purpose"), new SecretRef(own.id(), "changed"),
                        foreign)) {
                    assertEquals(ProblemCode.ACCESS_DENIED,
                            assertThrows(ApiException.class,
                                    () -> secrets.use(candidate, candidate.purpose(), task, bytes -> 1)).problem()
                                    .code());
                }
                var forged = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
                        new Class<?>[]{TaskContext.class},
                        (proxy, method, args) -> method.getName().equals("id") ? task.id() : method.invoke(task, args));
                assertEquals(ProblemCode.ACCESS_DENIED,
                        assertThrows(ApiException.class, () -> secrets.use(own, "purpose", forged, bytes -> 1))
                                .problem().code());
                assertEquals(ProblemCode.UNAVAILABLE,
                        assertThrows(ApiException.class, () -> secrets.reserve(own, "key", task)).problem().code());
                for (var purpose : List.of("", " ", "x".repeat(129)))
                    assertEquals(ProblemCode.INVALID_INPUT,
                            assertThrows(ApiException.class, () -> secrets.create(purpose, new byte[0], task)).problem()
                                    .code());
                assertEquals(ProblemCode.INVALID_INPUT, assertThrows(ApiException.class,
                        () -> secrets.create("purpose", new byte[1024 * 1024 + 1], task)).problem().code());
                SwingUtilities.invokeAndWait(() -> assertEquals(ProblemCode.CONTRACT_VIOLATION,
                        assertThrows(ApiException.class, () -> secrets.use(own, "purpose", task, bytes -> 1)).problem()
                                .code()));
                return success(own);
            }));
            assertEquals(ProblemCode.ACCESS_DENIED,
                    assertThrows(ApiException.class, () -> secrets.use(saved, "purpose", captured.get(), bytes -> 1))
                            .problem().code());
            secrets.close();
            completed(binding.submit(request(), task -> {
                assertEquals(ProblemCode.UNAVAILABLE,
                        assertThrows(ApiException.class, () -> secrets.use(saved, "purpose", task, bytes -> 1))
                                .problem().code());
                return success(null);
            }));
        }
    }

    @Test
    void referencesCommitWithPrivateBusinessDataRemainProtectedWhenDisabledAndReleaseOnlyAfterKnownDeletion()
            throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            var db = runtime.persistence().database();
            var secrets = runtime.secrets(OWNER, VERSION, Optional.of(schema));
            var business = new TaskModDatabase(db, runtime.tasks(), schema);
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var reference = completed(binding.submit(request(), task -> {
                var secret = secrets.create("deploy/env", new byte[]{1, -1}, task);
                var reserved = secrets.reserve(secret, "application/42", task);
                assertEquals(ReferenceState.RESERVED, reserved.state());
                assertEquals(reserved.id(), secrets.reserve(secret, "application/42", task).id());
                assertThrows(SQLException.class, () -> runtime.persistence().security().delete(secret.id()));
                try (var connection = business.openConnection(task); var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE secrets(id INT PRIMARY KEY,reference_id UUID NOT NULL)");
                    connection.setAutoCommit(false);
                    try (var insert = connection.prepareStatement("INSERT INTO secrets VALUES(42,?)")) {
                        insert.setObject(1, reserved.id());
                        insert.executeUpdate();
                    }
                    connection.commit();
                }
                secrets.resolve(reserved.id(), ReferenceResolution.REFERENCED, task);
                return success(reserved);
            }));
            sql(db, "UPDATE mod_registry SET enabled=FALSE WHERE mod_id=?", OWNER);
            assertThrows(SQLException.class, () -> runtime.persistence().security().delete(reference.secret().id()));
            sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,schema_version,manifest_json,origin_kind,installed_at,state) VALUES(?,'1.0.1','fixture',?,'1',?,'CLASS',1,'READY')",
                    OWNER, "0".repeat(64), PlatformModIntentsTest.manifest(OWNER, "1.0.1", Map.of(), "1", List.of()));
            sql(db, "UPDATE mod_registry SET enabled=TRUE,selected_version='1.0.1',actual_version='1.0.1' WHERE mod_id=?",
                    OWNER);
            var next = runtime.secrets(OWNER, "1.0.1", Optional.of(schema));
            completed(runtime.tasks().bind(OWNER, "1.0.1", false).submit(request(), task -> {
                assertEquals(reference.id(), next.reserve(reference.secret(), "application/42", task).id());
                next.resolve(reference.id(), ReferenceResolution.UNKNOWN, task);
                assertEquals(ReferenceState.UNKNOWN, next.reserve(reference.secret(), "application/42", task).state());
                assertThrows(SQLException.class,
                        () -> runtime.persistence().security().delete(reference.secret().id()));
                try (var connection = business.openConnection(task); var statement = connection.createStatement()) {
                    connection.setAutoCommit(false);
                    assertEquals(1, statement.executeUpdate("DELETE FROM secrets WHERE id=42"));
                    connection.commit();
                }
                next.resolve(reference.id(), ReferenceResolution.NOT_REFERENCED, task);
                next.resolve(reference.id(), ReferenceResolution.NOT_REFERENCED, task);
                return success(null);
            }));
            assertTrue(runtime.persistence().security().delete(reference.secret().id()));
        }
    }

    @Test
    void anotherGenerationCannotReleaseReferencesEvenWhenItOwnsTheSameCredential() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            var db = runtime.persistence().database();
            var secrets = runtime.secrets(OWNER, VERSION, Optional.of(schema));
            var otherSchema = new SchemaIdentity(OWNER, "2", UUID.randomUUID());
            var coordinates = new SchemaCoordinates(otherSchema);
            sql(db, "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES(?,'2',?,?,?,'PREPARING',1)",
                    OWNER, otherSchema.generation(), coordinates.physicalName(), coordinates.databaseUser());
            new SchemaProvisioner(db).create(otherSchema);
            sql(db, "UPDATE mod_schema SET state='READY',validated_at=1 WHERE mod_id=? AND schema_version='2'", OWNER);
            var retained = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var secret = secrets.create("retained", new byte[]{1}, task);
                var foreign = runtime.persistence().credentialReferences().reserve(otherSchema, secret, "other-schema");
                assertEquals(ProblemCode.ACCESS_DENIED,
                        assertThrows(ApiException.class,
                                () -> secrets.resolve(foreign.id(), ReferenceResolution.NOT_REFERENCED, task)).problem()
                                .code());
                var wrong = runtime.secrets(OWNER, VERSION, Optional.of(otherSchema));
                assertEquals(ProblemCode.ACCESS_DENIED,
                        assertThrows(ApiException.class, () -> wrong.reserve(secret, "wrong-claim", task)).problem()
                                .code());
                return success(foreign);
            }));
            assertThrows(SQLException.class, () -> runtime.persistence().security().delete(retained.secret().id()));
            runtime.persistence().credentialReferences().resolve(otherSchema, retained.id(),
                    ReferenceResolution.NOT_REFERENCED);
            assertTrue(runtime.persistence().security().delete(retained.secret().id()));
        }
    }

    @Test
    void cancellationAllowsExistingAssociationCleanupButBlocksNewSecretWork() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            var secrets = runtime.secrets(OWNER, VERSION, Optional.of(schema));
            var ready = new CountDownLatch(1);
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var secret = secrets.create("cancelled", new byte[]{1}, task);
                var reference = secrets.reserve(secret, "draft", task);
                ready.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (!task.cancellation().requested() && System.nanoTime() < deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                assertTrue(task.cancellation().requested());
                assertEquals(ProblemCode.CANCELLED,
                        assertThrows(ApiException.class, () -> secrets.create("new", new byte[0], task)).problem()
                                .code());
                assertEquals(ProblemCode.CANCELLED,
                        assertThrows(ApiException.class, () -> secrets.reserve(secret, "new", task)).problem().code());
                assertEquals(ProblemCode.CANCELLED,
                        assertThrows(ApiException.class, () -> secrets.use(secret, "cancelled", task, bytes -> 1))
                                .problem().code());
                secrets.resolve(reference.id(), ReferenceResolution.NOT_REFERENCED, task);
                return new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
            });
            assertTrue(ready.await(8, TimeUnit.SECONDS));
            handle.requestCancel();
            assertEquals(Outcome.CANCELLED,
                    handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_credential_reference"));
        }
    }

    @Test
    void unknownReferenceWritesCannotBeHiddenAndReleaseFailuresRetainOnlyOriginalTaskOwnership() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            var db = runtime.persistence().database();
            var secrets = runtime.secrets(OWNER, VERSION, Optional.of(schema));
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var reference = new AtomicReference<SecretReference>();
            var original = new AtomicReference<TaskContext>();
            sql(db, "ALTER TABLE mod_credential_reference ADD CONSTRAINT fixture_secret_state CHECK(state<>'ACTIVE')");
            var handle = binding.submit(request(), task -> {
                original.set(task);
                reference.set(secrets.reserve(secrets.create("unknown", new byte[]{7}, task), "draft", task));
                assertEquals(ProblemCode.UNKNOWN,
                        assertThrows(ApiException.class,
                                () -> secrets.resolve(reference.get().id(), ReferenceResolution.REFERENCED, task))
                                .problem().code());
                secrets.resolve(reference.get().id(), ReferenceResolution.UNKNOWN, task);
                return success(null);
            });
            state(handle, TaskState.UNKNOWN);
            callbackReturned(runtime, original.get());
            assertEquals(1, count(db, "SELECT COUNT(*) FROM mod_credential_reference WHERE state='UNKNOWN'"));
            sql(db, "ALTER TABLE mod_credential_reference DROP CONSTRAINT fixture_secret_state");
            try (var registration = binding.registerRecovery("fixture", (previous, task) -> {
                secrets.resolve(reference.get().id(), ReferenceResolution.REFERENCED, task);
                return success(null);
            })) {
                assertEquals(TaskState.ENDED,
                        runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            var plaintext = new AtomicReference<byte[]>();
            sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_secret_release CHECK(released_at IS NULL OR task_id='"
                    + handle.id() + "')");
            var failedRelease = binding.submit(request(), task -> {
                original.set(task);
                return success(secrets.use(reference.get().secret(), "unknown", task, bytes -> {
                    plaintext.set(bytes);
                    return bytes.length;
                }));
            });
            state(failedRelease, TaskState.UNKNOWN);
            callbackReturned(runtime, original.get());
            assertArrayEquals(new byte[1], plaintext.get());
            assertEquals(0, db.openConnections());
            assertEquals(1, count(db,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='CUSTOM' AND released_at IS NULL"));
            sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_secret_release");
            try (var registration = binding.registerRecovery("fixture", (previous, task) -> {
                secrets.retryFinishedReleases(task);
                return success(null);
            })) {
                assertEquals(TaskState.ENDED, runtime.tasks().reconcile(failedRelease.id()).toCompletableFuture()
                        .get(8, TimeUnit.SECONDS).state());
            }
        }
    }

    @Test
    void acceptedTaskKeepsItsKeyAcrossLockAndCannotBeClosedWhileAConsumerIsActive() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var secrets = runtime.secrets(OWNER, VERSION, Optional.empty());
            var ready = new CountDownLatch(1);
            var proceed = new CountDownLatch(1);
            var consumerReady = new CountDownLatch(1);
            var consumerExit = new CountDownLatch(1);
            var plaintext = new AtomicReference<byte[]>();
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var task = binding.submit(request(), context -> {
                var secret = secrets.create("locked", new byte[]{42}, context);
                ready.countDown();
                assertTrue(proceed.await(8, TimeUnit.SECONDS));
                return success(secrets.use(secret, "locked", context, bytes -> {
                    plaintext.set(bytes);
                    consumerReady.countDown();
                    assertTrue(consumerExit.await(8, TimeUnit.SECONDS));
                    return (int) bytes[0];
                }));
            });
            try {
                assertTrue(ready.await(8, TimeUnit.SECONDS));
                runtime.security().lock();
                assertThrows(ApiException.class, () -> binding.submit(request(), context -> success(null)));
                proceed.countDown();
                assertTrue(consumerReady.await(8, TimeUnit.SECONDS));
                assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class, secrets::close).problem().code());
                assertEquals(42, plaintext.get()[0]);
            } finally {
                proceed.countDown();
                consumerExit.countDown();
            }
            assertEquals(42, completed(task));
            assertArrayEquals(new byte[1], plaintext.get());
            secrets.close();
        }
    }
}
