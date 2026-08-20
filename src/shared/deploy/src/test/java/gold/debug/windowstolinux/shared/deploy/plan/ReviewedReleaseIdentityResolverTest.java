package gold.debug.windowstolinux.shared.deploy.plan;

import gold.debug.windowstolinux.shared.deploy.contract.DeploymentApproval;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;

import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidenceLevel;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.SourceRevision;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReviewedReleaseIdentityResolverTest {
    private static final String SOURCE = "a".repeat(64);
    @TempDir Path temporaryDirectory;

    @Test
    void coversConfigurationSecretRevisionAndRuntimeWithoutDependingOnSecretOrder() {
        ReviewedDeploymentRequest baseline = request(1, List.of(new SecretReference("alpha", 1),
                new SecretReference("beta", 2)), 8080);
        assertEquals(ReviewedReleaseIdentityResolver.from(baseline), ReviewedReleaseIdentityResolver.from(request(1,
                List.of(new SecretReference("beta", 2), new SecretReference("alpha", 1)), 8080)));
        assertNotEquals(ReviewedReleaseIdentityResolver.from(baseline), ReviewedReleaseIdentityResolver.from(request(2,
                baseline.secretReferences(), 8080)));
        assertNotEquals(ReviewedReleaseIdentityResolver.from(baseline), ReviewedReleaseIdentityResolver.from(request(1,
                List.of(new SecretReference("alpha", 2), new SecretReference("beta", 2)), 8080)));
        assertNotEquals(ReviewedReleaseIdentityResolver.from(baseline), ReviewedReleaseIdentityResolver.from(request(1,
                baseline.secretReferences(), 8081)));
    }

    @Test
    void distinguishesSpringBootBuildToolsForTheSameReviewedInputs() {
        ReviewedDeploymentRequest gradle = springBootRequest(DeploymentBuildToolType.GRADLE_WRAPPER);

        assertNotEquals(ReviewedReleaseIdentityResolver.from(gradle),
                ReviewedReleaseIdentityResolver.from(springBootRequest(DeploymentBuildToolType.MAVEN_WRAPPER)));
        assertNotEquals(ReviewedReleaseIdentityResolver.from(gradle),
                ReviewedReleaseIdentityResolver.from(springBootRequest(DeploymentBuildToolType.MAVEN)));
    }

    @Test
    void rejectsAnExperimentalAdapterWithoutFreshTestEnvironmentApproval() {
        assertThrows(IllegalArgumentException.class,
                () -> springBootRequest(DeploymentBuildToolType.MAVEN_WRAPPER, false));
    }

    private ReviewedDeploymentRequest request(long revision, List<SecretReference> secrets, int port) {
        ServerIdentity server = new ServerIdentity("server-one", "example.test", 22, "SHA256:fixture");
        DeploymentProjectFacts facts = new DeploymentProjectFacts(temporaryDirectory, "demo",
                DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.NPM,
                List.of(new AnalysisEvidence(LocalizedMessage.of("test.evidence"), "fixture",
                        LocalizedMessage.of("test.detected"), EvidenceConfidenceLevel.HIGH)), List.of(), List.of());
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create("demo", revision, "v1", Instant.EPOCH,
                List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME,
                        new ConfigurationValue.Number(port))));
        DeploymentRuntimeSpecification runtime = new DeploymentRuntimeSpecification.NodeService(18,
                new HealthCheck.Tcp(port, 5, 1));
        return new ReviewedDeploymentRequest(server, facts, new SourceRevision(SOURCE, Optional.empty(), Map.of()),
                new SourceArchiveDescriptor(temporaryDirectory.resolve("source.tar.gz"), SOURCE, 100, 100),
                configuration, secrets, runtime, Optional.empty(), BuildLimitConfiguration.defaultNonRoot(),
                new DeploymentApproval("demo", SOURCE, "server-one", false, Instant.EPOCH), false);
    }

    private ReviewedDeploymentRequest springBootRequest(DeploymentBuildToolType buildTool) {
        return springBootRequest(buildTool, true);
    }

    private ReviewedDeploymentRequest springBootRequest(DeploymentBuildToolType buildTool, boolean experimentalRiskAccepted) {
        ServerIdentity server = new ServerIdentity("server-one", "example.test", 22, "SHA256:fixture");
        DeploymentProjectFacts facts = new DeploymentProjectFacts(temporaryDirectory, "demo",
                DeploymentProjectType.SPRING_BOOT, buildTool,
                List.of(new AnalysisEvidence(LocalizedMessage.of("test.evidence"), "fixture",
                        LocalizedMessage.of("test.detected"), EvidenceConfidenceLevel.HIGH)), List.of(), List.of());
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create("demo", 1, "v1", Instant.EPOCH,
                List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME,
                        new ConfigurationValue.Number(8080))));
        return new ReviewedDeploymentRequest(server, facts, new SourceRevision(SOURCE, Optional.empty(), Map.of()),
                new SourceArchiveDescriptor(temporaryDirectory.resolve("source.tar.gz"), SOURCE, 100, 100),
                configuration, List.of(), new DeploymentRuntimeSpecification.SpringBoot(new HealthCheck.Tcp(8080, 5, 1)),
                Optional.empty(), BuildLimitConfiguration.defaultNonRoot(),
                new DeploymentApproval("demo", SOURCE, "server-one", false, Instant.EPOCH), false,
                experimentalRiskAccepted);
    }
}
