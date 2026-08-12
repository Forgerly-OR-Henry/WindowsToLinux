package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.shared.git.reference.GitReference;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentRuntimeParserTest {
    @Test
    void parsesBoundedRuntimeAndSourceReferencesWithoutDroppingDuplicates() {
        assertEquals(2, DeploymentRuntimeParser.secrets("database-password:1;api-token:2").size());
        assertEquals(java.util.Map.of(18080, 8080), DeploymentRuntimeParser.ports("18080:8080"));
        assertEquals(1, DeploymentRuntimeParser.volumes("windowstolinux-data:/var/lib/demo:rw").size());
        assertEquals(java.util.List.of("--flag", "value"), DeploymentRuntimeParser.arguments("--flag value"));
        assertEquals("main", ((GitReference.Branch) DeploymentRuntimeParser.gitReference(0, "main")).value());
        var php = (DeploymentRuntimeSpecification.AdvancedService) DeploymentRuntimeParser.advanced(
                DeploymentProjectType.PHP_SERVICE, "8.3", "public", "public/index.php",
                new HealthCheck.Tcp(8080, 10, 5));
        assertEquals(8080, php.servicePort().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> DeploymentRuntimeParser.ports("18080:8080;18080:8081"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentRuntimeParser.secrets("missing-revision"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentRuntimeParser.gitReference(3, "main"));
    }
}
