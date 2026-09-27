package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyRecoveryLedgerTest {
    @TempDir
    Path root;

    DependencyRecovery call(PlatformDatabase db, SchemaIdentity schema) {
        return new DependencyRecovery(db.bootId(), UUID.randomUUID(), schema.ownerId(), "1.0.0", Optional.of(schema));
    }

    @Test
    void independentGroupsAcquireAndReleaseAtomically() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var task = new TaskLedgerTest().queue(ledger, "caller");
            assertTrue(ledger.tryStart(task));
            var schema = new SchemaIdentity("provider", "1", UUID.randomUUID());
            var first = call(db, schema);
            var second = call(db, schema);
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute(
                        "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_acquire CHECK(resource_kind<>'SCHEMA')");
            }
            assertThrows(SQLException.class, () -> ledger.retainDependency(task, first, c -> first.claims()));
            assertTrue(ledger.dependencies(task).isEmpty());
            assertFalse(ledger.holds(task, first.claims().getFirst()));
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute("ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_acquire");
            }
            var one = ledger.retainDependency(task, first, c -> first.claims());
            var two = ledger.retainDependency(task, second, c -> second.claims());
            assertEquals(2, ledger.dependencies(task).size());
            assertThrows(SQLException.class, () -> ledger.releaseDependency(task, first, two));
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute(
                        "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_release CHECK(resource_kind<>'SCHEMA' OR released_at IS NULL)");
            }
            assertThrows(SQLException.class, () -> ledger.releaseDependency(task, first, one));
            assertEquals(2, ledger.dependencies(task).size());
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute("ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_release");
            }
            ledger.releaseDependency(task, first, one);
            ledger.releaseDependency(task, first, one);
            assertEquals(Set.of(second), ledger.dependencies(task).keySet());
            assertTrue(ledger.holds(task, first.claims().getFirst()));
            assertFalse(ledger.complete(task, Outcome.SUCCEEDED, "{}", false));
            ledger.releaseDependency(task, second, two);
            assertTrue(ledger.complete(task, Outcome.SUCCEEDED, "{}", false));
        }
    }

    @Test
    void originalProviderAndSshOwnershipSurviveReopenAndCorruptionBlocksRelease() throws Exception {
        UUID task;
        DependencyRecovery original;
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            task = new TaskLedgerTest().queue(ledger, "caller");
            assertTrue(ledger.tryStart(task));
            original = call(db, new SchemaIdentity("provider", "1", UUID.randomUUID()));
            ledger.retainDependency(task, original, c -> original.claims());
            for (String owner : List.of("caller", "provider"))
                ledger.retain(task, new TaskResource("CONNECTION", "ssh-" + UUID.randomUUID(), owner, false),
                        new ResourceRecovery("ssh.v1", Map.of("bootId", db.bootId().toString(), "serverId", "server")));
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            assertEquals(Set.of(original), ledger.dependencies(task).keySet());
            assertThrows(SQLException.class, () -> ledger.releasePreviousSshConnections(task, "provider", "2.0.0"));
            ledger.releasePreviousSshConnections(task, "provider", "1.0.0");
            assertFalse(ledger.hasDependencyResources(task, Set.of("provider")));
            assertTrue(ledger.hasDependencyResources(task, Set.of("caller")));
            ledger.releasePreviousSshConnections(task, "caller", "1.0.0");
            try (var c = db.openConnection();
                    var p = c.prepareStatement(
                            "UPDATE platform_task_resource SET owner_id='different' WHERE task_id=? AND resource_kind='SCHEMA'")) {
                p.setObject(1, task);
                p.executeUpdate();
            }
            assertThrows(SQLException.class, () -> ledger.dependencies(task));
        }
    }

    @Test
    void recoveryIdentityRejectsMissingExtraAndNoncanonicalFacts() {
        var original = new DependencyRecovery(UUID.randomUUID(), UUID.randomUUID(), "provider", "1.0.0",
                Optional.empty());
        assertEquals(original, DependencyRecovery.decode(original.encode()));
        for (var change : List.of(Map.entry("bootId", "1-1-1-1-1"),
                Map.entry("schemaGeneration", UUID.randomUUID().toString()), Map.entry("extra", "value"))) {
            var fields = new HashMap<>(original.encode().fields());
            fields.put(change.getKey(), change.getValue());
            assertThrows(IllegalArgumentException.class,
                    () -> DependencyRecovery.decode(new ResourceRecovery("service.v1", fields)));
        }
    }
}
