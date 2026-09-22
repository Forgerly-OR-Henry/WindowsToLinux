package gold.debug.windowstolinux.app.service.execution.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.*;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.shared.model.health.*;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.managed.*;
import gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode;
import gold.debug.windowstolinux.shared.model.project.application.*;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationInventoryUseCaseTest {
    @TempDir
    Path directory;

    @Test
    void combinesFiltersUsesStableDeploymentOrderAndNeverInventsAnExternalDeploymentDate() throws Exception {
        Instant time = Instant.parse("2026-09-16T00:00:00Z");
        var server = new ServerIdentity("server", "example.test", 22, "SHA256:fixture");
        String externalId = "external:" + UUID.randomUUID();
        try (var db = DesktopPersistence.open(directory)) {
            db.servers().saveServerProfile(new StoredServerProfile(server.id(), server.host(), 22, "tester",
                    "ssh/server/password", "MASTER_PASSWORD", "Production"));
            var workload = new ApplicationWorkload(ApplicationWorkload.ExecutionMode.DAEMON, true,
                    ApplicationCommand.primary(), "",
                    List.of(new ApplicationEndpoint("web", ApplicationEndpoint.ProtocolType.HTTPS, "0.0.0.0", 8080,
                            8080, ApplicationEndpoint.ExposureType.EXTERNAL, "https://example.test")),
                    Optional.empty(), "", Optional.empty(), List.of(), List.of());
            var runtime = new ManagedApplicationRuntimeConfiguration(
                    new HealthCheck.Http(URI.create("http://127.0.0.1:8080"), 200, 10),
                    Optional.of(new UserAccessUrl(URI.create("https://example.test"))),
                    RuntimeIdentityMode.SYSTEMD_STATIC, workload);
            for (String id : List.of("b", "a"))
                db.managedApplications().recordSuccessfulDeployment(
                        ManagedApplication.forManaged(id, server, "a".repeat(64)), runtime,
                        new CurrentRelease(id, "b".repeat(64), time));
            db.managedApplications().save(ManagedApplication.forManaged("unknown", server, "a".repeat(64)));
            db.externalApplications().adopt(new StoredExternalApplication(externalId, server.id(), server.host(), 22,
                    "tester",
                    new DiscoveredApplication(new ExternalApplicationTarget(ExternalApplicationKind.SYSTEMD,
                            "external.service", "c".repeat(64)), "External", RuntimeState.STOPPED, true, true, false),
                    time.plusSeconds(1), time.plusSeconds(1)));
            var servers = new ServerUseCaseFacade(db.servers(), new DesktopSecretStoreService(db.encryptedSecrets()),
                    (endpoint, credential, verifier) -> {
                        throw new AssertionError("inventory reads must not connect");
                    });
            var inventory = new ApplicationInventoryUseCase(db.managedApplications(), db.externalApplications(),
                    servers);
            var list = inventory.list();
            assertEquals(List.of(externalId, "managed:a", "managed:b", "managed:unknown"),
                    list.stream().map(ApplicationSummary::key).toList());
            assertTrue(list.getFirst().deployedAt().isEmpty());
            assertEquals(time.plusSeconds(1), list.getFirst().adoptedAt().orElseThrow());
            assertEquals(2, list.stream().filter(value -> value.matches("WEBSITE", "server")).count());
            assertEquals(0, list.stream().filter(value -> value.matches("WEBSITE", "other")).count());
            assertThrows(IllegalArgumentException.class,
                    () -> inventory.savePresentation("managed:a", "Renamed", "APP", "https://example.test/new"));
            inventory.savePresentation("managed:a", "Renamed", "WEBSITE", "https://example.test/new");
            var updated = inventory.list().stream().filter(value -> value.key().equals("managed:a")).findFirst()
                    .orElseThrow();
            assertEquals("Renamed", updated.name());
            assertEquals("WEBSITE", updated.category());
            assertTrue(updated.accessUrl().isPresent());
            assertTrue(db.managedApplications().findRuntime("a").orElseThrow().userAccessUrl().orElseThrow().url()
                    .getPath().isEmpty(), "editing entry must not modify runtime configuration");
        }
    }
}
