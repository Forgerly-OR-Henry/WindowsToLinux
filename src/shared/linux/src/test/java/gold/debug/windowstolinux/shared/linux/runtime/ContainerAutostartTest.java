package gold.debug.windowstolinux.shared.linux.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContainerAutostartTest {
    @Test
    void keepsDockerRestartAndPodmanQuadletAutostartAsSeparateTypedOperations() {
        assertEquals(ContainerAutostart.Policy.UNLESS_STOPPED,
                new ContainerAutostart.DockerRestartPolicy(ContainerAutostart.Policy.UNLESS_STOPPED).policy());
        assertEquals("multi-user.target", new ContainerAutostart.PodmanQuadletInstall("multi-user.target").wantedBy());
        assertThrows(IllegalArgumentException.class, () -> new ContainerAutostart.PodmanQuadletInstall("bad target; rm"));
    }
}
