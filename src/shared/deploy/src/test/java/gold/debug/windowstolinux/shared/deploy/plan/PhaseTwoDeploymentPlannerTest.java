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
import gold.debug.windowstolinux.shared.model.project.PhaseTwoBuildTool;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectFacts;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectType;
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

class PhaseTwoDeploymentPlannerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void plansNodeWithImmutableConfigAndRollback() {
        PhaseTwoDeploymentRequest request = request(PhaseTwoProjectType.NODE_SERVICE, PhaseTwoBuildTool.NPM,
                new PhaseTwoRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(8080, 10, 2)), false);

        PhaseTwoDeploymentPlan plan = new PhaseTwoDeploymentPlanner().plan(request);

        assertEquals(PhaseTwoProjectType.NODE_SERVICE, plan.request().facts().projectType());
        assertTrue(plan.steps().contains(PhaseTwoDeploymentStep.VERIFY_CONFIGURATION_SNAPSHOT));
        assertTrue(plan.steps().contains(PhaseTwoDeploymentStep.ROLLBACK_ON_FAILURE));
    }

    @Test
    void requiresExplicitDockerDaemonRiskApproval() {
        PhaseTwoRuntimeSpecification.Container runtime = new PhaseTwoRuntimeSpecification.Container(
                PhaseTwoRuntimeSpecification.ContainerEngine.DOCKER, Map.of(8080, 8080),
                List.of(new PhaseTwoRuntimeSpecification.ManagedVolume("windowstolinux-demo-data", "/data", false)),
                new HealthCheck.Tcp(8080, 10, 2));

        assertThrows(IllegalArgumentException.class, () -> request(PhaseTwoProjectType.DOCKERFILE_CONTAINER,
                PhaseTwoBuildTool.CONTAINER_BUILD, runtime, false));

        PhaseTwoDeploymentPlan plan = new PhaseTwoDeploymentPlanner().plan(request(PhaseTwoProjectType.DOCKERFILE_CONTAINER,
                PhaseTwoBuildTool.CONTAINER_BUILD, runtime, true));
        assertTrue(plan.steps().contains(PhaseTwoDeploymentStep.VERIFY_CONTAINER_POLICY));
    }

    private PhaseTwoDeploymentRequest request(PhaseTwoProjectType type, PhaseTwoBuildTool buildTool,
                                              PhaseTwoRuntimeSpecification runtime, boolean dockerRiskAccepted) {
        String sha = "0".repeat(64);
        PhaseTwoProjectFacts facts = new PhaseTwoProjectFacts(temporaryDirectory, "demo", type, buildTool,
                List.of(new AnalysisEvidence(LocalizedMessage.of("test.evidence"), "fixture", LocalizedMessage.of("test.detected"),
                        EvidenceConfidence.HIGH)), List.of(), List.of());
        ConfigurationSnapshot configuration = ConfigurationSnapshot.create("demo", 1, "v1", Instant.parse("2026-08-12T00:00:00Z"),
                List.of(new ConfigurationEntry("PORT", ConfigurationScope.RUNTIME, new ConfigurationValue.Number(8080))));
        return new PhaseTwoDeploymentRequest(new ServerIdentity("server-one", "example.test", 22, "SHA256:abcdefghijkl"), facts,
                new SourceRevision(sha, Optional.of("a".repeat(40)), Map.of()),
                new SourceArchiveDescriptor(temporaryDirectory.resolve("source.tar.gz"), sha, 100, 100), configuration,
                List.of(), runtime, BuildLimits.defaultNonRoot(),
                new DeploymentApproval("demo", sha, "server-one", false, Instant.parse("2026-08-12T00:00:00Z")), dockerRiskAccepted);
    }
}
