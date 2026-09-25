package gold.debug.windowstolinux.app.service.database;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.api.db.SchemaIdentity;
import gold.debug.windowstolinux.api.task.*;
import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.bootstrap.DatabaseProtection;
import gold.debug.windowstolinux.app.db.schema.*;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskModDatabaseTest {
    static gold.debug.windowstolinux.app.service.security.DesktopSecurityService security(PlatformDatabase db)
            throws Exception {
        var security = new gold.debug.windowstolinux.app.service.security.DesktopSecurityService(
                new gold.debug.windowstolinux.app.db.persistence.repository.SecurityRepository(
                        new gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections(
                                db::openConnection)));
        if (security.securityState().configured())
            security.unlock("fixture-password".toCharArray());
        else
            security.setupAndUnlock("fixture-password".toCharArray(), "fixture-password".toCharArray());
        return security;
    }

    @TempDir
    Path root;

    static final DatabaseProtection PROTECTION = new DatabaseProtection() {
        public byte[] protect(byte[] input) {
            var copy = input.clone();
            for (int i = 0; i < copy.length; i++)
                copy[i] ^= 0x21;
            return copy;
        }

        public byte[] unprotect(byte[] input) {
            return protect(input);
        }
    };
    static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.ofNullable(value), Optional.empty());
    }

    static <T> OperationResult<T> result(TaskHandle<T> task) throws Exception {
        return task.completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
    }

    static void state(TaskHandle<?> task, TaskState expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (task.snapshot().state() != expected && System.nanoTime() < deadline)
            Thread.sleep(5);
        assertEquals(expected, task.snapshot().state(), task.snapshot().toString());
    }

    TaskRequest request() {
        return new TaskRequest("fixture", "数据库任务", List.of("server-A"), true);
    }

    SchemaIdentity prepare(PlatformDatabase database, String owner) throws Exception {
        var identity = new SchemaIdentity(owner, "1", UUID.randomUUID());
        var coordinates = new SchemaCoordinates(identity);
        try (var c = database.openConnection()) {
            try (var p = c.prepareStatement("INSERT INTO mod_registry(mod_id,updated_at) VALUES(?,1)")) {
                p.setString(1, owner);
                p.executeUpdate();
            }
            try (var p = c.prepareStatement(
                    "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES(?,'1',?,?,?,'PREPARING',1)")) {
                p.setString(1, owner);
                p.setObject(2, identity.generation());
                p.setString(3, coordinates.physicalName());
                p.setString(4, coordinates.databaseUser());
                p.executeUpdate();
            }
        }
        new SchemaProvisioner(database).create(identity);
        try (var access = new SchemaAccessScope(database, identity, UUID.randomUUID(), false);
                var c = access.openConnection();
                var s = c.createStatement()) {
            s.execute("CREATE TABLE items(id INT PRIMARY KEY)");
        }
        try (var c = database.openConnection();
                var p = c.prepareStatement("UPDATE mod_schema SET state='READY',validated_at=1 WHERE mod_id=?")) {
            p.setString(1, owner);
            p.executeUpdate();
        }
        return identity;
    }

    List<TaskResource> claims(SchemaIdentity identity, TaskRequest request) {
        var claims = new ArrayList<TaskResource>();
        claims.add(new TaskResource("MOD", identity.ownerId(), identity.ownerId(), false));
        claims.add(new TaskResource("SCHEMA", identity.generation().toString(), identity.ownerId(), false));
        for (var server : request.serverIds())
            claims.add(new TaskResource("SERVER", server, identity.ownerId(), true));
        return claims;
    }

    void sql(PlatformDatabase db, String sql) throws Exception {
        try (var c = db.openConnection(); var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    long count(PlatformDatabase db, String sql) throws Exception {
        try (var c = db.openConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void realOwnedJdbcPreservesParentIdentityAndAuthorTransactionControl() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION)) {
            var identity = prepare(db, "example.owner");
            try (var security = security(db);
                    var runtime = new PlatformTaskRuntime(db,
                            (connection, owner, version, request) -> claims(identity, request), security)) {
                var business = new TaskModDatabase(db, runtime, identity);
                assertEquals(identity, business.identity());
                var task = runtime.bind(identity.ownerId(), "1.0.0", false).submit(request(), active -> {
                    javax.swing.SwingUtilities.invokeAndWait(
                            () -> assertThrows(ApiException.class, () -> business.openConnection(active)));
                    try (var c = business.openConnection(active); var s = c.createStatement()) {
                        assertSame(c, s.getConnection());
                        assertSame(c, c.getMetaData().getConnection());
                        c.setAutoCommit(false);
                        s.execute("INSERT INTO items VALUES(1)");
                        c.rollback();
                        s.execute("INSERT INTO items VALUES(2)");
                        c.commit();
                        try (var r = s.executeQuery("SELECT id FROM items")) {
                            assertSame(c, r.getStatement().getConnection());
                            assertTrue(r.next());
                            assertEquals(2, r.getInt(1));
                            assertFalse(r.next());
                        }
                        assertThrows(SQLException.class, () -> s.execute("SELECT * FROM app.mod_registry"));
                        assertThrows(SQLException.class, () -> c.getMetaData().getURL());
                    }
                    return success("written");
                });
                assertEquals("written", result(task).value().orElseThrow());
                assertEquals(0, db.openConnections());
                assertEquals(1, count(db, "SELECT COUNT(*) FROM mod_schema WHERE ever_used"));
                assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
        }
    }

    @Test
    void anotherModOrStaleGenerationCannotBorrowTheOriginalTasksAuthority() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION)) {
            var identity = prepare(db, "example.owner");
            var other = prepare(db, "example.other");
            try (var security = security(db);
                    var runtime = new PlatformTaskRuntime(db,
                            (connection, owner, version, request) -> claims(identity, request), security)) {
                var foreign = new TaskModDatabase(db, runtime, other);
                var stale = new TaskModDatabase(db, runtime,
                        new SchemaIdentity(identity.ownerId(), "1", UUID.randomUUID()));
                var task = runtime.bind(identity.ownerId(), "1.0.0", false).submit(request(), active -> {
                    assertThrows(ApiException.class, () -> foreign.openConnection(active));
                    assertThrows(SQLException.class, () -> stale.openConnection(active));
                    return success(null);
                });
                result(task);
                assertEquals(0, count(db, "SELECT COUNT(*) FROM mod_schema WHERE ever_used"));
                assertEquals(0,
                        count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='CONNECTION'"));
            }
        }
    }

    @Test
    void connectionOnItsOwningThreadKeepsTheTaskFinishingUntilActualClose() throws Exception {
        var close = new CountDownLatch(1);
        try (var db = PlatformDatabase.open(root, PROTECTION)) {
            var identity = prepare(db, "example.owner");
            try (var security = security(db);
                    var runtime = new PlatformTaskRuntime(db,
                            (connection, owner, version, request) -> claims(identity, request), security);
                    var ownerThread = Executors.newSingleThreadExecutor()) {
                var business = new TaskModDatabase(db, runtime, identity);
                var opened = new CompletableFuture<Void>();
                var closed = new CompletableFuture<Void>();
                var task = runtime.bind(identity.ownerId(), "1.0.0", false).submit(request(), active -> {
                    ownerThread.submit(() -> {
                        try (var c = business.openConnection(active)) {
                            opened.complete(null);
                            if (!close.await(8, TimeUnit.SECONDS))
                                throw new IllegalStateException("Test close signal timed out");
                        } catch (Throwable failure) {
                            opened.completeExceptionally(failure);
                            closed.completeExceptionally(failure);
                            return;
                        }
                        closed.complete(null);
                    });
                    opened.get(8, TimeUnit.SECONDS);
                    return success("returned");
                });
                state(task, TaskState.FINISHING);
                assertEquals(1, db.openConnections());
                assertFalse(task.completion().toCompletableFuture().isDone());
                assertThrows(ApiException.class, runtime::close);
                close.countDown();
                closed.get(8, TimeUnit.SECONDS);
                assertEquals("returned", result(task).value().orElseThrow());
                assertEquals(0, db.openConnections());
            }
        } finally {
            close.countDown();
        }
    }

    @Test
    void physicalCloseCanRetryFailedTaskReleaseThroughTheSameConnection() throws Exception {
        var attempt = new CountDownLatch(1);
        var retry = new CountDownLatch(1);
        try (var db = PlatformDatabase.open(root, PROTECTION)) {
            var identity = prepare(db, "example.owner");
            try (var security = security(db);
                    var runtime = new PlatformTaskRuntime(db,
                            (connection, owner, version, request) -> claims(identity, request), security)) {
                var business = new TaskModDatabase(db, runtime, identity);
                var opened = new CountDownLatch(1);
                var failed = new CountDownLatch(1);
                var task = runtime.bind(identity.ownerId(), "1.0.0", false).submit(request(), active -> {
                    var c = business.openConnection(active);
                    var parent = c.getMetaData().getConnection();
                    assertSame(c, parent);
                    opened.countDown();
                    assertTrue(attempt.await(8, TimeUnit.SECONDS));
                    assertThrows(SQLException.class, parent::close);
                    assertTrue(c.isClosed());
                    failed.countDown();
                    assertTrue(retry.await(8, TimeUnit.SECONDS));
                    c.close();
                    c.close();
                    return success(null);
                });
                assertTrue(opened.await(8, TimeUnit.SECONDS));
                sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_release_failure CHECK(released_at IS NULL)");
                attempt.countDown();
                assertTrue(failed.await(8, TimeUnit.SECONDS));
                assertEquals(0, db.openConnections());
                assertEquals(1, count(db,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='CONNECTION' AND released_at IS NULL"));
                assertFalse(task.completion().toCompletableFuture().isDone());
                sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_release_failure");
                retry.countDown();
                result(task);
                assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
        } finally {
            attempt.countDown();
            retry.countDown();
        }
    }

    @Test
    void openingFailureDoesNotLeaveAPhantomTaskConnection() throws Exception {
        try (var db = PlatformDatabase.open(root, PROTECTION)) {
            var identity = prepare(db, "example.owner");
            sql(db, "DROP SCHEMA " + new SchemaCoordinates(identity).physicalName() + " CASCADE");
            try (var security = security(db);
                    var runtime = new PlatformTaskRuntime(db,
                            (connection, owner, version, request) -> claims(identity, request), security)) {
                var business = new TaskModDatabase(db, runtime, identity);
                var task = runtime.bind(identity.ownerId(), "1.0.0", false).submit(request(), active -> {
                    assertThrows(SQLException.class, () -> business.openConnection(active));
                    return success(null);
                });
                result(task);
                assertEquals(0, db.openConnections());
                assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
        }
    }
}
