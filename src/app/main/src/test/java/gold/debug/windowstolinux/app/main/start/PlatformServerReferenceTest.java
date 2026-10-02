package gold.debug.windowstolinux.app.main.start;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskHandle;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.connect.RepositoryConnections;
import gold.debug.windowstolinux.app.db.server.ServerReferenceRepository;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.schema.SchemaProvisioner;
import gold.debug.windowstolinux.app.service.db.TaskModDatabase;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.server.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformServerReferenceTest {
    @TempDir
    Path root;

    static final ServerRef SERVER = new ServerRef("server");

    static <T> T completed(TaskHandle<T> task) throws Exception {
        var result = task.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(Outcome.SUCCEEDED, result.outcome());
        return result.value().orElse(null);
    }

    static void callbackReturned(PlatformRuntime runtime, TaskContext task) throws Exception {
        assertNotNull(task);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            try {
                runtime.tasks().requireCleanup(task, OWNER, VERSION);
            } catch (ApiException rejected) {
                assertEquals(ProblemCode.UNAVAILABLE, rejected.problem().code());
                assertEquals("TASK_NOT_EXECUTING", rejected.problem().eventCode());
                return;
            }
            Thread.sleep(5);
        }
        fail("The original callback did not return after its failure");
    }

    @Test
    void committedBusinessAssociationSurvivesTaskEndDisableAndReleaseVersionChange() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            var db = runtime.persistence().database();
            var business = new TaskModDatabase(db, runtime.tasks(), schema);
            var access = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.of(schema));
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var reference = completed(binding.submit(request("server"), task -> {
                var reserved = access.reserve(SERVER, "application/42", task);
                assertEquals(ReferenceState.RESERVED, reserved.state());
                assertEquals(reserved.id(), access.reserve(SERVER, "application/42", task).id());
                try (var connection = business.openConnection(task); var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE associations(id INT PRIMARY KEY,reference_id UUID NOT NULL)");
                    connection.setAutoCommit(false);
                    try (var insert = connection.prepareStatement("INSERT INTO associations VALUES(42,?)")) {
                        insert.setObject(1, reserved.id());
                        insert.executeUpdate();
                    }
                    connection.commit();
                }
                access.resolve(reserved.id(), ReferenceResolution.REFERENCED, task);
                return success(reserved.id());
            }));
            assertThrows(SQLException.class, () -> runtime.persistence().servers().deleteServerProfile("server"));
            sql(db, "UPDATE mod_registry SET enabled=FALSE WHERE mod_id=?", OWNER);
            assertThrows(SQLException.class, () -> runtime.persistence().servers().deleteServerProfile("server"));
            sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,schema_version,manifest_json,origin_kind,installed_at,state) VALUES(?,'1.0.1','fixture',?,'1',?,'CLASS',1,'READY')",
                OWNER, "0".repeat(64), PlatformModIntentsTest.manifest(OWNER, "1.0.1", Map.of(), "1", List.of()));
            sql(db, "UPDATE mod_registry SET enabled=TRUE,selected_version='1.0.1',actual_version='1.0.1' WHERE mod_id=?",
                OWNER);
            var next = runtime.capabilities().serverAccess(OWNER, "1.0.1", Optional.of(schema));
            completed(runtime.tasks().bind(OWNER, "1.0.1", false).submit(request("server"), task -> {
                var retained = next.reserve(SERVER, "application/42", task);
                assertEquals(reference, retained.id());
                assertEquals(ReferenceState.ACTIVE, retained.state());
                next.resolve(reference, ReferenceResolution.UNKNOWN, task);
                assertEquals(ReferenceState.UNKNOWN, next.reserve(SERVER, "application/42", task).state());
                try (var connection = business.openConnection(task); var statement = connection.createStatement()) {
                    connection.setAutoCommit(false);
                    try (var rows = statement.executeQuery("SELECT reference_id FROM associations WHERE id=42")) {
                        assertTrue(rows.next());
                        assertEquals(reference, rows.getObject(1, UUID.class));
                    }
                    statement.executeUpdate("DELETE FROM associations WHERE id=42");
                    connection.commit();
                }
                next.resolve(reference, ReferenceResolution.NOT_REFERENCED, task);
                next.resolve(reference, ReferenceResolution.NOT_REFERENCED, task);
                return success(null);
            }));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mod_server_reference"));
            runtime.persistence().servers().deleteServerProfile("server");
        }
    }

    @Test
    void configuredDiscoveryDoesNotAdmitServersAndCursorsStayWithinOriginalQuery() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            for (var id : List.of("server-a", "server-z"))
                runtime.persistence().servers()
                    .saveServerProfile(new StoredServerProfile(id, "host.example", 22, "alice", "credential", id));
            var access = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.of(schema));
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var request = new TaskRequest("discover", "Discover servers", List.of(), true);
            var callbackFailure = new AtomicReference<Throwable>();
            var cursor = completed(binding.submit(request, task -> {
                try {
                    assertTrue(access.list(Optional.empty(), 1, task).items().isEmpty());
                    var first = access.listConfigured(Optional.empty(), 1, task);
                    assertEquals("server", first.items().getFirst().ref().id());
                    var second = access.listConfigured(first.nextCursor(), 1, task);
                    assertEquals("server-a", second.items().getFirst().ref().id());
                    assertEquals("server-z",
                        access.listConfigured(second.nextCursor(), 1, task).items().getFirst().ref().id());
                    assertEquals(ProblemCode.INVALID_INPUT,
                        assertThrows(ApiException.class, () -> access.list(first.nextCursor(), 1, task)).problem()
                            .code());
                    assertEquals(ProblemCode.ACCESS_DENIED,
                        assertThrows(ApiException.class, () -> access.get(new ServerRef("server-a"), task))
                            .problem().code());
                    assertEquals(ProblemCode.ACCESS_DENIED,
                        assertThrows(ApiException.class,
                            () -> access.reserve(new ServerRef("server-a"), "discovery-only", task)).problem()
                            .code());
                    return success(first.nextCursor().orElseThrow());
                } catch (Exception | AssertionError failure) {
                    callbackFailure.set(failure);
                    return success(null);
                }
            }));
            if (callbackFailure.get() != null)
                throw new AssertionError("Discovery callback failed", callbackFailure.get());
            completed(binding.submit(request, task -> {
                assertEquals(ProblemCode.INVALID_INPUT,
                    assertThrows(ApiException.class, () -> access.listConfigured(Optional.of(cursor), 1, task))
                        .problem().code());
                return success(null);
            }));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_server_reference"));
        }
    }

    @Test
    void listsOnlyAdmittedServersWithBoundedTaskCursorsAndNoSchemaCannotReserve() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            for (var id : List.of("server-a", "server-z"))
                runtime.persistence().servers()
                    .saveServerProfile(new StoredServerProfile(id, "host.example", 22, "alice", "credential", id));
            var access = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.empty());
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var original = new AtomicReference<TaskContext>();
            var cursor = completed(binding.submit(request("server-a", "server-z"), task -> {
                original.set(task);
                var page = access.list(Optional.empty(), 1, task);
                assertEquals(List.of("server-a"), page.items().stream().map(info -> info.ref().id()).toList());
                assertTrue(page.nextCursor().isPresent());
                var end = access.list(page.nextCursor(), 1, task);
                assertEquals("server-z", end.items().getFirst().ref().id());
                assertTrue(end.nextCursor().isEmpty());
                assertTrue(access.get(new ServerRef("server-a"), task).observedAt().isEmpty());
                assertEquals(ProblemCode.ACCESS_DENIED,
                    assertThrows(ApiException.class, () -> access.get(SERVER, task)).problem().code());
                assertEquals(ProblemCode.UNAVAILABLE,
                    assertThrows(ApiException.class, () -> access.reserve(new ServerRef("server-a"), "key", task))
                        .problem().code());
                for (int limit : new int[]{0, 201})
                    assertEquals(ProblemCode.INVALID_INPUT,
                        assertThrows(ApiException.class, () -> access.list(Optional.empty(), limit, task)).problem()
                            .code());
                assertThrows(ApiException.class, () -> access.list(Optional.of("!invalid"), 1, task));
                var forged = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
                    new Class<?>[]{TaskContext.class}, (proxy, method, args) -> method.invoke(task, args));
                assertEquals(ProblemCode.ACCESS_DENIED,
                    assertThrows(ApiException.class, () -> access.list(Optional.empty(), 1, forged)).problem()
                        .code());
                return success(page.nextCursor().orElseThrow());
            }));
            assertThrows(ApiException.class, () -> access.list(Optional.empty(), 1, original.get()));
            completed(binding.submit(request("server-a", "server-z"), task -> {
                assertEquals(ProblemCode.INVALID_INPUT,
                    assertThrows(ApiException.class, () -> access.list(Optional.of(cursor), 1, task)).problem()
                        .code());
                return success(null);
            }));
            access.close();
            completed(binding.submit(request("server-a"), task -> {
                assertEquals(ProblemCode.UNAVAILABLE,
                    assertThrows(ApiException.class, () -> access.get(new ServerRef("server-a"), task)).problem()
                        .code());
                return success(null);
            }));
        }
    }

    @Test
    void cancellationAllowsOwnedReferenceCleanupButRefusesNewReservations() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            var access = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.of(schema));
            var ready = new CountDownLatch(1);
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                var reference = access.reserve(SERVER, "cancelled-draft", task);
                ready.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (!task.cancelToken().requested() && System.nanoTime() < deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                assertTrue(task.cancelToken().requested());
                assertEquals(ProblemCode.CANCELLED,
                    assertThrows(ApiException.class, () -> access.reserve(SERVER, "unrelated-new-work", task))
                        .problem().code());
                assertEquals(reference, access.findReference(SERVER, "cancelled-draft", task).orElseThrow());
                assertTrue(access.findReference(SERVER, "missing", task).isEmpty());
                access.resolve(reference.id(), ReferenceResolution.NOT_REFERENCED, task);
                return new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
            });
            assertTrue(ready.await(8, TimeUnit.SECONDS));
            handle.requestCancel();
            assertEquals(Outcome.CANCELLED,
                handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_server_reference"));
            runtime.persistence().servers().deleteServerProfile("server");
        }
    }

    @Test
    void uncertainPersistenceCannotBeHiddenAndOriginalRecoveryKeepsReferenceProtected() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            var db = runtime.persistence().database();
            var access = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.of(schema));
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var reference = new AtomicReference<ServerReference>();
            var original = new AtomicReference<TaskContext>();
            sql(db, "ALTER TABLE mod_server_reference ADD CONSTRAINT fixture_no_active CHECK(state<>'ACTIVE')");
            var handle = binding.submit(request("server"), task -> {
                original.set(task);
                reference.set(access.reserve(SERVER, "unknown-business", task));
                assertEquals(ProblemCode.UNKNOWN,
                    assertThrows(ApiException.class,
                        () -> access.resolve(reference.get().id(), ReferenceResolution.REFERENCED, task))
                        .problem().code());
                access.resolve(reference.get().id(), ReferenceResolution.UNKNOWN, task);
                return success(null);
            });
            state(handle, TaskState.UNKNOWN);
            callbackReturned(runtime, original.get());
            assertEquals(1, count(db, "SELECT COUNT(*) FROM mod_server_reference WHERE state='UNKNOWN'"));
            assertThrows(SQLException.class, () -> runtime.persistence().servers().deleteServerProfile("server"));
            sql(db, "ALTER TABLE mod_server_reference DROP CONSTRAINT fixture_no_active");
            try (var recovery = binding.registerRecovery("fixture", (previous, task) -> {
                assertEquals(handle.id(), task.id());
                access.resolve(reference.get().id(), ReferenceResolution.REFERENCED, task);
                return success(null);
            })) {
                assertEquals(TaskState.ENDED,
                    runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            assertEquals(1, count(db, "SELECT COUNT(*) FROM mod_server_reference WHERE state='ACTIVE'"));
            assertThrows(SQLException.class, () -> runtime.persistence().servers().deleteServerProfile("server"));
        }
    }

    @Test
    void failedPhysicalOperationReleaseIsRetriedWithoutDroppingTheOriginalTask() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            var db = runtime.persistence().database();
            var access = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.of(schema));
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_no_release CHECK(released_at IS NULL)");
            var original = new AtomicReference<TaskContext>();
            var handle = binding.submit(request("server"), task -> {
                original.set(task);
                return success(access.get(SERVER, task));
            });
            state(handle, TaskState.UNKNOWN);
            callbackReturned(runtime, original.get());
            assertEquals(0, db.openConnections());
            assertEquals(1, count(db,
                "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='CUSTOM' AND released_at IS NULL"));
            sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_no_release");
            try (var recovery = binding.registerRecovery("fixture", (previous, task) -> {
                access.retryFinishedReleases(task);
                return success(null);
            })) {
                assertEquals(TaskState.ENDED,
                    runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(8, TimeUnit.SECONDS).state());
            }
            assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void wrongGenerationCannotInspectOrReleaseRetainedReferencesAndDeleteRacesCannotCreateOrphans() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var schema = initialize(runtime, true);
            var db = runtime.persistence().database();
            var access = runtime.capabilities().serverAccess(OWNER, VERSION, Optional.of(schema));
            var wrong = runtime.capabilities().serverAccess(OWNER, VERSION,
                Optional.of(new SchemaIdentity(OWNER, "1", UUID.randomUUID())));
            var foreignReferences = new LinkedHashMap<SchemaIdentity, ServerReference>();
            for (var foreignOwner : List.of(OWNER, "example.foreign")) {
                if (!foreignOwner.equals(OWNER))
                    sql(db, "INSERT INTO mod_registry(mod_id,updated_at) VALUES(?,1)", foreignOwner);
                var foreignSchema = new SchemaIdentity(foreignOwner, "2", UUID.randomUUID());
                var coordinates = new SchemaCoordinates(foreignSchema);
                sql(db, "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES(?,'2',?,?,?,'PREPARING',1)",
                    foreignOwner, foreignSchema.generation(), coordinates.physicalName(),
                    coordinates.databaseUser());
                new SchemaProvisioner(db).create(foreignSchema);
                sql(db, "UPDATE mod_schema SET state='READY',validated_at=1 WHERE mod_id=? AND schema_version='2'",
                    foreignOwner);
                foreignReferences.put(foreignSchema,
                    runtime.persistence().serverReferences().reserve(foreignSchema, "server", "foreign-fixture"));
            }
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            completed(binding.submit(request("server"), task -> {
                assertEquals(ProblemCode.ACCESS_DENIED,
                    assertThrows(ApiException.class, () -> wrong.reserve(SERVER, "invalid", task)).problem()
                        .code());
                assertTrue(access.findReference(SERVER, "foreign-fixture", task).isEmpty());
                var owned = access.reserve(SERVER, "foreign-fixture", task);
                assertEquals(owned, access.findReference(SERVER, "foreign-fixture", task).orElseThrow());
                assertEquals(ProblemCode.ACCESS_DENIED,
                    assertThrows(ApiException.class,
                        () -> wrong.resolve(owned.id(), ReferenceResolution.NOT_REFERENCED, task)).problem()
                        .code());
                for (var foreign : foreignReferences.values()) {
                    assertEquals(ProblemCode.ACCESS_DENIED,
                        assertThrows(ApiException.class,
                            () -> access.resolve(foreign.id(), ReferenceResolution.NOT_REFERENCED, task))
                            .problem().code());
                    assertEquals(ProblemCode.ACCESS_DENIED,
                        assertThrows(ApiException.class,
                            () -> access.resolve(foreign.id(), ReferenceResolution.REFERENCED, task)).problem()
                            .code());
                }
                access.resolve(owned.id(), ReferenceResolution.NOT_REFERENCED, task);
                return success(null);
            }));
            assertEquals(2, count(db, "SELECT COUNT(*) FROM mod_server_reference WHERE state='RESERVED'"));
            for (var foreign : foreignReferences.entrySet())
                runtime.persistence().serverReferences().resolve(foreign.getKey(), foreign.getValue().id(),
                    ReferenceResolution.NOT_REFERENCED);
            var attempted = new CountDownLatch(1);
            try (var connection = db.openConnection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                connection.setAutoCommit(false);
                RepositoryConnections.lockPlatformState(connection);
                var competing = executor.submit(() -> {
                    attempted.countDown();
                    return new ServerReferenceRepository(new RepositoryConnections(db::openConnection)).reserve(schema,
                        "server", "racing-reserve");
                });
                assertTrue(attempted.await(8, TimeUnit.SECONDS));
                assertFalse(competing.isDone());
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM server_profile WHERE server_profile_id='server'");
                }
                connection.commit();
                assertInstanceOf(SQLException.class,
                    assertThrows(ExecutionException.class, () -> competing.get(8, TimeUnit.SECONDS)).getCause());
                assertEquals(0, count(db, "SELECT COUNT(*) FROM mod_server_reference"));
            }
        }
    }
}
