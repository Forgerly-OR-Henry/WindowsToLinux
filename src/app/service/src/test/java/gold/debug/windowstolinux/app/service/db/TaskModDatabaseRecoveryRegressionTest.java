package gold.debug.windowstolinux.app.service.db;

import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.app.service.db.TaskModDatabaseTest.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TaskModDatabaseRecoveryRegressionTest {
    @TempDir
    Path root;

    /** A real previous JVM exits after one committed write with an unclosed physical JDBC handle. */
    public static void main(String[] args) throws Exception {
        var fixture = new TaskModDatabaseTest();
        var db = PlatformDatabase.open(Path.of(args[0]), PROTECTION);
        var identity = fixture.prepare(db, "example.owner");
        var security = security(db);
        var runtime = new PlatformTaskRuntime(db, (c, o, v, r) -> fixture.claims(identity, r), security);
        var business = new TaskModDatabase(db, runtime, identity);
        var task = runtime.bind(identity.ownerId(), "1.0.0", false).submit(fixture.request(), active -> {
            var connection = business.openConnection(active);
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO items VALUES(1)");
            }
            throw new IllegalStateException("Fixture process loses its original JDBC handle");
        });
        state(task, TaskState.UNKNOWN);
        assertEquals(1, db.openConnections());
        if (Boolean.parseBoolean(args[1]))
            fixture.sql(db, "UPDATE platform_task_resource SET recovery_json='{\"release\":\"EXPLICIT\"}' WHERE resource_kind='CONNECTION'");
        Files.writeString(Path.of(args[2]), task.id() + "\n" + identity.generation());
        System.exit(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void previousJvmReleasePreservesLiveUnknownForeignAndOriginalBusiness(boolean legacy) throws Exception {
        var ids = root.resolve("ids.txt");
        var executable = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var child = new ProcessBuilder(executable.toString(), "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            getClass().getName(), root.resolve("db").toString(), Boolean.toString(legacy), ids.toString())
            .redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(0, child.exitValue(), () -> {
            try {
                return Files.readString(root.resolve("child.log"));
            } catch (Exception failure) {
                return failure.toString();
            }
        });
        var lines = Files.readAllLines(ids);
        var taskId = UUID.fromString(lines.get(0));
        var identity = new SchemaIdentity("example.owner", "1", UUID.fromString(lines.get(1)));
        var fixture = new TaskModDatabaseTest();
        var problem = new AtomicReference<Throwable>();
        try (var db = PlatformDatabase.open(root.resolve("db"), PROTECTION);
             var security = security(db);
             var runtime = new PlatformTaskRuntime(db, (c, o, v, r) -> fixture.claims(identity, r), security)) {
            var business = new TaskModDatabase(db, runtime, identity);
            var service = runtime.bind(identity.ownerId(), "1.0.0", true);
            try (var registration = service.registerRecovery("fixture", (previous, active) -> {
                try (var live = business.openConnection(active);
                     var unknown = runtime.retain(active, new TaskResource("CONNECTION", UUID.randomUUID().toString(), identity.ownerId(), false),
                         new ResourceRecovery("unrecognized.v1", Map.of("kind", "unknown")), false);
                     var foreign = runtime.retain(active, new TaskResource("CONNECTION", UUID.randomUUID().toString(), "foreign.owner", false))) {
                    assertThrows(ApiException.class, () -> runtime.releasePreviousSchemaConnections(active,
                        new SchemaIdentity(identity.ownerId(), "1", UUID.randomUUID())));
                    business.releasePreviousConnections(active);
                    business.releasePreviousConnections(active);
                    assertFalse(service.resourcesReleased(active));
                    assertEquals(3, fixture.count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='CONNECTION' AND released_at IS NULL"));
                    assertEquals(1, db.openConnections());
                    try (var statement = live.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM items")) {
                        assertTrue(rows.next());
                        assertEquals(1, rows.getInt(1));
                    }
                } catch (Throwable failure) {
                    problem.set(failure);
                    throw failure;
                }
                return success(null);
            })) {
                var result = runtime.reconcile(taskId).toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertNull(problem.get(), () -> String.valueOf(problem.get()));
                assertEquals(TaskState.ENDED, result.state());
                assertEquals(0, fixture.count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
        }
    }

    @Test
    void normalCallbackNeverReleasesItsLiveJdbc() throws Exception {
        var fixture = new TaskModDatabaseTest();
        try (var db = PlatformDatabase.open(root, PROTECTION)) {
            var identity = fixture.prepare(db, "example.owner");
            try (var security = security(db);
                 var runtime = new PlatformTaskRuntime(db, (c, o, v, r) -> fixture.claims(identity, r), security)) {
                var business = new TaskModDatabase(db, runtime, identity);
                var task = runtime.bind(identity.ownerId(), "1.0.0", false).submit(fixture.request(), active -> {
                    try (var live = business.openConnection(active)) {
                        business.releasePreviousConnections(active);
                        assertEquals(1, db.openConnections());
                        assertFalse(live.isClosed());
                    }
                    return success(null);
                });
                assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED, result(task).outcome());
            }
        }
    }
}
