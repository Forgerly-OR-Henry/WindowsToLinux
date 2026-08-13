package gold.debug.windowstolinux.shared.linux.sshd.protocol;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerProtocolContractTest {
    @Test
    void rendersDeterministicEnginePortsAndNamedVolumesOnly() {
        DeploymentRuntimeSpecification.Container runtime = new DeploymentRuntimeSpecification.Container(
                DeploymentRuntimeSpecification.ContainerEngine.PODMAN, Map.of(9000, 9001, 8080, 8081),
                List.of(new DeploymentRuntimeSpecification.ManagedVolume("windowstolinux-demo-cache", "/cache", true),
                        new DeploymentRuntimeSpecification.ManagedVolume("windowstolinux-demo-data", "/data", false)),
                new HealthCheck.Tcp(8080, 5, 1));

        assertEquals(List.of("podman", "2", "8080", "8081", "9000", "9001", "2",
                "windowstolinux-demo-cache", "/cache", "1", "windowstolinux-demo-data", "/data", "0"),
                ContainerRuntimeArguments.from(runtime));
    }

    @Test
    void helperHasDedicatedDockerAndQuadletPathsWithoutPrivilegedEscapes() {
        String helper = ManagedHelperBundle.renderScript();

        assertTrue(helper.contains("publish-container) publish_container"));
        assertTrue(helper.contains("snapshot-container) snapshot_container"));
        assertTrue(helper.contains("observe-deployment) observe_deployment"));
        assertTrue(helper.contains("save_container_parameters"));
        assertTrue(helper.contains("load_container_parameters"));
        assertTrue(helper.contains("save_deployment_parameters"));
        assertTrue(helper.contains("load_deployment_parameters"));
        assertTrue(helper.contains("printf 'HELPER=1\\nPROTOCOL=%s\\n'"));
        assertFalse(helper.contains("\\\\n"));
        assertTrue(helper.contains("Volume=%s:%s"));
        assertTrue(helper.contains("update --restart unless-stopped"));
        assertTrue(helper.contains("set_podman_quadlet_autostart"));
        assertTrue(helper.contains("WantedBy=multi-user.target"));
        assertFalse(helper.contains("systemctl enable \"windowstolinux-$app.service\""));
        assertFalse(helper.contains("systemctl disable \"windowstolinux-$app.service\""));
        assertFalse(helper.contains("--privileged"));
        assertFalse(helper.contains("/var/run/docker.sock"));
        assertFalse(helper.contains("--pid=host"));
    }
}
