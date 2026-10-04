package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.schema.SchemaProvisioner;
import gold.debug.windowstolinux.app.db.server.StoredServerIdentity;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.app.service.db.TaskModDatabase;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.ResourceLease;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTaskAdmissionIntegrationTest {
    @TempDir
    Path root;

    static final String OWNER = "example.admission";

    static final String VERSION = "1.0.0";

    static final String PASSWORD = "fixture-password";

    static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.ofNullable(value), Optional.empty());
    }

    static TaskRequest request(String... servers) {
        return new TaskRequest("fixture", "平台准入本机验证", List.of(servers), true);
    }

    static void sql(PlatformDatabase db, String statement, Object... values) throws SQLException {
        try (var connection = db.openConnection(); var query = connection.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++)
                query.setObject(i + 1, values[i]);
            query.executeUpdate();
        }
    }

    static long count(PlatformDatabase db, String query) throws SQLException {
        try (var connection = db.openConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    static void state(TaskHandle<?> task, TaskState expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (task.snapshot().state() != expected && System.nanoTime() < deadline)
            Thread.sleep(5);
        assertEquals(expected, task.snapshot().state(), task.snapshot().toString());
    }

    static void register(PlatformDatabase db, String schemaVersion) throws Exception {
        sql(db, "INSERT INTO mod_registry(mod_id,updated_at) VALUES(?,1)", OWNER);
        sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,schema_version,manifest_json,origin_kind,installed_at,state) VALUES(?,?,?, ?,?,?,'CLASS',1,'READY')",
            OWNER, VERSION, "fixture-only", "0".repeat(64), schemaVersion,
            PlatformModIntentsTest.manifest(OWNER, VERSION, Map.of(), schemaVersion, List.of()));
        sql(db, "UPDATE mod_registry SET enabled=TRUE,selected_version=?,selection_initialized=TRUE,actual_version=?,last_runtime_state='RUNNING',last_boot_id=? WHERE mod_id=?",
            VERSION, VERSION, db.bootId(), OWNER);
    }

    static SchemaIdentity initialize(PlatformRuntime runtime, boolean withSchema) throws Exception {
        runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        try (var operation = runtime.security().enterOperation();
             var credentials = runtime.security().openCredentials()) {
            credentials.save("credential", "fixture-secret".toCharArray());
            credentials.save("other-credential", "other-secret".toCharArray());
        }
        runtime.persistence().servers().saveServerProfile(profile("host.example", 22, "alice", "credential", "Server"));
        var db = runtime.persistence().database();
        register(db, withSchema ? "1" : null);
        if (!withSchema)
            return null;
        var identity = new SchemaIdentity(OWNER, "1", UUID.randomUUID());
        var coordinates = new SchemaCoordinates(identity);
        sql(db, "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES(?,'1',?,?,?,'PREPARING',1)",
            OWNER, identity.generation(), coordinates.physicalName(), coordinates.databaseUser());
        new SchemaProvisioner(db).create(identity);
        try (var scope = new SchemaAccessScope(db, identity, UUID.randomUUID(), false);
             var connection = scope.openConnection();
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE items(id INT PRIMARY KEY)");
        }
        sql(db, "UPDATE mod_schema SET state='READY',validated_at=1 WHERE mod_id=?", OWNER);
        return identity;
    }

    static StoredServerProfile profile(String host, int port, String user, String credential, String name) {
        return new StoredServerProfile("server", host, port, user, credential, name);
    }

    @Test
    void defaultRuntimeAdmitsRealSchemaAndFreezesConnectionSettingsUntilActualRelease() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var identity = initialize(runtime, true);
            var db = runtime.persistence().database();
            var business = new TaskModDatabase(db, runtime.tasks(), identity);
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var retained = new AtomicReference<ResourceLease>();
            var task = binding.submit(request("server"), context -> {
                retained.set(context.retain("fixture", "held", "收尾占用"));
                try (var connection = business.openConnection(context); var statement = connection.createStatement()) {
                    assertEquals(1, statement.executeUpdate("INSERT INTO items VALUES(42)"));
                }
                return success(42);
            });
            try {
                state(task, TaskState.FINISHING);
                var ledger = new TaskLedger(db);
                assertTrue(ledger.queries().holds(task.id(),
                    new TaskResource("SCHEMA", identity.generation().toString(), OWNER, false)));
                assertEquals(1, count(db, "SELECT COUNT(*) FROM mod_schema WHERE ever_used=TRUE"));
                assertThrows(SQLException.class, runtime::close);
                runtime.tasks().seal(true);
                assertThrows(SQLException.class, runtime::close);
                assertThrows(ApiException.class, () -> binding.submit(request(), context -> success(null)));
                runtime.tasks().seal(false);
                var queued = binding.submit(request("server"), context -> {
                    fail("Cancelled queued callback ran");
                    return success(null);
                });
                assertEquals(TaskState.QUEUED, queued.snapshot().state());
                queued.requestCancel();
                assertEquals(TaskResult.CANCELLED,
                    queued.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
                for (var changed : List.of(profile("new.example", 22, "alice", "credential", "Server"),
                    profile("host.example", 2222, "alice", "credential", "Server"),
                    profile("host.example", 22, "bob", "credential", "Server"),
                    profile("host.example", 22, "alice", "other-credential", "Server")))
                    assertThrows(SQLException.class, () -> runtime.persistence().servers().saveServerProfile(changed));
                runtime.persistence().servers()
                    .saveServerProfile(profile("host.example", 22, "alice", "credential", "重命名"));
                runtime.persistence().servers()
                    .saveServer(new StoredServerIdentity("server", "host.example", 22, "SHA256:fixture"));
                assertEquals("重命名",
                    runtime.persistence().servers().findServerProfile("server").orElseThrow().displayName());
            } finally {
                if (retained.get() != null)
                    retained.get().close();
            }
            assertEquals(42, task.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).value().orElseThrow());
            assertEquals(0, runtime.security().securityState().activeTasks());
            runtime.persistence().servers()
                .saveServerProfile(profile("new.example", 2222, "bob", "other-credential", "Changed"));
            assertTrue(runtime.persistence().servers().findServer("server").isEmpty());
        }
    }

    @Test
    void rejectsStaleBootPendingIntentUnavailableSchemaAndUnknownServersBeforePersistingTasks() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var identity = initialize(runtime, true);
            var db = runtime.persistence().database();
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var mutations = List.of(
                new String[]{"UPDATE mod_registry SET enabled=FALSE", "UPDATE mod_registry SET enabled=TRUE"},
                new String[]{"UPDATE mod_registry SET last_boot_id=RANDOM_UUID()",
                    "UPDATE mod_registry SET last_boot_id='" + db.bootId() + "'"},
                new String[]{"UPDATE mod_registry SET last_runtime_state='STARTING'",
                    "UPDATE mod_registry SET last_runtime_state='RUNNING'"},
                new String[]{"UPDATE mod_version SET state='REINSTALLING'", "UPDATE mod_version SET state='READY'"},
                new String[]{"UPDATE mod_schema SET state='UNKNOWN'", "UPDATE mod_schema SET state='READY'"},
                new String[]{"UPDATE mod_schema SET database_user='invalid_owner'",
                    "UPDATE mod_schema SET database_user='" + new SchemaCoordinates(identity).databaseUser()
                        + "'"});
            for (var mutation : mutations) {
                sql(db, mutation[0]);
                var failure = assertThrows(ApiException.class,
                    () -> binding.submit(request("server"), context -> success(null)));
                assertEquals(ProblemCode.UNAVAILABLE, failure.problem().code());
                assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task"));
                assertEquals(0, runtime.security().securityState().activeTasks());
                sql(db, mutation[1]);
            }
            sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,manifest_json,origin_kind,installed_at,state) VALUES(?,'2.0.0','next',?,'{}','CLASS',1,'READY')",
                OWNER, "1".repeat(64));
            sql(db, "UPDATE mod_registry SET selected_version='2.0.0'");
            assertThrows(ApiException.class, () -> binding.submit(request("server"), context -> success(null)));
            sql(db, "UPDATE mod_registry SET selected_version=?", VERSION);
            assertThrows(ApiException.class, () -> binding.submit(request("missing"), context -> success(null)));
            assertThrows(ApiException.class,
                () -> runtime.tasks().bind(OWNER, "2.0.0", false).submit(request(), context -> success(null)));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task"));
            assertTrue(runtime.tasks().snapshots().isEmpty());
            assertEquals(0, runtime.security().securityState().activeTasks());
        }
    }

    @Test
    void concurrentConfigurationWriteCannotPassBetweenLiveValidationAndTaskInsert() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var db = runtime.persistence().database();
            var policy = new PlatformTaskAdmission(db);
            var validated = new CountDownLatch(1);
            var admit = new CountDownLatch(1);
            var releaseWork = new CountDownLatch(1);
            try (var tasks = new PlatformTaskRuntime(db, (connection, owner, version, request) -> {
                var claims = policy.claims(connection, owner, version, request);
                validated.countDown();
                try {
                    if (!admit.await(8, TimeUnit.SECONDS))
                        throw new SQLException("Admission test timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new SQLException(failure);
                }
                return claims;
            }, runtime.security()); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var submitted = workers
                    .submit(() -> tasks.bind(OWNER, VERSION, false).submit(request("server"), context -> {
                        assertTrue(releaseWork.await(8, TimeUnit.SECONDS));
                        return success(null);
                    }));
                Future<?> mutation = null;
                TaskHandle<?> task = null;
                try {
                    assertTrue(validated.await(8, TimeUnit.SECONDS));
                    var entered = new CountDownLatch(1);
                    mutation = workers.submit(() -> {
                        entered.countDown();
                        runtime.persistence().servers()
                            .saveServerProfile(profile("changed.example", 22, "alice", "credential", "Changed"));
                        return null;
                    });
                    assertTrue(entered.await(8, TimeUnit.SECONDS));
                    var blocked = mutation;
                    assertThrows(TimeoutException.class, () -> blocked.get(150, TimeUnit.MILLISECONDS));
                    admit.countDown();
                    task = submitted.get(8, TimeUnit.SECONDS);
                    var refused = assertThrows(ExecutionException.class, () -> blocked.get(8, TimeUnit.SECONDS));
                    assertInstanceOf(SQLException.class, refused.getCause());
                    assertEquals("host.example",
                        runtime.persistence().servers().findServerProfile("server").orElseThrow().host());
                } finally {
                    admit.countDown();
                    releaseWork.countDown();
                    if (task == null)
                        task = submitted.get(8, TimeUnit.SECONDS);
                    assertEquals(TaskResult.SUCCEEDED,
                        task.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
                    if (mutation != null && !mutation.isDone())
                        mutation.get(8, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void queuedAndUnknownTasksFreezeServersEvenWithoutActiveResourceRows() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var db = runtime.persistence().database();
            var ledger = new TaskLedger(db);
            UUID id = ledger.enqueue(UUID.randomUUID(), OWNER, VERSION, request("server"), connection -> {
                try {
                    return new PlatformTaskAdmission(db).claims(connection, OWNER, VERSION, request("server"));
                } catch (ApiException failure) {
                    throw new SQLException(failure);
                }
            });
            try {
                assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task_resource"));
                var changed = profile("host.example", 22, "bob", "credential", "Server");
                assertThrows(SQLException.class, () -> runtime.persistence().servers().saveServerProfile(changed));
                ledger.uncertain(id);
                assertThrows(SQLException.class, () -> runtime.persistence().servers().saveServerProfile(changed));
                sql(db, "UPDATE platform_task SET request_json='{}' WHERE task_id=?", id);
                assertThrows(SQLException.class, () -> runtime.persistence().servers().saveServerProfile(changed));
            } finally {
                assertTrue(ledger.complete(id, TaskResult.CANCELLED, "{}", true));
            }
            runtime.persistence().servers()
                .saveServerProfile(profile("host.example", 22, "bob", "credential", "Server"));
        }
    }

    @Test
    void defaultStartupOwnsPreviousBootUnknownAndAllowsOnlyOriginalRecovery() throws Exception {
        UUID taskId;
        try (var db = PlatformDatabase.open(root, PlatformStartupTest.PROTECTION)) {
            try (var security = new gold.debug.windowstolinux.app.service.security.DesktopSecurityService(
                new gold.debug.windowstolinux.app.db.security.SecurityStore(
                    new gold.debug.windowstolinux.app.db.connect.StoreConnections(
                        db::openConnection)))) {
                security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            }
            var ledger = new TaskLedger(db);
            taskId = ledger.enqueue(UUID.randomUUID(), OWNER, VERSION, request(),
                List.of(new TaskResource("MOD", OWNER, OWNER, false)));
            assertTrue(ledger.tryStart(taskId));
        }
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            assertEquals(TaskState.UNKNOWN, runtime.tasks().snapshots().getFirst().state());
            assertEquals(taskId, runtime.tasks().snapshots().getFirst().id());
            assertEquals(1, runtime.security().securityState().activeTasks());
            assertTrue(runtime.security().securityState().configured());
            assertFalse(runtime.security().securityState().unlocked());
            assertThrows(SQLException.class, runtime::close);
            runtime.security().unlock(PASSWORD.toCharArray());
            assertThrows(RuntimeException.class, () -> runtime.security().changeMasterPassword(PASSWORD.toCharArray(),
                "replacement-password".toCharArray(), "replacement-password".toCharArray()));
            var recovery = runtime.tasks().bind(OWNER, VERSION, true);
            assertThrows(ApiException.class, () -> recovery.submit(request(), context -> success(null)));
            try (var registration = recovery.registerRecovery("fixture", (previous, context) -> {
                assertEquals(taskId, context.id());
                assertEquals(VERSION, previous.ownerVersion());
                return success(null);
            })) {
                assertEquals(TaskState.ENDED,
                    runtime.tasks().reconcile(taskId).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            assertEquals(0, runtime.security().securityState().activeTasks());
        }
    }
}
