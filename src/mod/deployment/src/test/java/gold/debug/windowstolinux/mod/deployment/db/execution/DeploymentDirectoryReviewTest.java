package gold.debug.windowstolinux.mod.deployment.db.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentGraphFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import org.junit.jupiter.api.Test;

class DeploymentDirectoryReviewTest extends DeploymentGraphFixture {
    @Test
    void completeArchiveKeepsCustomLayoutWithoutSourceDatabaseAccess() throws Exception {
        var custom = layout("A");
        var original = component("service", "server", List.of());
        var unit = gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity.forManaged(
            original.unit().id(), original.unit().server(), original.unit().ownershipManifestSha256(), custom);
        var part = new gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentApplicationGraph.Part(
            original.componentId(), unit, original.runtime(), original.dependencies(), original.reviewedRuntime(),
            original.dataPaths(), original.resources());
        var graph = new gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentApplicationGraph(
            "service", "service", part.reviewedRuntime().healthCheck(), List.of(part));
        var commits = publications(graph, 1, List.of());
        var snapshot = gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseSnapshot.capture(graph, commits);
        String runtime = gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseCodec.runtime(snapshot);
        String health = gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseCodec.health(snapshot);
        String digest = gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseCodec.digest(runtime, health);
        var archive = gold.debug.windowstolinux.mod.deployment.service.core.handoff.DeploymentArchivedRuntimeCodec.encode(
            new gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal(graph, commits), List.of(),
            new gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseStore(nullDatabase()));
        assertEquals(3, archive.formatVersion());
        var decoded = gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseCodec.snapshot(
            gold.debug.windowstolinux.mod.deployment.service.core.handoff.DeploymentArchivedRuntimeCodec.decode("service", digest, archive));
        assertEquals(snapshot, decoded);
        assertEquals(custom, decoded.graph().components().getFirst().unit().layout());
    }

    @Test
    void defaultReviewKeepsVersionOneAndNondefaultReviewBindsAllFiveRoots() throws Exception {
        var original = description(DeploymentRemoteLayout.original());
        String old = DeploymentAssistedBuildReviewCodec.encodeDescription(original);
        assertTrue(old.startsWith("{\"formatVersion\":1,"));
        assertFalse(old.contains("remoteLayout"));
        assertEquals(original, DeploymentAssistedBuildReviewCodec.decodeDescription(old));
        var custom = description(layout("A"));
        String encoded = DeploymentAssistedBuildReviewCodec.encodeDescription(custom);
        assertTrue(encoded.startsWith("{\"formatVersion\":2,"));
        assertEquals(custom, DeploymentAssistedBuildReviewCodec.decodeDescription(encoded));
        assertNotEquals(original.reviewedRevision(), custom.reviewedRevision());
        var node = new ObjectMapper().readTree(encoded);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("remoteLayout").path("remote"))
            .put("dataRoot", "/srv/wtl.data-B");
        assertThrows(SQLException.class, () -> DeploymentAssistedBuildReviewCodec.decodeDescription(node.toString()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("remoteLayout");
        assertThrows(SQLException.class, () -> DeploymentAssistedBuildReviewCodec.decodeDescription(node.toString()));
    }

    private DeploymentAssistedBuildReviewCodec.Description description(DeploymentRemoteLayout layout) {
        var component = component("service", "server", List.of());
        var configuration = DeploymentConfigSnapshot.create("service", 1, "1", OBSERVED, List.of());
        var source = Map.of("snapshot", "12345678-1234-1234-1234-123456789012", "manifest", "b".repeat(64),
            "archive", "a".repeat(64), "relativeRoot", ".", "bytes", "1", "uncompressedBytes", "1");
        var facts = Map.of("projectType", "NODE_SERVICE", "buildTool", "NPM", "buildDirectory",
            component.reviewedRuntime().workload().buildDirectory(), "language", "JavaScript", "support", "DIRECT",
            "architecture", "x86_64", "framework", "node");
        var hashFacts = new LinkedHashMap<>(facts);
        hashFacts.put("applicationId", "service");
        var limits = new DeploymentConfig.Build(1800, 512, 2048, 8_388_608, 8_589_934_592L).limits();
        var values = new DeploymentReviewedPlanDigest.Values("server", UUID.fromString(source.get("snapshot")),
            source.get("manifest"), source.get("archive"), 1, 1, hashFacts, List.of(), configuration, List.of(),
            component.resources(), component.reviewedRuntime(), Optional.empty(), limits, OBSERVED, false, false);
        assertEquals(DeploymentReviewedPlanDigest.from(values),
            DeploymentReviewedPlanDigest.from(values, DeploymentRemoteLayout.original()));
        return new DeploymentAssistedBuildReviewCodec.Description("service", "server", source, facts,
            List.of(), Optional.empty(), configuration, List.of(), component.resources(), component.reviewedRuntime(),
            limits, List.of("build"), Map.of("primary", "", "secondary", "", "version", "22", "jvmTarget", ""),
            OBSERVED, false, false, DeploymentReviewedPlanDigest.from(values, layout), layout);
    }

    private static DeploymentRemoteLayout layout(String suffix) {
        return new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths("/srv/wtl.helper-" + suffix,
            "/srv/wtl.apps-" + suffix, "/srv/wtl.state-" + suffix, "/srv/wtl.config-" + suffix, "/srv/wtl.data-" + suffix));
    }

    private gold.debug.windowstolinux.appapi.db.ModDatabase nullDatabase() {
        return new gold.debug.windowstolinux.appapi.db.ModDatabase() {
            public gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity identity() {
                throw new AssertionError("Archive validation must not read the source database");
            }

            public java.sql.Connection openConnection(gold.debug.windowstolinux.appapi.task.run.TaskContext task) {
                throw new AssertionError("Archive validation must not read the source database");
            }
        };
    }
}
