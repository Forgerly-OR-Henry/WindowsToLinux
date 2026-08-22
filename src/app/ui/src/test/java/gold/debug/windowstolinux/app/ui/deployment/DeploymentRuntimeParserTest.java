package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.shared.git.GitReference;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
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
        var php = (DeploymentRuntimeSpecification.PhpService) DeploymentRuntimeParser.service(
                DeploymentProjectType.PHP_SERVICE, "8.3", "public", "public/index.php",
                new HealthCheck.Tcp(8080, 10, 5));
        assertEquals(8080, php.servicePort());
        assertThrows(IllegalArgumentException.class, () -> DeploymentRuntimeParser.ports("18080:8080;18080:8081"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentRuntimeParser.secrets("missing-revision"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentRuntimeParser.gitReference(3, "main"));
    }

    @Test
    void distinguishesExplicitNoDatabaseFromOneReviewedServerDatabase() {
        assertEquals(java.util.List.of(), DeploymentRuntimeParser.databaseBindings(
                DeploymentRuntimeParser.DatabaseReviewMode.NONE, "ignored retained expert input").orElseThrow());

        var bindings = DeploymentRuntimeParser.databaseBindings(
                DeploymentRuntimeParser.DatabaseReviewMode.POSTGRESQL,
                "primary|db.example.test|5432|shop|shop|database-password:3|required").orElseThrow();
        assertEquals("primary", bindings.getFirst().databaseId());
        var connection = (ManagedDatabaseConnection.Server) bindings.getFirst().connection();
        assertEquals("db.example.test", connection.host());
        assertEquals(5432, connection.port());
        assertEquals("database-password", connection.passwordReference().identifier());
        assertEquals(3, connection.passwordReference().revision());
        assertEquals(true, connection.tlsRequired());
    }

    @Test
    void rejectsUnreviewedOrMalformedDatabaseScopes() {
        assertThrows(IllegalArgumentException.class, () -> DeploymentRuntimeParser.databaseBindings(
                DeploymentRuntimeParser.DatabaseReviewMode.UNREVIEWED, ""));
        assertThrows(IllegalArgumentException.class, () -> DeploymentRuntimeParser.databaseBindings(
                DeploymentRuntimeParser.DatabaseReviewMode.MYSQL, "missing-fields"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentRuntimeParser.databaseBindings(
                DeploymentRuntimeParser.DatabaseReviewMode.MARIADB,
                "primary|db.example.test|3306|shop|shop|database-password:1|unknown"));
    }
}
