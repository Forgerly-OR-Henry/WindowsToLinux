package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.deploy.contract.spi.CandidatePortMode;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentComponent;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentRequest;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationPort;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationRequest;
import gold.debug.windowstolinux.shared.model.deployment.ReleaseSetDigest;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;

class ManagedRestoreDeploymentPortTest {
    private static final String ARCHIVE = "a".repeat(64);

    private static final String OWNER = "b".repeat(64);

    @Test
    void performsCandidateAndFormalHealthInOrder() {
        RecordingRemote remote = new RecordingRemote(false);
        ManagedRestoreDeploymentPort port = new ManagedRestoreDeploymentPort(remote);
        RestoreDeploymentRequest request = request(true);

        assertTrue(port.verifyComponents(request, Optional.empty()).healthy());
        assertTrue(port.verifyApplication(request, Optional.empty()).healthy());
        var committed = port.commit(request, Optional.empty());

        assertTrue(committed.committed());
        assertTrue(committed.previousReleaseRetained());
        assertEquals(
                List.of("inspect", "start", "components", "application", "prepare-commit", "start-formal", "commit"),
                remote.calls);
        assertEquals(49153, remote.activation.components().getFirst().ports().getFirst().candidatePort());
    }

    @Test
    void exposesFormalHealthFailureAsAnUncommittedResult() {
        RecordingRemote remote = new RecordingRemote(true);
        ManagedRestoreDeploymentPort port = new ManagedRestoreDeploymentPort(remote);
        RestoreDeploymentRequest request = request(true);
        assertTrue(port.verifyComponents(request, Optional.empty()).healthy());
        assertTrue(port.verifyApplication(request, Optional.empty()).healthy());
        assertFalse(port.commit(request, Optional.empty()).committed());
    }

    @Test
    void refusesActivationWithoutExactStagedInputManifest() {
        RecordingRemote remote = new RecordingRemote(false);
        ManagedRestoreDeploymentPort port = new ManagedRestoreDeploymentPort(remote);
        assertThrows(IllegalStateException.class, () -> port.verifyComponents(request(false), Optional.empty()));
        assertEquals(List.of("inspect"), remote.calls);
    }

    @Test
    void usesWholeApplicationShortStopWhenOneRuntimeIsOpaque() {
        RecordingRemote remote = new RecordingRemote(false);
        ManagedRestoreDeploymentPort port = new ManagedRestoreDeploymentPort(remote);
        var component = component(new DeploymentRuntimeSpecification.NodeService(20, http(8080)), true);
        RestoreDeploymentRequest request = request(List.of(component));
        assertTrue(port.verifyComponents(request, Optional.empty()).healthy());
        assertEquals(gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationMode.SHORT_STOP,
                remote.activation.mode());
        assertTrue(remote.activation.components().getFirst().ports().isEmpty());
    }

    @Test
    void databaseActivationForcesStoppedWritePreparationBeforeFormalHealth() {
        RecordingRemote remote = new RecordingRemote(false);
        ManagedRestoreDeploymentPort port = new ManagedRestoreDeploymentPort(remote);
        RestoreDeploymentRequest request = request(true);
        Optional<String> databaseToken = Optional.of("database-candidate");

        assertTrue(port.prepareCommit(request, databaseToken).healthy());
        assertTrue(port.verifyComponents(request, databaseToken).healthy());

        assertEquals(gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationMode.SHORT_STOP,
                remote.activation.mode());
        assertEquals(List.of("inspect", "start", "prepare-commit", "start-formal", "components"), remote.calls);
    }

    private static RestoreDeploymentRequest request(boolean inputs) {
        return request(List.of(component(new DeploymentRuntimeSpecification.StaticSite("public", http(8080)), inputs)));
    }

    private static RestoreDeploymentRequest request(List<RestoreDeploymentComponent> components) {
        String releaseSet = ReleaseSetDigest.sha256(components.stream().map(
                component -> new ReleaseSetDigest.ComponentRelease(component.componentId(), component.releaseSha256()))
                .toList());
        return new RestoreDeploymentRequest("demo", "server", "demo-" + ARCHIVE.substring(0, 16), ARCHIVE, releaseSet,
                List.of(), "/var/lib/windowstolinux/work/demo-" + ARCHIVE.substring(0, 16) + "/mutable/restore",
                ARCHIVE.substring(0, 32), components, "web", http(8080));
    }

    private static RestoreDeploymentComponent component(DeploymentRuntimeSpecification runtime, boolean inputs) {
        String release = "c".repeat(64);
        return new RestoreDeploymentComponent("web", "managed-web", OWNER, release, List.of(), "releases/web.pax",
                "config/web.bin", "runtime/web.bin", List.of(), runtime,
                inputs ? Optional.of(new DeploymentInputManifest("d".repeat(64), List.of())) : Optional.empty(),
                List.of("data/web/files/uploads.pax"), Optional.empty());
    }

    private static HealthCheck.Http http(int port) {
        return new HealthCheck.Http(URI.create("http://127.0.0.1:" + port + "/health"), 200, 10);
    }

    private static final class RecordingRemote implements RemoteRestoreActivationPort {
        private final List<String> calls = new ArrayList<>();

        private final boolean formalFailure;

        private RemoteRestoreActivationRequest activation;

        private RecordingRemote(boolean formalFailure) {
            this.formalFailure = formalFailure;
        }

        @Override
        public PreflightEvidence inspectRestoreActivation(String applicationId, long requiredBytes) {
            calls.add("inspect");
            return new PreflightEvidence(true, false, 1_000_000, Set.of(49152), List.of("preflight"));
        }

        @Override
        public StepEvidence startRestoreActivation(RemoteRestoreActivationRequest request) {
            calls.add("start");
            activation = request;
            return new StepEvidence(true, List.of("started"));
        }

        @Override
        public StepEvidence prepareRestoreCommit(RemoteRestoreActivationRequest request) {
            calls.add("prepare-commit");
            return new StepEvidence(true, List.of("quiesced"));
        }

        @Override
        public StepEvidence startRestoreFormal(RemoteRestoreActivationRequest request) {
            calls.add("start-formal");
            return new StepEvidence(true, List.of("formal started"));
        }

        @Override
        public StepEvidence verifyRestoreComponents(RemoteRestoreActivationRequest request) {
            calls.add("components");
            return new StepEvidence(true, List.of("components healthy"));
        }

        @Override
        public StepEvidence verifyRestoreApplication(RemoteRestoreActivationRequest request) {
            calls.add("application");
            return new StepEvidence(true, List.of("application healthy"));
        }

        @Override
        public CommitEvidence commitRestoreActivation(RemoteRestoreActivationRequest request) {
            calls.add("commit");
            return new CommitEvidence(!formalFailure, true, !formalFailure, !formalFailure,
                    "demo-" + ARCHIVE.substring(0, 16), List.of("formal health"));
        }

        @Override
        public StepEvidence quiesceRestoreRecovery(RemoteRestoreActivationRequest request) {
            calls.add("quiesce-recovery");
            return new StepEvidence(true, List.of("recovery quiesced"));
        }

        @Override
        public RecoveryEvidence recoverRestoreActivation(RemoteRestoreActivationRequest request) {
            calls.add("recover");
            return new RecoveryEvidence(true, true, List.of("recovered"));
        }
    }
}
