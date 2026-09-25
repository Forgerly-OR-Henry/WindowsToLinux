package gold.debug.windowstolinux.app.service.deployment.single;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.app.service.deployment.single.DeploymentHandoff;
import gold.debug.windowstolinux.app.service.deployment.single.DeploymentOutcome;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentTraceEvent;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.SourceRevision;
import gold.debug.windowstolinux.shared.model.project.application.*;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.standard.deploy.contract.DeploymentApproval;
import gold.debug.windowstolinux.shared.standard.deploy.contract.ReviewedDeploymentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentOutcomeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsTheExplicitBusinessUrlInsteadOfTheTargetLocalHealthEndpoint() {
        HealthCheck.Http health = new HealthCheck.Http(URI.create("http://127.0.0.1:18080/actuator/health?ready=true"),
                200, 10);
        URI businessUrl = URI.create("http://198.51.100.24:18080/");
        ReviewedDeploymentRequest request = request(health, Optional.of(new UserAccessUrl(businessUrl)));
        DeploymentOutcome outcome = DeploymentOutcome.from(result(DeploymentStatus.SUCCEEDED), request, application());

        var handoff = assertInstanceOf(DeploymentHandoff.ApplicationEntry.class, outcome.handoff().orElseThrow());
        assertEquals(List.of(businessUrl.toString()), handoff.usage().endpoints());
        assertEquals("WEBSITE", handoff.usage().category());
        assertTrue(handoff.usage().lifecycle());
    }

    @Test
    void returnsTheBoundSystemdStartCommandForTcpHealthChecks() {
        ReviewedDeploymentRequest request = request(new HealthCheck.Tcp(18081, 10, 1), Optional.empty());
        DeploymentOutcome outcome = DeploymentOutcome.from(result(DeploymentStatus.SUCCEEDED), request, application());

        var handoff = assertInstanceOf(DeploymentHandoff.ApplicationEntry.class, outcome.handoff().orElseThrow());
        assertEquals("APP", handoff.usage().category());
        assertEquals("sudo /usr/local/lib/windowstolinux/managed-helper lifecycle demo start " + "a".repeat(64),
                handoff.usage().command());
    }

    @Test
    void doesNotProvideAnyHandoffForAFailedDeployment() {
        ReviewedDeploymentRequest request = request(
                new HealthCheck.Http(URI.create("http://127.0.0.1:8080/actuator/health"), 200, 10),
                Optional.of(new UserAccessUrl(URI.create("http://198.51.100.24:8080/"))));

        DeploymentOutcome outcome = DeploymentOutcome.from(result(DeploymentStatus.FAILED_BUILD), request,
                application());

        assertEquals(DeploymentStatus.FAILED_BUILD, outcome.status());
        assertTrue(outcome.handoff().isEmpty());
    }

    @Test
    void rejectsLoopbackAsAUserBusinessUrl() {
        assertThrows(IllegalArgumentException.class, () -> new UserAccessUrl(URI.create("http://127.0.0.1:8080/")));
    }

    private ReviewedDeploymentRequest request(HealthCheck healthCheck, Optional<UserAccessUrl> userAccessUrl) {
        ServerIdentity server = application().server();
        String sourceSha256 = "b".repeat(64);
        DeploymentProjectFacts facts = new DeploymentProjectFacts(temporaryDirectory.resolve("source"), "demo",
                DeploymentProjectType.SPRING_BOOT, DeploymentBuildToolType.MAVEN, List.of(), List.of(), List.of());
        SourceArchiveDescriptor archive = new SourceArchiveDescriptor(temporaryDirectory.resolve("demo.tar.gz"),
                sourceSha256, 0, 0);
        return new ReviewedDeploymentRequest(server, facts,
                new SourceRevision(sourceSha256, Optional.empty(), Map.of()), archive,
                ConfigurationSnapshot.create("demo", 1, "v1", Instant.now(),
                        List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME,
                                new ConfigurationValue.Number(8080)))),
                List.of(),
                new DeploymentRuntimeSpecification.SpringBoot(healthCheck)
                        .withWorkload(new ApplicationWorkload(ApplicationWorkload.ExecutionMode.DAEMON, true,
                                ApplicationCommand.primary(), "",
                                userAccessUrl.map(url -> List.of(new ApplicationEndpoint("web",
                                        ApplicationEndpoint.ProtocolType.HTTP, "0.0.0.0",
                                        healthCheck.portNumber().orElseThrow(), healthCheck.portNumber().orElseThrow(),
                                        ApplicationEndpoint.ExposureType.EXTERNAL, url.url().toString())))
                                        .orElse(List.of()),
                                Optional.empty(), "", Optional.empty(), List.of(), List.of())),
                userAccessUrl, BuildLimitConfiguration.defaultNonRoot(),
                new DeploymentApproval("demo", sourceSha256, server.id(), false, Instant.now()), true, true);
    }

    private static ManagedApplication application() {
        ServerIdentity server = new ServerIdentity("server-one", "198.51.100.24", 22, "SHA256:AAAAAAAAAAAA");
        return ManagedApplication.forManaged("demo", server, "a".repeat(64));
    }

    private static DeploymentResult result(DeploymentStatus status) {
        Optional<String> release = status == DeploymentStatus.SUCCEEDED
                ? Optional.of("c".repeat(64))
                : Optional.empty();
        return new DeploymentResult(status, List.of(DeploymentEvent.result(DeploymentTraceEvent.REMOTE_BUILD,
                status == DeploymentStatus.SUCCEEDED, "evidence")), Optional.empty(), release);
    }
}
