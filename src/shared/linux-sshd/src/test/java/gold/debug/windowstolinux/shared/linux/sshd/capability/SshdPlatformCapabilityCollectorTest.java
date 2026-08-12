package gold.debug.windowstolinux.shared.linux.sshd.capability;

import gold.debug.windowstolinux.shared.model.server.LinuxDistro;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshdPlatformCapabilityCollectorTest {
    @Test
    void parsesOnlyFixedNonSecretProbeFacts() {
        var capabilities = SshdPlatformCapabilityCollector.fromValues(Map.ofEntries(
                Map.entry("DISTRO_ID", "centos"), Map.entry("DISTRO_VARIANT", "stream"), Map.entry("VERSION", "10"),
                Map.entry("ARCH", "x86_64"), Map.entry("PACKAGE_MANAGER", "dnf"), Map.entry("SYSTEMD", "1"),
                Map.entry("DOCKER_CLIENT", "0"), Map.entry("DOCKER_OPERATIONAL", "0"), Map.entry("PODMAN_CLIENT", "1"),
                Map.entry("PODMAN_OPERATIONAL", "1"), Map.entry("PODMAN_QUADLET", "1"),
                Map.entry("JAVA_MAJORS", "21"), Map.entry("NODE_MAJORS", "22"), Map.entry("NPM", "1"),
                Map.entry("PYTHON3", "1"), Map.entry("PYTHON_VERSIONS", "3.11,3.12"),
                Map.entry("X86_64_V3", "1"), Map.entry("CPU_FLAGS", "sse4_2,popcnt")), "SHA256:host");

        assertEquals(LinuxDistro.CENTOS_STREAM, capabilities.distro());
        assertTrue(capabilities.podmanQuadletAvailable());
        assertTrue(capabilities.podmanOperational());
        assertEquals(java.util.Set.of(21), capabilities.javaMajorVersions());
        assertEquals(java.util.Set.of(22), capabilities.nodeMajorVersions());
        assertEquals(java.util.Set.of("3.11", "3.12"), capabilities.pythonVersions());
        assertTrue(capabilities.python3Available());
        assertTrue(capabilities.cpuFlags().contains("sse4_2"));
        assertTrue(capabilities.x86_64V3Available());
        assertTrue(PlatformCapabilityProbeScript.render().contains("PODMAN_QUADLET"));
    }
}
