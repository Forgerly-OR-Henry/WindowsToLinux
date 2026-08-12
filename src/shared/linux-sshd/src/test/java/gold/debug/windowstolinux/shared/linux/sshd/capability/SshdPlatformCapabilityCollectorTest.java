package gold.debug.windowstolinux.shared.linux.sshd.capability;

import gold.debug.windowstolinux.shared.model.server.LinuxDistro;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshdPlatformCapabilityCollectorTest {
    @Test
    void parsesOnlyFixedNonSecretProbeFacts() {
        var capabilities = SshdPlatformCapabilityCollector.fromValues(Map.of(
                "DISTRO_ID", "centos", "DISTRO_VARIANT", "stream", "VERSION", "10", "ARCH", "x86_64",
                "PACKAGE_MANAGER", "dnf", "SYSTEMD", "1", "DOCKER_CLIENT", "0", "PODMAN_CLIENT", "1",
                "PODMAN_QUADLET", "1", "CPU_FLAGS", "sse4_2,popcnt"), "SHA256:host");

        assertEquals(LinuxDistro.CENTOS_STREAM, capabilities.distro());
        assertTrue(capabilities.podmanQuadletAvailable());
        assertTrue(capabilities.cpuFlags().contains("sse4_2"));
        assertTrue(PlatformCapabilityProbeScript.render().contains("PODMAN_QUADLET"));
    }
}
