package gold.debug.windowstolinux.shared.linux.sshd.build.script;

import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildConfigurationEnvironmentRendererTest {
    @Test
    void exportsOnlyBuildScopeWithShellQuoting() {
        ConfigurationSnapshot snapshot = ConfigurationSnapshot.create("demo", 1, "v1", Instant.EPOCH, List.of(
                new ConfigurationEntry("BUILD_LABEL", ConfigurationScope.BUILD, new ConfigurationValue.Text("value'; touch /tmp/pwned; '")),
                new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(8080))));

        String rendered = BuildConfigurationEnvironmentRenderer.render(snapshot);

        assertTrue(rendered.contains("export BUILD_LABEL='value'\"'\"'; touch /tmp/pwned; '\"'\"''"));
        assertFalse(rendered.contains("export PORT="));
    }
}
