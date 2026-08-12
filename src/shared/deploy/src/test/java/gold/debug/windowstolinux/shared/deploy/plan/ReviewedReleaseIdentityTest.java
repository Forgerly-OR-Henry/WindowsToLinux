package gold.debug.windowstolinux.shared.deploy.plan;

import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
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

class ReviewedReleaseIdentityTest {
    private static final String SOURCE = "a".repeat(64);
    @TempDir Path temporaryDirectory;

    @Test
    void coversConfigurationSecretRevisionAndRuntimeWithoutDependingOnSecretOrder() {
        ReviewedDeploymentRequest baseline = request(1, List.of(new SecretReference("alpha", 1),
                new SecretReference("beta", 2)), 8080);
        assertEquals(ReviewedReleaseIdentity.from(baseline), ReviewedReleaseIdentity.from(request(1,
                List.of(new SecretReference("beta", 2), new SecretReference("alpha", 1)), 8080)));
        assertNotEquals(ReviewedReleaseIdentity.from(baseline), ReviewedReleaseIdentity.from(request(2,
                baseline.secretReferences(), 8080)));
        assertNotEquals(ReviewedReleaseIdentity.from(baseline), ReviewedReleaseIdentity.from(request(1,
                List.of(new SecretReference("alpha", 2), new SecretReference("beta", 2)), 8080)));
        assertNotEquals(ReviewedReleaseIdentity.from(baseline), ReviewedReleaseIdentity.from(request(1,
                baseline.secretReferences(), 8081)));
    }

    private ReviewedDeploymentRequest request(long revision, List<SecretReference> secrets, int port) {
        ServerIdentity server = new ServerIdentity("server-one", "example.test", 22, "SHA256:fixture");
        DeploymentProjectFacts facts = new DeploymentProjectFacts(temporaryDirectory, "demo",
                DeploymentProjectType.NODE_SERVICE, DeploymentBuildTool.NPM,
                List.of(new AnalysisEvidence(LocalizedMessage.of("test.evidence"), "fixture",
                        LocalizedMessage.of("test.detected"), EvidenceConfidence.HIGH)), List.of(), List.of());
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create("demo", revision, "v1", Instant.EPOCH,
                List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME,
                        new ConfigurationValue.Number(port))));
        DeploymentRuntimeSpecification runtime = new DeploymentRuntimeSpecification.NodeService(18,
                new HealthCheck.Tcp(port, 5, 1));
        return new ReviewedDeploymentRequest(server, facts, new SourceRevision(SOURCE, Optional.empty(), Map.of()),
                new SourceArchiveDescriptor(temporaryDirectory.resolve("source.tar.gz"), SOURCE, 100, 100),
                configuration, secrets, runtime, Optional.empty(), BuildLimits.defaultNonRoot(),
                new DeploymentApproval("demo", SOURCE, "server-one", false, Instant.EPOCH), false);
    }
}
