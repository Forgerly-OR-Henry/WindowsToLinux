package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentAgentDirectoryBootstrapTest {
    private static final DeploymentRemoteLayout LAYOUT = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
        "/srv/wtl/helper", "/srv/wtl/apps", "/srv/wtl/state", "/srv/wtl/config", "/srv/wtl/data"));
    private static final ServerId SERVER = new ServerId("controlled-server");

    @Test
    void canonicalInstallerEvidenceAndEffectTargetsBindAllRootsAndRecoveryAnchor() {
        String operation = "a".repeat(32);
        String source = DeploymentAgentHelperInstallerScript.render(operation, LAYOUT);
        assertEquals(operation, DeploymentAgentHelperInstallerScript.parseOperation(source, LAYOUT));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHelperInstallerScript.parseOperation(source));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHelperInstallerScript.parseOperation(source + "\n# altered", LAYOUT));
        assertFalse(DeploymentAgentHelperBootstrapEvidence.describe(source, LAYOUT).isEmpty());
        assertFalse(DeploymentAgentHelperBootstrapEvidence.navigation(source, LAYOUT).isEmpty());
        try (var request = new CommandRequest(DeploymentAgentHelperInstallerScript.LAUNCHER, source.getBytes(StandardCharsets.UTF_8), Duration.ofHours(2), 131072)) {
            var original = RemoteActions.command(SERVER, "b".repeat(64), request).withSemantics(new ActionSemantics(
                DeploymentAgentHelperBootstrapEvidence.OPERATION, gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper.installationTargets(SERVER, LAYOUT)));
            var reviewed = DeploymentAgentHelperBootstrapEvidence.upgrade(original, request, SERVER, "b".repeat(64), operation, LAYOUT);
            assertEquals(original.exactAction(), reviewed.exactAction());
            assertEquals(DeploymentAgentHelperBootstrapEvidence.targets(SERVER, LAYOUT), reviewed.semantics().orElseThrow().targets());
            for (String root : List.of(LAYOUT.paths().helperDirectory(), LAYOUT.paths().applicationsRoot(), LAYOUT.paths().stateRoot(), LAYOUT.paths().configurationRoot(), LAYOUT.paths().dataRoot()))
                assertTrue(reviewed.semantics().orElseThrow().targets().contains(new ActionTarget(SERVER.id(), "directory", root)));
            assertTrue(reviewed.semantics().orElseThrow().targets().contains(new ActionTarget(SERVER.id(), "file", DeploymentRemoteLayoutAdmission.UNIT_PATH)));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHelperBootstrapEvidence.upgrade(original, request, SERVER, "b".repeat(64), operation));
        }
    }

    @Test
    void readinessChecksSelectedFilesAllRootsAndCanonicalRecoveryUnitWithoutInstallingAnything() {
        String source = DeploymentAgentHelperReadiness.script(LAYOUT);
        assertTrue(source.contains("managed_file '" + LAYOUT.helperPath() + "' '" + DeploymentManagedHelperBundle.helperSha256(LAYOUT) + "'"));
        assertTrue(source.contains("managed_file '" + LAYOUT.buildEntry() + "' '" + DeploymentManagedHelperBundle.buildEntrySha256(LAYOUT) + "'"));
        for (String path : List.of(LAYOUT.workRoot(), LAYOUT.snapshotsRoot(), LAYOUT.backupsRoot(), LAYOUT.paths().configurationRoot(), LAYOUT.paths().dataRoot(), LAYOUT.helperInstallOperationsRoot()))
            assertTrue(source.contains(path));
        assertTrue(source.contains("layout_unit=" + DeploymentRemoteLayoutAdmission.UNIT_PATH));
        assertTrue(source.contains("--property=FragmentPath --value"));
        assertTrue(source.contains("--property=DropInPaths --value"));
        assertFalse(source.contains("/usr/local/lib/windowstolinux"));
        assertFalse(source.contains("systemctl enable"));
        assertFalse(source.contains("systemctl daemon-reload"));
        assertFalse(source.contains("install -d"));
    }

    @Test
    void fixedReviewContractsKeepDefaultBytesAndBindCustomSourceIdentityWithoutPermission() {
        assertEquals(DeploymentAgentHealthReviewContract.text(), DeploymentAgentHealthReviewContract.text(DeploymentRemoteLayout.original()));
        assertEquals(DeploymentAgentObservationReviewContract.text(), DeploymentAgentObservationReviewContract.text(DeploymentRemoteLayout.original()));
        for (String text : List.of(DeploymentAgentHealthReviewContract.text(LAYOUT), DeploymentAgentObservationReviewContract.text(LAYOUT))) {
            assertTrue(text.contains(LAYOUT.helperPath()));
            assertTrue(text.contains(DeploymentManagedHelperBundle.helperSha256(LAYOUT)));
            assertTrue(text.contains(LAYOUT.paths().applicationsRoot()));
            assertFalse(text.contains("/usr/local/lib/windowstolinux"));
            assertFalse(text.contains("@wtl:"));
        }
        assertFalse(DeploymentAgentHealthReviewContract.text(LAYOUT).equals(DeploymentAgentHealthReviewContract.text()));
    }
}
