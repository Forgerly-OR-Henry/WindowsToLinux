package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.app.db.ModActivationLedgerTest.sql;

import java.nio.file.Path;
import java.util.*;
import java.sql.SQLException;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMaterialTransitionTest {
    @TempDir
    Path root;

    static final String OWNER = "example.consumer", PRODUCER = "example.provider";

    static ResourceRecovery header(UUID task, UUID material, UUID workspace) {
        var f = new LinkedHashMap<String, String>();
        f.put("materialId", material.toString());
        f.put("operationId", UUID.randomUUID().toString());
        f.put("taskId", task.toString());
        for (String role : List.of("producer", "consumer")) {
            f.put(role + "Id", role.equals("producer") ? PRODUCER : OWNER);
            f.put(role + "Version", "1.0.0");
            f.put(role + "Digest", (role.equals("producer") ? "b" : "a").repeat(64));
            f.put(role + "SchemaVersion", "");
            f.put(role + "SchemaGeneration", "");
        }
        f.put("workspaceId", workspace.toString());
        f.put("physicalOwnerId", PRODUCER);
        f.put("physicalIdentity", "fixture-custody");
        f.put("revision", "0123456789abcdef");
        f.put("manifestSha256", "");
        f.put("byteCount", "0");
        f.put("fileCount", "0");
        f.put("state", "CAPTURING");
        f.put("recordRevision", "0");
        return new ResourceRecovery("material.v1", f);
    }

    static ResourceRecovery next(ResourceRecovery original, String state) {
        var f = new HashMap<>(original.fields());
        f.put("state", state);
        f.put("recordRevision", Long.toString(Long.parseLong(f.get("recordRevision")) + 1));
        if (state.equals("PUBLISHED")) {
            f.put("manifestSha256", "c".repeat(64));
            f.put("byteCount", "20000000");
            f.put("fileCount", "1");
        }
        return new ResourceRecovery("material.v1", f);
    }

    @Test
    void unpublishedReleaseRequiresExactWorkspaceAndNoProducerProcessAndRollsBackBothRows() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var graph = new TaskRecoveryGraph(OWNER, List.of(new TaskRecoveryGraph.Release(OWNER, "1.0.0", "a".repeat(64), Optional.empty()),
                new TaskRecoveryGraph.Release(PRODUCER, "1.0.0", "b".repeat(64), Optional.empty())),
                List.of(new TaskRecoveryGraph.Edge(OWNER, PRODUCER, TaskRecoveryGraph.EdgeKind.OPTIONAL_USED)));
            UUID task = ledger.enqueue(UUID.randomUUID(), OWNER, "1.0.0", new TaskRequest("material", "material", List.of(), false), TaskOptionalDependencyTest.policy(graph, graph.claims()));
            assertTrue(ledger.tryStart(task));
            UUID workspaceId = UUID.randomUUID(), materialId = UUID.randomUUID();
            var workspace = new TaskResource("FILE", "source-workspace-v1:" + workspaceId, PRODUCER, false);
            UUID workspaceLease = ledger.retain(task, workspace);
            var material = new TaskResource("FILE", "material-v1:" + materialId, OWNER, false);
            var capturing = header(task, materialId, workspaceId);
            UUID rootLease = ledger.retain(task, material, capturing, true);
            var requested = next(capturing, "RELEASE_REQUESTED");
            var released = next(requested, "RELEASED");
            ledger.materials().checkpointFileMaterial(task, rootLease, material, capturing, requested);
            assertThrows(SQLException.class, () -> ledger.materials().releaseMaterialRoot(task, rootLease, material, requested, released));
            var process = new TaskResource("PROCESS", "producer-writer", PRODUCER, false);
            UUID processLease = ledger.retain(task, process);
            assertThrows(SQLException.class, () -> ledger.materials().releaseUnpublishedMaterial(task, workspaceLease, workspace, rootLease, material, requested, released));
            ledger.release(task, processLease);
            sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_unpublished CHECK(resource_id NOT LIKE 'source-workspace-v1:%' OR released_at IS NULL)");
            assertThrows(SQLException.class, () -> ledger.materials().releaseUnpublishedMaterial(task, workspaceLease, workspace, rootLease, material, requested, released));
            assertTrue(ledger.queries().holds(task, workspace));
            assertTrue(ledger.queries().holds(task, material));
            assertEquals(requested, ledger.queries().recoveries(task, "FILE", "material-v1:").get(material));
            sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_unpublished");
            ledger.materials().releaseUnpublishedMaterial(task, workspaceLease, workspace, rootLease, material, requested, released);
            ledger.materials().releaseUnpublishedMaterial(task, workspaceLease, workspace, rootLease, material, requested, released);
            ledger.release(task, workspaceLease);
            assertFalse(ledger.queries().holds(task, workspace));
            assertFalse(ledger.queries().holds(task, material));
        }
    }

    @Test
    void custodyDetachAndHoldVsReleaseAreAtomicAndOldBootHoldsNeverExpire() throws Exception {
        UUID task, rootLease, holdLease;
        TaskResource material, hold;
        ResourceRecovery published, active, requested;
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var graph = new TaskRecoveryGraph(OWNER, List.of(new TaskRecoveryGraph.Release(OWNER, "1.0.0", "a".repeat(64), Optional.empty()),
                new TaskRecoveryGraph.Release(PRODUCER, "1.0.0", "b".repeat(64), Optional.empty())),
                List.of(new TaskRecoveryGraph.Edge(OWNER, PRODUCER, TaskRecoveryGraph.EdgeKind.OPTIONAL_USED)));
            task = ledger.enqueue(UUID.randomUUID(), OWNER, "1.0.0", new TaskRequest("material", "material", List.of(), false), TaskOptionalDependencyTest.policy(graph, graph.claims()));
            assertTrue(ledger.tryStart(task));
            UUID workspaceId = UUID.randomUUID(), materialId = UUID.randomUUID();
            var workspace = new TaskResource("FILE", "source-workspace-v1:" + workspaceId, PRODUCER, false);
            UUID workspaceLease = ledger.retain(task, workspace);
            material = new TaskResource("FILE", "material-v1:" + materialId, OWNER, false);
            var capturing = header(task, materialId, workspaceId);
            published = next(capturing, "PUBLISHED");
            rootLease = ledger.retain(task, material, capturing, true);
            sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_detach CHECK(resource_id NOT LIKE 'source-workspace-v1:%' OR released_at IS NULL)");
            assertThrows(SQLException.class, () -> ledger.materials().transferSourceWorkspace(task, workspaceLease, workspace, rootLease, material, capturing, published));
            assertTrue(ledger.queries().holds(task, workspace));
            assertEquals(capturing, ledger.queries().recoveries(task, "FILE", "material-v1:").get(material));
            sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_detach");
            ledger.materials().transferSourceWorkspace(task, workspaceLease, workspace, rootLease, material, capturing, published);
            ledger.materials().transferSourceWorkspace(task, workspaceLease, workspace, rootLease, material, capturing, published);
            assertFalse(ledger.queries().holds(task, workspace));
            UUID holdId = UUID.randomUUID();
            hold = new TaskResource("FILE", "material-hold-v1:" + materialId + "/" + holdId, OWNER, false);
            active = new ResourceRecovery("material-hold.v1", Map.of("materialId", materialId.toString(), "holdId", holdId.toString(), "taskId", task.toString(),
                "consumerId", OWNER, "consumerVersion", "1.0.0", "materialDigest", "c".repeat(64), "revision", published.fields().get("revision"), "bootId", db.bootId().toString(), "state", "ACTIVE"));
            holdLease = ledger.materials().acquireMaterialHold(task, rootLease, material, published, hold, active);
            requested = next(published, "RELEASE_REQUESTED");
            ledger.materials().checkpointFileMaterial(task, rootLease, material, published, requested);
            assertThrows(SQLException.class, () -> ledger.materials().acquireMaterialHold(task, rootLease, material, published, hold, active));
            var released = next(requested, "RELEASED");
            assertThrows(SQLException.class, () -> ledger.materials().releaseMaterialRoot(task, rootLease, material, requested, released));
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            assertTrue(ledger.queries().holds(task, hold));
            assertNotEquals(db.bootId().toString(), active.fields().get("bootId"));
            assertThrows(SQLException.class, () -> ledger.materials().releaseMaterialRoot(task, rootLease, material, requested, next(requested, "RELEASED")));
            var f = new HashMap<>(active.fields());
            f.put("state", "RELEASED");
            var closed = new ResourceRecovery("material-hold.v1", f);
            ledger.materials().releaseMaterialHold(task, holdLease, hold, active, closed);
            ledger.materials().releaseMaterialHold(task, holdLease, hold, active, closed);
            ledger.materials().releaseMaterialRoot(task, rootLease, material, requested, next(requested, "RELEASED"));
            assertFalse(ledger.queries().holds(task, material));
        }
    }
}
