package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskCancellationPersistenceTest {
    @TempDir
    Path root;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void unknownCancellationKeepsOriginalGraphAndClaimsAcrossDatabaseReopen() throws Exception {
        UUID original;
        ObjectNode before;
        try (var database = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(database);
            original = TaskRecoveryGraphTest.enqueue(ledger, TaskRecoveryGraphTest.graph());
            assertTrue(ledger.tryStart(original));
            assertFalse(ledger.queries().cancellationRequested(original));
            ledger.uncertain(original);
            before = metadata(database, original);
            assertFalse(ledger.cancel(original));
            assertEquals(TaskState.UNKNOWN, ledger.queries().snapshot(original).state());
            assertTrue(ledger.queries().cancellationRequested(original));
            var after = metadata(database, original);
            assertTrue(after.remove("cancelRequested").booleanValue());
            assertEquals(before, after);
            assertFalse(ledger.queries().snapshot(original).blockers().isEmpty());
        }
        try (var database = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(database);
            assertTrue(ledger.queries().cancellationRequested(original));
            assertEquals(TaskState.UNKNOWN, ledger.queries().snapshot(original).state());
            assertTrue(ledger.queries().snapshot(original).endedAt().isEmpty());
            var after = metadata(database, original);
            after.remove("cancelRequested");
            assertEquals(before, after);
            assertTrue(ledger.complete(original, TaskResult.CANCELLED, "{}", true));
        }
    }

    @Test
    void knownLegacyCancellationIsRetainedBeforeStartupMakesTasksUnknown() throws Exception {
        UUID cancelled, untouched;
        try (var database = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(database);
            cancelled = enqueue(ledger, "cancelled");
            untouched = enqueue(ledger, "untouched");
            assertTrue(ledger.tryStart(cancelled));
            assertTrue(ledger.tryStart(untouched));
            assertFalse(ledger.cancel(cancelled));
            var legacy = metadata(database, cancelled);
            legacy.remove("cancelRequested");
            TaskRecoveryGraphTest.metadata(database, cancelled, JSON.writeValueAsString(legacy));
            assertEquals(TaskState.CANCEL_REQUESTED, ledger.queries().snapshot(cancelled).state());
        }
        try (var database = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(database);
            assertTrue(ledger.queries().cancellationRequested(cancelled));
            assertFalse(ledger.queries().cancellationRequested(untouched));
            for (var id : List.of(cancelled, untouched)) {
                assertEquals(TaskState.UNKNOWN, ledger.queries().snapshot(id).state());
                assertTrue(ledger.queries().snapshot(id).endedAt().isEmpty());
                assertFalse(ledger.queries().snapshot(id).blockers().isEmpty());
            }
        }
    }

    @Test
    void actualOptionalGraphExtensionRetainsOriginalCancellationIntent() throws Exception {
        try (var database = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            TaskOptionalDependencyTest.registry(database);
            var ledger = new TaskLedger(database);
            var graph = TaskOptionalDependencyTest.original();
            UUID original = ledger.enqueue(UUID.randomUUID(), TaskOptionalDependencyTest.OWNER, "1.0.0",
                new TaskRequest("optional.cancel", "original", List.of(), true),
                TaskOptionalDependencyTest.policy(graph, graph.claims()));
            assertTrue(ledger.tryStart(original));
            assertFalse(ledger.cancel(original));
            var admitted = ledger.dependencies().admitDependency(original, TaskOptionalDependencyTest.OWNER, "1.0.0",
                TaskOptionalDependencyTest.PROVIDER, "1.0.0", Optional.empty(), false,
                TaskOptionalDependencyTest.policy(TaskOptionalDependencyTest.provider(),
                    List.of(new TaskResource("MOD", TaskOptionalDependencyTest.PROVIDER, TaskOptionalDependencyTest.PROVIDER, false))));
            assertTrue(ledger.queries().cancellationRequested(original));
            assertEquals(3, ledger.queries().recoveryGraph(original).orElseThrow().releases().size());
            ledger.dependencies().releaseDependency(original, admitted.recovery(), admitted.leases());
            assertTrue(ledger.complete(original, TaskResult.CANCELLED, "{}", false));
        }
    }

    @Test
    void malformedCancellationAndUnrecognizedMetadataCannotGrantRecoveryOrBeRewritten() throws Exception {
        try (var database = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(database);
            var original = enqueue(ledger, "strict");
            assertTrue(ledger.tryStart(original));
            String valid = TaskRecoveryGraphTest.metadata(database, original);
            for (String encoded : List.of(JSON.writeValueAsString(metadata(database, original).put("cancelRequested", "true")),
                JSON.writeValueAsString(metadata(database, original).put("otherCancellation", true)),
                valid.substring(0, valid.length() - 1) + ",\"cancelRequested\":true,\"cancelRequested\":false}",
                valid + " {}")) {
                TaskRecoveryGraphTest.metadata(database, original, encoded);
                assertThrows(SQLException.class, () -> ledger.queries().cancellationRequested(original));
                assertThrows(SQLException.class, () -> ledger.cancel(original));
                assertThrows(SQLException.class, () -> ledger.queries().recoveryGraph(original));
                assertEquals(encoded, TaskRecoveryGraphTest.metadata(database, original));
                assertEquals(TaskState.RUNNING, ledger.queries().snapshot(original).state());
                TaskRecoveryGraphTest.metadata(database, original, valid);
            }
        }
    }

    private static UUID enqueue(TaskLedger ledger, String owner) throws Exception {
        return ledger.enqueue(UUID.randomUUID(), owner, "1.0.0", new TaskRequest("cancel", "original", List.of(), true),
            List.of(new TaskResource("MOD", owner, owner, false)));
    }

    private static ObjectNode metadata(PlatformDatabase database, UUID original) throws Exception {
        return (ObjectNode) JSON.readTree(TaskRecoveryGraphTest.metadata(database, original));
    }
}
