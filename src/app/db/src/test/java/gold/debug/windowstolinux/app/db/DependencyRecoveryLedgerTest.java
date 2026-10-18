package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.DependencyRecovery;
import gold.debug.windowstolinux.app.db.task.ResourceRecovery;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyRecoveryLedgerTest {
    @TempDir
    Path root;

    DependencyRecovery call(PlatformDatabase db, SchemaIdentity schema) {
        return new DependencyRecovery(db.bootId(), UUID.randomUUID(), schema.ownerId(), "1.0.0", Optional.of(schema),
            "caller", "1.0.0", "a".repeat(64));
    }

    @Test
    void oldServiceCheckpointCannotBeReadOrEncodedWithoutCallerProof() {
        var current = new DependencyRecovery(UUID.randomUUID(), UUID.randomUUID(), "provider", "1.0.0",
            Optional.empty(), "caller", "1.0.0", "a".repeat(64));
        assertEquals(current, DependencyRecovery.decode(current.encode()));
        assertThrows(IllegalArgumentException.class,
            () -> DependencyRecovery.decode(new ResourceRecovery("service.v1", current.encode().fields())));
        assertThrows(IllegalArgumentException.class, () -> new DependencyRecovery(current.boot(), current.call(),
            current.provider(), current.version(), current.schema(), "", "", ""));
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
            assertThrows(SQLException.class, () -> ledger.dependencies().retainDependency(task, first, c -> first.claims()));
            assertTrue(ledger.queries().dependencies(task).isEmpty());
            assertFalse(ledger.queries().holds(task, first.claims().getFirst()));
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute("ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_acquire");
            }
            var one = ledger.dependencies().retainDependency(task, first, c -> first.claims());
            var two = ledger.dependencies().retainDependency(task, second, c -> second.claims());
            assertEquals(2, ledger.queries().dependencies(task).size());
            assertThrows(SQLException.class, () -> ledger.dependencies().releaseDependency(task, first, two));
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute(
                    "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_release CHECK(resource_kind<>'SCHEMA' OR released_at IS NULL)");
            }
            assertThrows(SQLException.class, () -> ledger.dependencies().releaseDependency(task, first, one));
            assertEquals(2, ledger.queries().dependencies(task).size());
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute("ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_release");
            }
            ledger.dependencies().releaseDependency(task, first, one);
            ledger.dependencies().releaseDependency(task, first, one);
            assertEquals(Set.of(second), ledger.queries().dependencies(task).keySet());
            assertTrue(ledger.queries().holds(task, first.claims().getFirst()));
            assertFalse(ledger.complete(task, TaskResult.SUCCEEDED, "{}", false));
            ledger.dependencies().releaseDependency(task, second, two);
            assertTrue(ledger.complete(task, TaskResult.SUCCEEDED, "{}", false));
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
            ledger.dependencies().retainDependency(task, original, c -> original.claims());
            for (String owner : List.of("caller", "provider"))
                ledger.retain(task, new TaskResource("CONNECTION", "ssh-" + UUID.randomUUID(), owner, false),
                    new ResourceRecovery("ssh.v1", Map.of("bootId", db.bootId().toString(), "serverId", "server")),
                    false);
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            assertEquals(Set.of(original), ledger.queries().dependencies(task).keySet());
            assertThrows(SQLException.class, () -> ledger.releasePreviousSshConnections(task, "provider", "2.0.0"));
            ledger.releasePreviousSshConnections(task, "provider", "1.0.0");
            assertFalse(ledger.queries().hasDependencyResources(task, Set.of("provider")));
            assertTrue(ledger.queries().hasDependencyResources(task, Set.of("caller")));
            ledger.releasePreviousSshConnections(task, "caller", "1.0.0");
            try (var c = db.openConnection();
                 var p = c.prepareStatement(
                     "UPDATE platform_task_resource SET owner_id='different' WHERE task_id=? AND resource_kind='SCHEMA'")) {
                p.setObject(1, task);
                p.executeUpdate();
            }
            assertThrows(SQLException.class, () -> ledger.queries().dependencies(task));
        }
    }

    @Test
    void previousAiProcessReleasePreservesCurrentAndBusinessOwnership() throws Exception {
        UUID task;
        var access = new TaskResource("CUSTOM", "ai-access-" + UUID.randomUUID(), "caller", false);
        var http = new TaskResource("CONNECTION", "ai-http-" + UUID.randomUUID(), "caller", false);
        var business = new TaskResource("CUSTOM", "caller/checkpoint/original", "caller", false);
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            task = new TaskLedgerTest().queue(ledger, "caller");
            assertTrue(ledger.tryStart(task));
            ledger.retain(task, access);
            ledger.retain(task, business);
            ledger.retain(task, http, new ResourceRecovery("ai-process.v1", Map.of("bootId", db.bootId().toString(), "kind", "http")), false);
            ledger.releasePreviousAiResources(task, "caller", "1.0.0");
            assertTrue(ledger.queries().holds(task, access));
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var current = new TaskResource("CUSTOM", "ai-access-" + UUID.randomUUID(), "caller", false);
            ledger.retain(task, current, new ResourceRecovery("ai-process.v1", Map.of("bootId", db.bootId().toString(), "kind", "access")), false);
            assertThrows(SQLException.class, () -> ledger.releasePreviousAiResources(task, "caller", "2.0.0"));
            assertThrows(SQLException.class, () -> ledger.releasePreviousAiResources(task, "different", "1.0.0"));
            ledger.releasePreviousAiResources(task, "caller", "1.0.0");
            assertFalse(ledger.queries().holds(task, access));
            assertFalse(ledger.queries().holds(task, http));
            assertTrue(ledger.queries().holds(task, current));
            assertTrue(ledger.queries().holds(task, business));
        }
    }

    @Test
    void previousServerAccessReleasePreservesCurrentProcessAndBusinessResources() throws Exception {
        UUID task;
        var legacy = new TaskResource("CUSTOM", "server-access-" + UUID.randomUUID(), "caller", false);
        var tagged = new TaskResource("CUSTOM", "server-access-" + UUID.randomUUID(), "caller", false);
        var business = new TaskResource("CUSTOM", "caller/checkpoint/original", "caller", false);
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            task = new TaskLedgerTest().queue(ledger, "caller");
            assertTrue(ledger.tryStart(task));
            ledger.retain(task, legacy);
            ledger.retain(task, tagged, new ResourceRecovery("server-process.v1", Map.of("bootId", db.bootId().toString())), false);
            ledger.retain(task, business);
            ledger.releasePreviousServerAccesses(task, "caller", "1.0.0");
            assertTrue(ledger.queries().holds(task, legacy));
            assertTrue(ledger.queries().holds(task, tagged));
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var current = new TaskResource("CUSTOM", "server-access-" + UUID.randomUUID(), "caller", false);
            ledger.retain(task, current, new ResourceRecovery("server-process.v1", Map.of("bootId", db.bootId().toString())), false);
            assertThrows(SQLException.class, () -> ledger.releasePreviousServerAccesses(task, "other", "1.0.0"));
            assertThrows(SQLException.class, () -> ledger.releasePreviousServerAccesses(task, "caller", "2.0.0"));
            ledger.releasePreviousServerAccesses(task, "caller", "1.0.0");
            assertFalse(ledger.queries().holds(task, legacy));
            assertFalse(ledger.queries().holds(task, tagged));
            assertTrue(ledger.queries().holds(task, current));
            assertTrue(ledger.queries().holds(task, business));
            assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.UNKNOWN, ledger.queries().snapshot(task).state());
        }
    }

    @Test
    void malformedServerProcessProofRollsBackAllMatchingReleases() throws Exception {
        UUID task;
        var legacy = new TaskResource("CUSTOM", "server-access-" + UUID.randomUUID(), "caller", false);
        var corrupt = new TaskResource("CUSTOM", "server-access-" + UUID.randomUUID(), "caller", false);
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            task = new TaskLedgerTest().queue(ledger, "caller");
            assertTrue(ledger.tryStart(task));
            ledger.retain(task, legacy);
            ledger.retain(task, corrupt, new ResourceRecovery("server-process.v1", Map.of("bootId", "invalid")), false);
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            assertThrows(SQLException.class, () -> ledger.releasePreviousServerAccesses(task, "caller", "1.0.0"));
            assertTrue(ledger.queries().holds(task, legacy));
            assertTrue(ledger.queries().holds(task, corrupt));
        }
    }

    @Test
    void corruptAiProcessProofPreventsAnyMatchingRelease() throws Exception {
        UUID task;
        var access = new TaskResource("CUSTOM", "ai-access-" + UUID.randomUUID(), "caller", false);
        var malformed = new TaskResource("CONNECTION", "ai-http-" + UUID.randomUUID(), "caller", false);
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            task = new TaskLedgerTest().queue(ledger, "caller");
            assertTrue(ledger.tryStart(task));
            ledger.retain(task, access);
            ledger.retain(task, malformed, new ResourceRecovery("ai-process.v1", Map.of("bootId", "invalid", "kind", "http")), false);
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            assertThrows(SQLException.class, () -> ledger.releasePreviousAiResources(task, "caller", "1.0.0"));
            assertTrue(ledger.queries().holds(task, access));
            assertTrue(ledger.queries().holds(task, malformed));
        }
    }

    @Test
    void recoveryIdentityRejectsMissingExtraAndNoncanonicalFacts() {
        var original = new DependencyRecovery(UUID.randomUUID(), UUID.randomUUID(), "provider", "1.0.0",
            Optional.empty(), "caller", "1.0.0", "a".repeat(64));
        assertEquals(original, DependencyRecovery.decode(original.encode()));
        for (var change : List.of(Map.entry("bootId", "1-1-1-1-1"),
            Map.entry("schemaGeneration", UUID.randomUUID().toString()), Map.entry("extra", "value"))) {
            var fields = new HashMap<>(original.encode().fields());
            fields.put(change.getKey(), change.getValue());
            assertThrows(IllegalArgumentException.class,
                () -> DependencyRecovery.decode(new ResourceRecovery("service.v2", fields)));
        }
    }
}
