package gold.debug.windowstolinux.shared.standard.deploy.build.generation.script;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

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
