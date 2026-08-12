package gold.debug.windowstolinux.shared.deploy.plan;

import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewedDeploymentPlannerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void plansNodeWithImmutableConfigAndRollback() {
        ReviewedDeploymentRequest request = request(DeploymentProjectType.NODE_SERVICE, DeploymentBuildTool.NPM,
                new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(8080, 10, 2)), false);

        ReviewedDeploymentPlan plan = new ReviewedDeploymentPlanner().plan(request);

        assertEquals(DeploymentProjectType.NODE_SERVICE, plan.request().facts().projectType());
        assertTrue(plan.steps().contains(DeploymentStep.VERIFY_CONFIGURATION_SNAPSHOT));
        assertTrue(plan.steps().contains(DeploymentStep.ROLLBACK_ON_FAILURE));
    }

    @Test
    void requiresExplicitDockerDaemonRiskApproval() {
        DeploymentRuntimeSpecification.Container runtime = new DeploymentRuntimeSpecification.Container(
                DeploymentRuntimeSpecification.ContainerEngine.DOCKER, Map.of(8080, 8080),
                List.of(new DeploymentRuntimeSpecification.ManagedVolume("windowstolinux-demo-data", "/data", false)),
                new HealthCheck.Tcp(8080, 10, 2));

        assertThrows(IllegalArgumentException.class, () -> request(DeploymentProjectType.DOCKERFILE_CONTAINER,
                DeploymentBuildTool.CONTAINER_BUILD, runtime, false));

        ReviewedDeploymentPlan plan = new ReviewedDeploymentPlanner().plan(request(DeploymentProjectType.DOCKERFILE_CONTAINER,
                DeploymentBuildTool.CONTAINER_BUILD, runtime, true));
        assertTrue(plan.steps().contains(DeploymentStep.VERIFY_CONTAINER_POLICY));
    }

    private ReviewedDeploymentRequest request(DeploymentProjectType type, DeploymentBuildTool buildTool,
                                              DeploymentRuntimeSpecification runtime, boolean dockerRiskAccepted) {
        String sha = "0".repeat(64);
        DeploymentProjectFacts facts = new DeploymentProjectFacts(temporaryDirectory, "demo", type, buildTool,
                List.of(new AnalysisEvidence(LocalizedMessage.of("test.evidence"), "fixture", LocalizedMessage.of("test.detected"),
                        EvidenceConfidence.HIGH)), List.of(), List.of());
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create("demo", 1, "v1", Instant.parse("2026-08-12T00:00:00Z"),
                List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(8080))));
        return new ReviewedDeploymentRequest(new ServerIdentity("server-one", "example.test", 22, "SHA256:abcdefghijkl"), facts,
                new SourceRevision(sha, Optional.of("a".repeat(40)), Map.of()),
                new SourceArchiveDescriptor(temporaryDirectory.resolve("source.tar.gz"), sha, 100, 100), configuration,
                List.of(), runtime, BuildLimits.defaultNonRoot(),
                new DeploymentApproval("demo", sha, "server-one", false, Instant.parse("2026-08-12T00:00:00Z")), dockerRiskAccepted);
    }
}
