package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformServerRefTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.connect.StoreConnections;
import gold.debug.windowstolinux.app.db.security.SecretRefStore;
import gold.debug.windowstolinux.app.db.server.ServerRefStore;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.service.task.PlatformTaskAdmission;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.secret.*;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.secret.ref.SecretRef;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult;
import gold.debug.windowstolinux.appapi.task.ref.ResourceRefState;
import gold.debug.windowstolinux.appapi.server.ref.ServerRef;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformRefLookupTest {
    @TempDir
    Path root;

    @Test
    void exactLookupPreservesEveryStateAndTimestampAndDoesNotCreateMissingReferences() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            var db = runtime.persistence().database();
            var servers = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.of(schema));
            var secrets = runtime.capabilities().secrets(OWNER, VERSION, Optional.of(schema));
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                var secret = secrets.create("fixture-purpose", new byte[]{42}, task);
                String key = " exact / key ";
                assertTrue(servers.findReference(SERVER, key, task).isEmpty());
                assertTrue(secrets.findReference(secret, key, task).isEmpty());
                var serverRef = servers.reserve(SERVER, key, task);
                var secretRef = secrets.reserve(secret, key, task);
                for (var state : ResourceRefState.values()) {
                    if (state != ResourceRefState.RESERVED) {
                        var resolution = state == ResourceRefState.ACTIVE
                            ? ResourceRefResult.REFERENCED
                            : ResourceRefResult.UNKNOWN;
                        servers.resolve(serverRef.id(), resolution, task);
                        secrets.resolve(secretRef.id(), resolution, task);
                    }
                    sql(db, "UPDATE mod_server_reference SET updated_at=42");
                    sql(db, "UPDATE mod_credential_reference SET updated_at=43");
                    assertEquals(new ServerRef(serverRef.id(), SERVER, key, state),
                        servers.findReference(SERVER, key, task).orElseThrow());
                    assertEquals(new SecretRef(secretRef.id(), secret, key, state),
                        secrets.findReference(secret, key, task).orElseThrow());
                    assertTrue(servers.findReference(SERVER, key.trim(), task).isEmpty());
                    assertTrue(secrets.findReference(secret, key.trim(), task).isEmpty());
                    assertEquals(42, count(db, "SELECT updated_at FROM mod_server_reference"));
                    assertEquals(43, count(db, "SELECT updated_at FROM mod_credential_reference"));
                }
                var other = secrets.create("fixture-purpose", new byte[]{7}, task);
                assertTrue(secrets.findReference(other, key, task).isEmpty());
                assertEquals(1, count(db, "SELECT COUNT(*) FROM mod_server_reference"));
                assertEquals(1, count(db, "SELECT COUNT(*) FROM mod_credential_reference"));
                servers.resolve(serverRef.id(), ResourceRefResult.NOT_REFERENCED, task);
                secrets.resolve(secretRef.id(), ResourceRefResult.NOT_REFERENCED, task);
                assertTrue(servers.findReference(SERVER, key, task).isEmpty());
                assertTrue(secrets.findReference(secret, key, task).isEmpty());
                assertEquals(0, count(db, "SELECT COUNT(*) FROM mod_server_reference"));
                assertEquals(0, count(db, "SELECT COUNT(*) FROM mod_credential_reference"));
                return success(null);
            }));
        }
    }

    @Test
    void lookupRequiresAuthenticTaskExactPurposeSchemaAndAdmittedServer() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            var servers = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.of(schema));
            var secrets = runtime.capabilities().secrets(OWNER, VERSION, Optional.of(schema));
            var captured = new AtomicReference<TaskContext>();
            var saved = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                captured.set(task);
                var secret = secrets.create("fixture-purpose", new byte[]{42}, task);
                var forged = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
                    new Class<?>[]{TaskContext.class}, (proxy, method, args) -> method.invoke(task, args));
                assertCode(ProblemCode.ACCESS_DENIED, () -> servers.findReference(SERVER, "key", forged));
                assertCode(ProblemCode.ACCESS_DENIED, () -> secrets.findReference(secret, "key", forged));
                assertCode(ProblemCode.ACCESS_DENIED,
                    () -> servers.findReference(new ServerId("unadmitted"), "key", task));
                assertCode(ProblemCode.ACCESS_DENIED,
                    () -> secrets.findReference(new SecretId(secret.id(), "other"), "key", task));
                assertCode(ProblemCode.ACCESS_DENIED, () -> secrets.findReference(
                    SecretRefStore.newSecret("foreign", "fixture-purpose"), "key", task));
                for (String key : Arrays.asList(null, "", " ", "x".repeat(513))) {
                    assertCode(ProblemCode.INVALID_INPUT, () -> servers.findReference(SERVER, key, task));
                    assertCode(ProblemCode.INVALID_INPUT, () -> secrets.findReference(secret, key, task));
                }
                var wrong = new SchemaIdentity(OWNER, schema.schemaVersion(), UUID.randomUUID());
                assertCode(ProblemCode.ACCESS_DENIED, () -> runtime.capabilities().serverAccess(OWNER, VERSION, Optional.of(wrong))
                    .findReference(SERVER, "key", task));
                assertCode(ProblemCode.ACCESS_DENIED,
                    () -> runtime.capabilities().secrets(OWNER, VERSION, Optional.of(wrong)).findReference(secret, "key", task));
                assertCode(ProblemCode.UNAVAILABLE, () -> runtime.capabilities().serverAccess(OWNER, VERSION, Optional.empty())
                    .findReference(SERVER, "key", task));
                assertCode(ProblemCode.UNAVAILABLE,
                    () -> runtime.capabilities().secrets(OWNER, VERSION, Optional.empty()).findReference(secret, "key", task));
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    assertCode(ProblemCode.CONTRACT_VIOLATION, () -> servers.findReference(SERVER, "key", task));
                    assertCode(ProblemCode.CONTRACT_VIOLATION, () -> secrets.findReference(secret, "key", task));
                });
                return success(secret);
            }));
            assertCode(ProblemCode.ACCESS_DENIED, () -> servers.findReference(SERVER, "key", captured.get()));
            assertCode(ProblemCode.ACCESS_DENIED, () -> secrets.findReference(saved, "key", captured.get()));
        }
    }

    @Test
    void uncertainOriginalCallbackCanReadButNotRepeatWritesAndRecoveryFindsLostReservationIds() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            var servers = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.of(schema));
            var secrets = runtime.capabilities().secrets(OWNER, VERSION, Optional.of(schema));
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var original = new AtomicReference<TaskContext>();
            var secret = new AtomicReference<SecretId>();
            var handle = binding.submit(request("server"), task -> {
                original.set(task);
                secret.set(secrets.create("fixture-purpose", new byte[]{42}, task));
                servers.reserve(SERVER, "original", task);
                secrets.reserve(secret.get(), "original", task);
                // Simulate loss of the returned UUIDs after successful persistence. / 模拟持久化成功后返回 UUID 丢失。
                runtime.tasks().reportUncertainEffect(task);
                assertTrue(servers.findReference(SERVER, "original", task).isPresent());
                assertTrue(secrets.findReference(secret.get(), "original", task).isPresent());
                assertCode(ProblemCode.UNKNOWN, () -> servers.reserve(SERVER, "original", task));
                assertCode(ProblemCode.UNKNOWN, () -> secrets.reserve(secret.get(), "original", task));
                return success(null);
            });
            state(handle, TaskState.UNKNOWN);
            callbackReturned(runtime, original.get());
            assertCode(ProblemCode.UNAVAILABLE, () -> servers.findReference(SERVER, "original", original.get()));
            assertCode(ProblemCode.UNAVAILABLE, () -> secrets.findReference(secret.get(), "original", original.get()));
            try (var registration = binding.registerRecovery("fixture", (previous, task) -> {
                assertEquals(handle.id(), task.id());
                var serverRef = servers.findReference(SERVER, "original", task).orElseThrow();
                var secretRef = secrets.findReference(secret.get(), "original", task).orElseThrow();
                rejectRecoveryWrites(servers, secrets, secret.get(), task);
                // This fixture wrote no business association. / 本夹具未写业务关联。
                servers.resolve(serverRef.id(), ResourceRefResult.NOT_REFERENCED, task);
                secrets.resolve(secretRef.id(), ResourceRefResult.NOT_REFERENCED, task);
                return success(null);
            })) {
                assertEquals(TaskState.ENDED,
                    runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_server_reference"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_credential_reference"));
        }
    }

    @Test
    void priorBootOriginalTaskFindsDurableReferencesWithoutSavedReservationUuids() throws Exception {
        SchemaIdentity schema;
        SecretId secret;
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            schema = initialize(runtime, true);
            secret = completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> success(
                runtime.capabilities().secrets(OWNER, VERSION, Optional.of(schema)).create("restart", new byte[]{42}, task))));
        }
        UUID taskId;
        UUID expectedServer;
        UUID expectedSecret;
        // Seed the durable boundary left by an interrupted process; this is not a real process-kill test. / 构造进程中断留下的持久边界，并非实际杀进程测试。
        try (var db = PlatformDatabase.open(root, PlatformStartupTest.PROTECTION)) {
            sql(db, "UPDATE mod_registry SET last_boot_id=? WHERE mod_id=?", db.bootId(), OWNER);
            var ledger = new TaskLedger(db);
            taskId = ledger.enqueue(db.bootId(), OWNER, VERSION, request("server"), connection -> {
                try {
                    return new PlatformTaskAdmission(db).claims(connection, OWNER, VERSION, request("server"));
                } catch (ApiException failure) {
                    throw new SQLException(failure);
                }
            });
            assertTrue(ledger.tryStart(taskId));
            var connections = new StoreConnections(db::openConnection);
            expectedServer = new ServerRefStore(connections).reserve(schema, "server", "restart-key").id();
            expectedSecret = new SecretRefStore(connections).reserve(schema, secret, "restart-key").id();
        }
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            var servers = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.of(schema));
            var secrets = runtime.capabilities().secrets(OWNER, VERSION, Optional.of(schema));
            var binding = runtime.tasks().bind(OWNER, VERSION, true);
            try (var registration = binding.registerRecovery("fixture", (previous, task) -> {
                assertEquals(taskId, task.id());
                var serverRef = servers.findReference(SERVER, "restart-key", task).orElseThrow();
                var secretRef = secrets.findReference(secret, "restart-key", task).orElseThrow();
                assertEquals(expectedServer, serverRef.id());
                assertEquals(expectedSecret, secretRef.id());
                assertEquals(ResourceRefState.RESERVED, serverRef.state());
                assertEquals(ResourceRefState.RESERVED, secretRef.state());
                rejectRecoveryWrites(servers, secrets, secret, task);
                servers.resolve(serverRef.id(), ResourceRefResult.NOT_REFERENCED, task);
                secrets.resolve(secretRef.id(), ResourceRefResult.NOT_REFERENCED, task);
                return success(null);
            })) {
                assertEquals(TaskState.ENDED,
                    runtime.tasks().reconcile(taskId).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            assertEquals(0, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void referenceReadFailuresAreIoFailuresAndDoNotInventUnknownBusinessWrites() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            var db = runtime.persistence().database();
            var servers = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.of(schema));
            var secrets = runtime.capabilities().secrets(OWNER, VERSION, Optional.of(schema));
            completed(runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                var secret = secrets.create("read-failure", new byte[]{42}, task);
                sql(db, "ALTER TABLE mod_server_reference RENAME TO fixture_server_reference");
                sql(db, "ALTER TABLE mod_credential_reference RENAME TO fixture_secret_reference");
                try {
                    assertCode(ProblemCode.IO_FAILURE, () -> servers.findReference(SERVER, "key", task));
                    assertCode(ProblemCode.IO_FAILURE, () -> secrets.findReference(secret, "key", task));
                } finally {
                    sql(db, "ALTER TABLE fixture_server_reference RENAME TO mod_server_reference");
                    sql(db, "ALTER TABLE fixture_secret_reference RENAME TO mod_credential_reference");
                }
                assertTrue(servers.findReference(SERVER, "key", task).isEmpty());
                assertTrue(secrets.findReference(secret, "key", task).isEmpty());
                return success(null);
            }));
        }
    }

    private static void rejectRecoveryWrites(ServerService servers, SecretService secrets, SecretId secret,
                                             TaskContext task) {
        for (String key : List.of("original", "new-key")) {
            assertCode(ProblemCode.CONTRACT_VIOLATION, () -> servers.reserve(SERVER, key, task));
            assertCode(ProblemCode.CONTRACT_VIOLATION, () -> secrets.reserve(secret, key, task));
        }
        assertCode(ProblemCode.CONTRACT_VIOLATION, () -> secrets.create("new", new byte[0], task));
    }

    private static void assertCode(ProblemCode expected, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(expected, assertThrows(ApiException.class, operation).problem().code());
    }
}
