package gold.debug.windowstolinux.shared.linux.sshd.build.generation.script;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildConfigurationEnvironmentRendererTest {
    @Test
    void exportsOnlyBuildScopeWithShellQuoting() {
        var snapshot = new gold.debug.windowstolinux.shared.linux.build.RemoteBuildEnvironment("demo",
                java.util.Map.of("BUILD_LABEL", "value'; touch /tmp/pwned; '"));

        String rendered = BuildConfigurationEnvironmentRenderer.render(snapshot);

        assertTrue(rendered.contains("export BUILD_LABEL='value'\"'\"'; touch /tmp/pwned; '\"'\"''"));
        assertFalse(rendered.contains("export PORT="));
    }
}
