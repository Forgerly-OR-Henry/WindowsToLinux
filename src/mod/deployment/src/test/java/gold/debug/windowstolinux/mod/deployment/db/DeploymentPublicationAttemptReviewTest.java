package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.*;
import org.junit.jupiter.api.Test;

class DeploymentPublicationAttemptReviewTest extends DeploymentGraphFixture {
    @Test
    void changedCatalogEvidenceWithoutChangedInputsOrExecutablesCannotAuthorizeAnotherAttempt() throws Exception {
        var component = component("service", "server", List.of());
        var configuration = DeploymentConfigurationSnapshot.create("service", 1, "1", OBSERVED, List.of());
        var graph = new DeploymentApplicationGraph("service", "service", component.reviewedRuntime().healthCheck(),
            List.of(component));
        var proposal = new DeploymentPublicationProposal(graph,
            List.of(new DeploymentUnitCommit(component.unit(), component.runtime(),
                new DeploymentCurrentRelease("service", "a".repeat(64), OBSERVED), configuration, List.of())));
        var snapshot = UUID.randomUUID();
        var task = UUID.randomUUID();
        var source = Map.of("snapshot", snapshot.toString(), "manifest", "b".repeat(64), "archive", "a".repeat(64),
            "relativeRoot", ".", "bytes", "1", "uncompressedBytes", "1");
        var facts = Map.of("projectType", "NODE_SERVICE", "buildTool", "NPM", "buildDirectory",
            component.reviewedRuntime().workload().buildDirectory(), "language", "JavaScript", "support", "DIRECT",
            "architecture", "x86_64", "framework", "node");
        var hashFacts = new LinkedHashMap<>(facts);
        hashFacts.put("applicationId", "service");
        var limits = DeploymentBuildLimits.defaultNonRoot();
        var revision = DeploymentReviewedPlanDigest.from(new DeploymentReviewedPlanDigest.Values("server", snapshot,
            source.get("manifest"), source.get("archive"), 1, 1, hashFacts, List.of(), configuration, List.of(),
            component.resources(), component.reviewedRuntime(), Optional.empty(), limits, OBSERVED, false, false));
        var description = new DeploymentAssistedBuildReviewCodec.Description("service", "server", source, facts,
            List.of(), Optional.empty(), configuration, List.of(), component.resources(),
            component.reviewedRuntime(), limits, List.of("build"),
            Map.of("primary", "", "secondary", "", "version", "22", "jvmTarget", ""), OBSERVED, false, false,
            revision);
        String plan = DeploymentAssistedBuildReviewCodec.encodeDescription(description);
        String tools = DeploymentToolchainBindingCodec
            .encode(new DeploymentResolvedToolchains("observed-before", List.of()));
        String incidentalTools = DeploymentToolchainBindingCodec
            .encode(new DeploymentResolvedToolchains("observed-after", List.of()));
        var checkpoint = DeploymentJsonDocument.object().put("version", 9).put("phase", "SESSION_CLOSED")
            .put("application", "service").put("task", task.toString()).put("archiveSha256", source.get("archive"))
            .put("planSha256", revision).put("previousRunning", false).put("previousDigest", "")
            .put("previousKind", "none").put("previousToken", "")
            .put("candidate",
                new DeploymentRemoteWorkspace("service", source.get("archive"), Optional.empty()).candidateId())
            .put("buildTools", DeploymentPublicationAttemptProof.digest(tools)).put("buildScript", "c".repeat(64))
            .put("verifiedOutcome", "FAILED").put("verifiedCode", ProblemCode.INCOMPATIBLE.name())
            .put("verifiedEvent", "DEPLOYMENT_HEALTH_FAILED");
        var proof = new DeploymentPublicationAttemptProof(task, task, revision,
            DeploymentPublicationAttemptProof.digest(DeploymentPublicationTemplateCodec.encodeTemplate(proposal)),
            ProblemCode.INCOMPATIBLE, "DEPLOYMENT_HEALTH_FAILED", 0,
            List.of(new DeploymentPublicationAttemptProof.Child("service", checkpoint, tools, Optional.empty(),
                "none", false, 1800, 1700)),
            Optional.empty());
        String originalProof = proof.encode(),
            originalProposal = DeploymentPublicationTemplateCodec.encodeTemplate(proposal);
        var review = new DeploymentPublicationAttemptReview(1, revision, revision, task, UUID.randomUUID(), "",
            "service", plan, plan, "c".repeat(64), "c".repeat(64), DeploymentPublicationAttemptProof.digest(tools),
            incidentalTools, DeploymentPublicationAttemptProof.digest(originalProof), "d".repeat(64),
            "e".repeat(64), "f".repeat(64), OBSERVED);
        assertNotEquals(DeploymentPublicationAttemptProof.digest(tools),
            DeploymentPublicationAttemptProof.digest(incidentalTools));
        assertEquals(DeploymentToolchainBindingCodec.decode(tools).selections(),
            DeploymentToolchainBindingCodec.decode(incidentalTools).selections());
        var rejected = assertThrows(SQLException.class, () -> review.requirePrevious(proof, proposal));
        assertEquals("Incidental evidence or free space change cannot authorize publication replay",
            rejected.getMessage());
        assertEquals(originalProof, proof.encode());
        assertEquals(originalProposal, DeploymentPublicationTemplateCodec.encodeTemplate(proposal));
    }
}
