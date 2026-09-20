package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.shared.model.deployment.DatabaseReviewMode;

import gold.debug.windowstolinux.app.service.contract.definition.*;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.git.GitReference;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentFormUseCaseTest {
    @Test void preservesExplicitHealthChoicesWithoutInventingEndpoints() {
        var source = new DeploymentSourceInput(Optional.of(Path.of("demo")), "", 0, "");
        var automatic = DeploymentFormUseCase.request(source, server(), input("AUTOMATIC", ""));
        assertFalse(automatic.overrides().containsKey("healthMode"));
        var tcp = DeploymentFormUseCase.request(source, server(), input("TCP", ""));
        assertEquals("TCP", tcp.overrides().get("healthMode"));
        assertFalse(tcp.overrides().containsKey("port"));
        var inferred = DeploymentFormUseCase.request(source, server(), input("AUTOMATIC", "http://127.0.0.1:18080/health"));
        assertEquals("HTTP", inferred.overrides().get("healthMode"));
        assertEquals("18080", inferred.overrides().get("port"));
        assertFalse(inferred.overrides().containsKey("databaseMode"), "unreviewed remains an input requirement");
        assertThrows(IllegalArgumentException.class,
                () -> DeploymentFormUseCase.request(source, server(), input("HTTP", "file:///tmp/health")));
    }

    @Test void resolvesGitReferencesAndRetainsTheAllowedHost() {
        var request = DeploymentFormUseCase.request(new DeploymentSourceInput(Optional.empty(),
                "https://example.test/team/demo.git", 1, "release-one"), server(), input("TCP", "18080"));
        assertTrue(request.directory().isEmpty());
        assertInstanceOf(GitReference.Tag.class, request.git().orElseThrow().reference());
        assertThrows(IllegalArgumentException.class, () -> DeploymentFormUseCase.request(
                new DeploymentSourceInput(Optional.empty(), "https://example.test/team/demo.git", 9, "main"),
                server(), input("TCP", "18080")));
    }

    private static DeploymentFormInput input(String healthMode, String endpoint) {
        return new DeploymentFormInput(true, DeploymentProjectType.NODE_SERVICE, "", "", "22", "", "PORT=18080", "",
                DatabaseReviewMode.UNREVIEWED, "", healthMode, endpoint, "200", "10", "1", "", "", "", "", "", null, false);
    }

    private static ServerProfile server() {
        return new ServerProfile("server", "example.test", 22, "root", "server/password", CredentialStorageMode.MASTER_PASSWORD);
    }
}
