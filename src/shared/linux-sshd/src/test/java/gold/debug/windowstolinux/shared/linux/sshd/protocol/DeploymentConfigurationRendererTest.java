package gold.debug.windowstolinux.shared.linux.sshd.protocol;

import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentConfigurationRendererTest {
    @Test
    void rendersOnlyRuntimeScopeWithoutShellEvaluation() {
        ConfigurationSnapshot snapshot = ConfigurationSnapshot.create("demo", 1, "v1", Instant.EPOCH, List.of(
                new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(8080)),
                new ConfigurationEntry("RUNTIME_LABEL", ConfigurationScope.RUNTIME, new ConfigurationValue.Text("$(touch /tmp/pwned)")),
                new ConfigurationEntry("BUILD_LABEL", ConfigurationScope.BUILD, new ConfigurationValue.Text("build"))));

        String systemd = new String(DeploymentConfigurationRenderer.systemd(snapshot), StandardCharsets.UTF_8);
        String container = new String(DeploymentConfigurationRenderer.container(snapshot), StandardCharsets.UTF_8);

        assertTrue(systemd.contains("RUNTIME_LABEL=\"$(touch /tmp/pwned)\""));
        assertTrue(container.contains("RUNTIME_LABEL=$(touch /tmp/pwned)"));
        assertFalse(systemd.contains("BUILD_LABEL"));
        assertFalse(container.contains("BUILD_LABEL"));
    }
}
