package gold.debug.windowstolinux.mod.deployment.service.environment;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import org.junit.jupiter.api.Test;

class DeploymentEnvironmentScriptsTest {
    @Test
    void originalProfilesContainFixedJava21OpenSshAndPythonPackages() {
        for (var profile : List.of("ubuntu-22.04", "ubuntu-24.04", "debian-13", "centos-9", "centos-10",
            "almalinux-9.8", "almalinux-10.2", "rocky-9.8", "rocky-10.2", "ol-9.7", "ol-10.2")) {
            var packages = DeploymentEnvironmentProfiles.packages(profile);
            assertTrue(packages.contains("maven"));
            assertTrue(packages.contains("nodejs"));
            assertTrue(packages.stream().anyMatch(value -> value.contains("21") && value.contains("jdk")));
            if (profile.matches("(centos|almalinux|rocky|ol)-9.*")) {
                assertTrue(packages.containsAll(List.of("openssh", "openssh-server", "openssh-clients", "python3.11", "python3.11-pip")));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> DeploymentEnvironmentProfiles.packages("centos-8"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentEnvironmentProfiles.packages("ubuntu-24.04; reboot"));
    }

    @Test
    void fixedScriptPreservesTransactionRepositoryAndSystemSecurityGuards() {
        var target = new RemoteTarget(new ServerId("test"), UUID.randomUUID(), "127.0.0.1", 22, "root", Optional.of("SHA256:" + "B".repeat(43)));
        var state = new DeploymentEnvironmentState(1, target, UUID.randomUUID(), DeploymentEnvironmentScripts.bodyDigest(), "INTENT", "none", Optional.empty());
        try (var request = DeploymentEnvironmentScripts.request(state, "probe")) {
            String script = new String(request.stdin(), StandardCharsets.UTF_8);
            assertTrue(script.contains("dnf --enablerepo=crb -y install"));
            assertFalse(script.contains("dnf update"));
            assertFalse(script.contains("dnf upgrade"));
            assertFalse(script.contains("config-manager"));
            assertFalse(script.contains("apt-get upgrade"));
            assertTrue(script.contains("/usr/sbin/sshd -t"));
            assertTrue(script.contains("selinux-autorelabel.service"));
            assertTrue(script.contains("ausearch --input-logs -m AVC,USER_AVC -ts boot"));
            assertTrue(script.contains("original-prepare-already-sent"));
            assertTrue(script.contains("original-enforce-already-sent"));
            assertTrue(script.contains("original-commit-already-sent"));
            assertTrue(script.contains("foreign-preparation-state"));
            assertTrue(script.contains("--on-active=180s"));
            assertTrue(script.contains("$task-$operation"));
        }
    }
}
