package gold.debug.windowstolinux.mod.deployment.service.core.collect;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import org.junit.jupiter.api.Test;

/** Actual producer and status receipts must agree before deriving a physical artifact address. */
class DeploymentCollectionLayoutTest {
    private static final DeploymentRemoteLayout LAYOUT = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
        "/srv/wtl/helper", "/srv/wtl/apps", "/srv/wtl/state", "/srv/wtl/config", "/srv/wtl/data"));
    private static final String OPERATION = "backup-" + "1".repeat(32);

    @Test
    void completedArchiveAndStatusResolveOnlyUnderCapturedBackupRoot() {
        var plan = plan("release", "release");
        String id = "artifact-" + DeploymentCollectionEvidence.digest32("demo", "main", "demo", "e".repeat(64), "release", "release");
        String body = "ARTIFACT=" + id + "\nKIND=release\nSIZE=64\nSHA256=" + "a".repeat(64) + "\n";
        var produced = DeploymentCollectionEvidence.parseArtifact(reply(body), plan, OPERATION, "demo", LAYOUT).orElseThrow();
        var status = DeploymentCollectionEvidence.parseStatus(reply("HANDOFF_BACKUP=1\nPRESENT=1\nCOMPLETE=1\n" + body),
            plan, OPERATION, "demo", LAYOUT).orElseThrow();
        assertEquals(produced, status);
        assertEquals("/srv/wtl/state/backups/" + OPERATION + "/" + id + ".pax", produced.remotePath());
        assertEquals("/var/lib/windowstolinux/backups/" + OPERATION + "/" + id + ".pax",
            DeploymentCollectionEvidence.parseArtifact(reply(body), plan, OPERATION, "demo").orElseThrow().remotePath());
        assertTrue(DeploymentCollectionEvidence.parseStatus(reply("HANDOFF_BACKUP=1\nPRESENT=1\nCOMPLETE=0\n" + body),
            plan, OPERATION, "demo", LAYOUT).isEmpty());
    }

    @Test
    void databaseReceiptKeepsOriginalArtifactIdentityAndRejectsOperationDrift() {
        var plan = plan("database", "orders");
        String id = "db-" + DeploymentCollectionEvidence.digest32(OPERATION, "demo", "main", "demo", "e".repeat(64), "orders");
        String body = "HANDOFF_DATABASE=1\nARTIFACT_ID=" + id + "\nBYTE_COUNT=64\nSHA256=" + "a".repeat(64) + "\n";
        var produced = DeploymentCollectionEvidence.parseArtifact(reply(body), plan, OPERATION, "demo", LAYOUT).orElseThrow();
        assertEquals("/srv/wtl/state/backups/" + id, produced.remotePath());
        assertTrue(DeploymentCollectionEvidence.parseArtifact(reply(body), plan, "backup-" + "2".repeat(32), "demo", LAYOUT).isEmpty());
        assertTrue(DeploymentCollectionEvidence.parseArtifact(reply(body + "SHA256=" + "a".repeat(64) + "\n"),
            plan, OPERATION, "demo", LAYOUT).isEmpty());
    }

    private static DeploymentCollectionPlan.ArtifactPlan plan(String kind, String resource) {
        return new DeploymentCollectionPlan.ArtifactPlan("main:" + resource, "main", "demo", "e".repeat(64),
            "b".repeat(64), kind, resource, "4096", List.of());
    }

    private static OperationResult<CommandResult> reply(String stdout) {
        return new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new CommandResult(Optional.of(0), stdout, "", false)), Optional.empty());
    }
}
