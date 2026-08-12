package gold.debug.windowstolinux.app.service;

import gold.debug.windowstolinux.app.service.deployment.DeploymentHandoff;
import gold.debug.windowstolinux.app.service.deployment.DeploymentOutcome;
import gold.debug.windowstolinux.app.service.source.SourcePreparation;

import gold.debug.windowstolinux.shared.deploy.plan.DeploymentApproval;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentEvent;
import gold.debug.windowstolinux.shared.deploy.plan.DeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.project.SourceProjectFacts;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentOutcomeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsTheExplicitBusinessUrlInsteadOfTheTargetLocalHealthEndpoint() {
        HealthCheck.Http health = new HealthCheck.Http(
                URI.create("http://127.0.0.1:18080/actuator/health?ready=true"), 200, 10
        );
        URI businessUrl = URI.create("http://198.51.100.24:18080/");
        DeploymentOutcome outcome = DeploymentOutcome.from(
                result(DeploymentStatus.SUCCEEDED), request(health, Optional.of(new UserAccessUrl(businessUrl)))
        );

        DeploymentHandoff.HttpAccessUrl handoff = assertInstanceOf(
                DeploymentHandoff.HttpAccessUrl.class, outcome.handoff().orElseThrow()
        );
        assertEquals(businessUrl, handoff.url());
        assertNotEquals(health.endpoint(), handoff.url(), "健康端点绝不能作为用户业务网址返回");
        assertEquals(DeploymentHandoff.Kind.HTTP_ACCESS_URL, handoff.kind());
    }

    @Test
    void returnsTheBoundSystemdStartCommandForTcpHealthChecks() {
        DeploymentOutcome outcome = DeploymentOutcome.from(
                result(DeploymentStatus.SUCCEEDED), request(new HealthCheck.Tcp(18081, 10, 1), Optional.empty())
        );

        DeploymentHandoff.SystemdStartCommand handoff = assertInstanceOf(
                DeploymentHandoff.SystemdStartCommand.class, outcome.handoff().orElseThrow()
        );
        assertEquals("windowstolinux-demo.service", handoff.systemdUnit());
        assertEquals("sudo /usr/local/lib/windowstolinux/managed-helper lifecycle demo start " + "a".repeat(64),
                handoff.command());
        assertEquals(DeploymentHandoff.Kind.SYSTEMD_START_COMMAND, handoff.kind());
    }

    @Test
    void rejectsLoopbackAsAUserBusinessUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new UserAccessUrl(URI.create("http://127.0.0.1:8080/")));
    }

    @Test
    void doesNotProvideAnyHandoffForAFailedDeployment() {
        DeploymentOutcome outcome = DeploymentOutcome.from(
                result(DeploymentStatus.FAILED_BUILD), request(new HealthCheck.Http(
                        URI.create("http://127.0.0.1:8080/actuator/health"), 200, 10
                ), Optional.of(new UserAccessUrl(URI.create("http://198.51.100.24:8080/"))))
        );

        assertEquals(DeploymentStatus.FAILED_BUILD, outcome.status());
        assertTrue(outcome.handoff().isEmpty());
    }

    @Test
    void rejectsHttpDeploymentWithoutAnExplicitBusinessUrl() {
        assertThrows(IllegalArgumentException.class, () -> request(new HealthCheck.Http(
                URI.create("http://127.0.0.1:8080/actuator/health"), 200, 10
        ), Optional.empty()));
    }

    private DeploymentRequest request(HealthCheck healthCheck, Optional<UserAccessUrl> userAccessUrl) {
        ServerIdentity server = new ServerIdentity("server-one", "198.51.100.24", 22, "SHA256:AAAAAAAAAAAA");
        ManagedApplication application = ManagedApplication.forManaged("demo", server, "a".repeat(64));
        SourceArchiveDescriptor archive = new SourceArchiveDescriptor(
                temporaryDirectory.resolve("demo.tar.gz"), "b".repeat(64), 0, 0
        );
        SourceProjectFacts source = new SourceProjectFacts(
                temporaryDirectory.resolve("source"), "demo", false, true,
                List.of(LocalizedMessage.of("analysis.observation.mavenDetected"))
        );
        return new DeploymentRequest(application, source, archive,
                new ManagedApplicationRuntimeConfiguration(healthCheck, userAccessUrl), BuildLimits.defaultNonRoot(),
                new DeploymentApproval("demo", archive.contentSha256(), "server-one", false, Instant.now()));
    }

    private static DeploymentResult result(DeploymentStatus status) {
        Optional<String> artifact = status == DeploymentStatus.SUCCEEDED ? Optional.of("c".repeat(64)) : Optional.empty();
        return new DeploymentResult(status, List.of(new DeploymentEvent("remote-build",
                status == DeploymentStatus.SUCCEEDED, "evidence")),
                Optional.empty(), artifact);
    }
}
