package gold.debug.windowstolinux.mod.deployment.service.core.restore;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import org.junit.jupiter.api.Test;

class DeploymentRestoreDirectoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CANDIDATE = "app-" + "a".repeat(16);
    private static final DeploymentRemoteLayout CUSTOM = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
        "/srv/wtl.helper", "/srv/wtl.apps", "/srv/wtl.state", "/srv/wtl.config", "/srv/wtl.data"));

    @Test
    void currentCandidateUsesWorkAndRequiresItsFrozenCompletePath() {
        var unit = JSON.createObjectNode().put("candidate", CANDIDATE).put("candidatePathVersion", 2)
            .put("candidateRoot", CUSTOM.candidateRoot(CANDIDATE));
        assertEquals("/srv/wtl.state/work/" + CANDIDATE, DeploymentRestoreSteps.candidateRoot(unit, CUSTOM));
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentRestoreSteps.candidateRoot(unit, DeploymentRemoteLayout.original()));
        unit.put("candidateRoot", "/srv/wtl.state/candidates/" + CANDIDATE);
        assertThrows(IllegalArgumentException.class, () -> DeploymentRestoreSteps.candidateRoot(unit, CUSTOM));
        unit.remove("candidateRoot");
        assertThrows(IllegalArgumentException.class, () -> DeploymentRestoreSteps.candidateRoot(unit, CUSTOM));
    }

    @Test
    void legacyCandidateKeepsItsActualOriginalDestination() {
        var unit = JSON.createObjectNode().put("candidate", CANDIDATE);
        assertEquals("/var/lib/windowstolinux/candidates/" + CANDIDATE,
            DeploymentRestoreSteps.candidateRoot(unit, DeploymentRemoteLayout.original()));
        assertThrows(IllegalArgumentException.class, () -> DeploymentRestoreSteps.candidateRoot(unit, CUSTOM));
    }

    @Test
    void pendingTransferCannotChangeLocalEndpointDestinationOrBudget() {
        var request = new TransferRequest(Path.of("original.tar"), CUSTOM.candidateRoot(CANDIDATE) + "/mutable/restore/data", 42);
        var file = JSON.createObjectNode().put("transferPathVersion", 2).put("localPath", request.localPath().toString())
            .put("remotePath", request.remoteAbsolutePath()).put("maximumBytes", request.maxBytes());
        assertTrue(DeploymentRestoreTransfers.frozenTransferMatches(file, request));
        assertFalse(DeploymentRestoreTransfers.frozenTransferMatches(file,
            new TransferRequest(Path.of("other.tar"), request.remoteAbsolutePath(), 42)));
        assertFalse(DeploymentRestoreTransfers.frozenTransferMatches(file,
            new TransferRequest(request.localPath(), request.remoteAbsolutePath() + "-other", 42)));
        assertFalse(DeploymentRestoreTransfers.frozenTransferMatches(file,
            new TransferRequest(request.localPath(), request.remoteAbsolutePath(), 43)));
        file.remove("remotePath");
        assertFalse(DeploymentRestoreTransfers.frozenTransferMatches(file, request));
    }
}
