package gold.debug.windowstolinux.mod.deployment.db.release;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentGraphFixture;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseRecord.DeploymentReleaseState;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentReleaseCodecTest extends DeploymentGraphFixture {
    DeploymentReleaseRecord record(String runtime, String health) {
        return new DeploymentReleaseRecord("application",
            new DeploymentReleaseIdentity(UUID.randomUUID(), DeploymentReleaseCodec.digest(runtime, health)),
            UUID.randomUUID(), DeploymentReleaseState.ACTIVE, runtime, health, OBSERVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"binary", "padding", "health", "cycle", "missing", "revision", "unknown-field", "identity",
        "normalization"})
    void matchingDigestDoesNotHideMalformedOrNoncanonicalManifest(String fault) throws Exception {
        var graph = graph("application");
        var snapshot = DeploymentReleaseSnapshot.capture(graph, publications(graph, 1, List.of()));
        var runtime = DeploymentJsonDocument.parse(DeploymentReleaseCodec.runtime(snapshot),
            DeploymentReleaseCodec.LIMIT);
        var health = DeploymentJsonDocument.parse(DeploymentReleaseCodec.health(snapshot));
        var component = (ObjectNode) runtime.path("components").get(0);
        switch (fault) {
            case "binary" -> component.put("runtime", "%%%%");
            case "padding" -> component.put("resources", component.path("resources").textValue() + "=");
            case "health" -> health.put("health", Base64.getEncoder().encodeToString(new byte[65_537]));
            case "cycle" -> component.putArray("dependencies").add("web");
            case "missing" -> component.remove("configurationSha256");
            case "revision" -> component.put("configurationRevision", 1.0);
            case "unknown-field" -> component.put("unexpected", true);
            case "identity" -> runtime.put("applicationId", "other");
            case "normalization" -> component.put("unitId", " web ");
            default -> throw new AssertionError(fault);
        }
        var record = record(DeploymentJsonDocument.encode(runtime, DeploymentReleaseCodec.LIMIT),
            DeploymentJsonDocument.encode(health));
        assertThrows(SQLException.class, () -> DeploymentReleaseCodec.snapshot(record));
    }

    @Test
    void aggregateManifestAboveOneMiBRetainsEveryImmutableReferenceWithoutChangingTheExecutionBound() throws Exception {
        var graph = graph("application");
        var references = new ArrayList<DeploymentSecretRef>();
        for (int i = 0; i < 18000; i++)
            references.add(new DeploymentSecretRef("secret-" + i, 1));
        var snapshot = DeploymentReleaseSnapshot.capture(graph, publications(graph, 1, references));
        String runtime = DeploymentReleaseCodec.runtime(snapshot);
        String health = DeploymentReleaseCodec.health(snapshot);
        assertTrue(runtime.length() > 1_048_576);
        assertTrue(runtime.length() < DeploymentReleaseCodec.LIMIT);
        assertEquals(snapshot, DeploymentReleaseCodec.snapshot(record(runtime, health)));
        assertThrows(SQLException.class, () -> DeploymentJsonDocument.parse(runtime));
    }
}
