package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessRecoveryLedgerTest {
    @TempDir
    Path root;

    @Test
    void originalCreationCheckpointIsOneWayAtomicAndSurvivesReopen() throws Exception {
        UUID task;
        ProcessRecovery observed;
        TaskResource resource;
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            task = new TaskLedgerTest().queue(ledger, "one", "A");
            assertTrue(ledger.tryStart(task));
            var original = new ProcessRecovery(db.bootId(), UUID.randomUUID(), OptionalLong.empty(), Optional.empty());
            observed = new ProcessRecovery(original.boot(), original.process(), OptionalLong.of(123),
                    Optional.of(Instant.now()));
            resource = new TaskResource("PROCESS", "process-v1:" + original.process(), "one", false);
            ledger.retain(task, resource, original.encode());
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute(
                        "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_checkpoint CHECK(resource_kind<>'PROCESS' OR recovery_json NOT LIKE '%STARTED%')");
            }
            assertThrows(SQLException.class, () -> ledger.checkpointProcess(task, resource, original, observed));
            assertEquals(original.encode(), ledger.recoveries(task, "PROCESS", "process-v1:").get(resource));
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute("ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_checkpoint");
            }
            ledger.checkpointProcess(task, resource, original, observed);
            ledger.checkpointProcess(task, resource, original, observed);
            var different = new ProcessRecovery(original.boot(), original.process(), OptionalLong.of(124),
                    observed.started());
            assertThrows(SQLException.class, () -> ledger.checkpointProcess(task, resource, original, different));
            assertThrows(SQLException.class,
                    () -> ledger.checkpointProcess(UUID.randomUUID(), resource, original, observed));
            assertThrows(SQLException.class, () -> ledger.checkpointProcess(task,
                    new TaskResource("PROCESS", resource.id(), "other", false), original, observed));
            assertFalse(ledger.complete(task, Outcome.SUCCEEDED, "{}", false));
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            assertEquals(TaskState.UNKNOWN, ledger.snapshot(task).state());
            assertEquals(observed,
                    ProcessRecovery.decode(ledger.recoveries(task, "PROCESS", "process-v1:").get(resource)));
        }
    }

    @Test
    void incompleteOrNoncanonicalIdentityIsNeverGuessed() {
        var original = new ProcessRecovery(UUID.randomUUID(), UUID.randomUUID(), OptionalLong.empty(),
                Optional.empty());
        assertEquals(original, ProcessRecovery.decode(original.encode()));
        for (var change : List.of(Map.entry("phase", "READY"), Map.entry("pid", "1"),
                Map.entry("bootId", "1-1-1-1-1"))) {
            var fields = new HashMap<>(original.encode().fields());
            fields.put(change.getKey(), change.getValue());
            assertThrows(IllegalArgumentException.class,
                    () -> ProcessRecovery.decode(new ResourceRecovery("process.v1", fields)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessRecovery(original.boot(), original.process(), OptionalLong.of(1), Optional.empty()));
    }
}
