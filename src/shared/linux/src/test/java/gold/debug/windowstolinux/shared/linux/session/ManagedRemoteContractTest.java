package gold.debug.windowstolinux.shared.linux.session;

import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedRemoteContractTest {
    @Test
    void exposesOnlyTheBoundedManagedOperationsAndNoRawCommandParameter() {
        Set<String> methods = java.util.Arrays.stream(LinuxRemoteSession.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "collectCapabilities", "prepareEnvironment", "uploadSource", "cleanupCandidate", "checkHealth",
                "observe", "executeLifecycle", "close"
        ), methods);
        for (Method method : LinuxRemoteSession.class.getMethods()) {
            assertFalse(java.util.Arrays.stream(method.getParameterTypes()).anyMatch(String.class::equals),
                    () -> method + " must not accept an arbitrary shell command, unit name or remote path");
        }
        assertTrue(java.util.Arrays.stream(LinuxGateway.class.getDeclaredMethods())
                .allMatch(method -> method.getName().equals("connect")),
                "gateway must not publish arbitrary SSH, SFTP or systemd entrypoints");

        Set<String> deploymentMethods = java.util.Arrays.stream(DeploymentRemoteSession.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "collectCapabilities", "prepareEnvironment", "uploadSource", "cleanupCandidate", "checkHealth",
                "observe", "executeLifecycle", "close", "collectDeploymentCapabilities", "buildDeployment",
                "stageDeploymentInputs", "snapshotDeployment", "publishDeployment", "rollbackDeployment",
                "checkDeploymentHealth", "observeDeployment", "executeDeploymentLifecycle",
                "retainRecentSuccessfulReleases", "inspect", "export", "restoreCandidate", "discardCandidate",
                "copyArtifact", "stageArtifact", "discardArtifact", "stageRestoreFiles", "discardRestoreFiles"
                , "createBackupArtifact", "copyBackupArtifact", "discardBackupOperation",
                "inspectRestoreActivation", "startRestoreActivation", "verifyRestoreComponents",
                "verifyRestoreApplication", "prepareRestoreCommit", "startRestoreFormal",
                "commitRestoreActivation", "recoverRestoreActivation", "quiesceRestoreRecovery",
                "commitCandidate", "recoverCandidate", "nativeDatabases"
        ), deploymentMethods);
        assertFalse(deploymentMethods.contains("build"));
        assertFalse(deploymentMethods.contains("snapshot"));
        assertFalse(deploymentMethods.contains("publish"));
        assertFalse(deploymentMethods.contains("rollback"));
        assertTrue(java.util.Arrays.stream(DeploymentLinuxGateway.class.getDeclaredMethods())
                        .allMatch(method -> method.getName().equals("connect")),
                "typed gateway must expose only its covariant typed connection");
    }
}
