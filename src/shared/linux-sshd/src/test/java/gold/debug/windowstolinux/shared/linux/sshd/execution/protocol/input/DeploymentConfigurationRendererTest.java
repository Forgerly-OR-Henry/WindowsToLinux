package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.input;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentConfigurationRendererTest {
    @Test
    void rendersOnlyRuntimeScopeWithoutShellEvaluation() {
        var snapshot = new gold.debug.windowstolinux.shared.linux.protocol.RemoteRuntimeConfiguration("demo", "a".repeat(64),
                java.util.Map.of("PORT", "8080", "RUNTIME_LABEL", "$(touch /tmp/pwned)"));

        String systemd = new String(DeploymentConfigurationRenderer.systemd(snapshot), StandardCharsets.UTF_8);
        String container = new String(DeploymentConfigurationRenderer.container(snapshot), StandardCharsets.UTF_8);

        assertTrue(systemd.contains("RUNTIME_LABEL=\"$(touch /tmp/pwned)\""));
        assertTrue(container.contains("RUNTIME_LABEL=$(touch /tmp/pwned)"));
        assertFalse(systemd.contains("BUILD_LABEL"));
        assertFalse(container.contains("BUILD_LABEL"));
    }
}
