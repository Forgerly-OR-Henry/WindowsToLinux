package gold.debug.windowstolinux.shared.config.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser;
import org.junit.jupiter.api.Test;

class DeploymentConfigurationParserTest {
    @Test
    void supportsExplicitBuildAndRuntimeScopesWhilePreservingTheRuntimeDefault() {
        var entries = DeploymentConfigurationParser.parse("build:BUILD_LABEL=release;runtime:PORT=8080;FEATURE=true");

        assertEquals(ConfigurationScope.BUILD, entries.get(0).scope());
        assertEquals(ConfigurationScope.RUNTIME, entries.get(1).scope());
        assertEquals(ConfigurationScope.RUNTIME, entries.get(2).scope());
        assertThrows(IllegalArgumentException.class, () -> DeploymentConfigurationParser.parse("secret:VALUE=blocked"));
    }

    @Test
    void parsesSecretReferencesWithoutSilentlyDroppingDuplicates() {
        assertEquals(2, DeploymentConfigurationParser.secrets("database-password:1;api-token:2").size());
        assertEquals(2, DeploymentConfigurationParser.secrets("database-password:1;database-password:1").size());
        assertThrows(IllegalArgumentException.class, () -> DeploymentConfigurationParser.secrets("missing-revision"));
    }
}
