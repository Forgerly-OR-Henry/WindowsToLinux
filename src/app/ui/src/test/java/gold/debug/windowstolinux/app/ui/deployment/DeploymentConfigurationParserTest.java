package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentConfigurationParserTest {
    @Test
    void supportsExplicitBuildAndRuntimeScopesWhilePreservingTheRuntimeDefault() {
        var entries = DeploymentConfigurationParser.parse("build:BUILD_LABEL=release;runtime:PORT=8080;FEATURE=true");

        assertEquals(ConfigurationScope.BUILD, entries.get(0).scope());
        assertEquals(ConfigurationScope.RUNTIME, entries.get(1).scope());
        assertEquals(ConfigurationScope.RUNTIME, entries.get(2).scope());
        assertThrows(IllegalArgumentException.class, () -> DeploymentConfigurationParser.parse("secret:VALUE=blocked"));
    }
}
