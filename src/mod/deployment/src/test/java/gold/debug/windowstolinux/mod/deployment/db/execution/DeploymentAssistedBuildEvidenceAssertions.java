package gold.debug.windowstolinux.mod.deployment.db.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentRuntimeCodec;

/** Reads actual workflow rows and injects reversible persisted evidence conflicts. / 读取实际流程行并注入可回滚的持久依据冲突。 */
public final class DeploymentAssistedBuildEvidenceAssertions {
    private DeploymentAssistedBuildEvidenceAssertions() {
    }

    /** Validates a real final template, then requires its reader to reject complete source, configuration and root tampering. / 验证真实最终模板，再要求读取器拒绝完整来源、配置及根篡改。
     * @param connection actual writable fixture Schema connection / 实际可写夹具Schema连接
     * @param remoteReleases exact digests acknowledged by the controlled helper / 受控helper确认的精确摘要
     * @return unchanged original request bytes for reopen verification / 供重开核对的未改原请求字节
     * @throws Exception if actual evidence or rejection differs / 实际依据或拒绝不符时 */
    public static String verify(Connection connection, Map<String, String> remoteReleases) throws Exception {
        final DeploymentExecutionRecord original;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM deployment_execution")) {
            assertTrue(rows.next());
            original = DeploymentExecutionStore.row(rows);
            assertFalse(rows.next());
        }
        var request = DeploymentJsonDocument.parse(original.requestJson(), DeploymentExecutionCodec.REQUEST_LIMIT);
        var reviews = DeploymentExecutionCodec.buildReviews(original);
        assertFalse(reviews.isEmpty());
        assertTrue(request.path("buildIntent").isNull());
        var proposal = DeploymentExecutionCodec.publicationTemplate(original).orElseThrow();
        assertEquals(remoteReleases.keySet(), proposal.units().stream().map(unit -> unit.unit().id()).collect(java.util.stream.Collectors.toSet()));
        proposal.units().forEach(unit -> assertEquals(remoteReleases.get(unit.unit().id()), unit.release().sha256()));
        for (var review : reviews) {
            var after = DeploymentAssistedBuildReviewCodec.decodeDescription(review.nextPlan());
            assertEquals(review.nextPlan(), DeploymentAssistedBuildReviewCodec.encodeDescription(after));
            nanoRoundTrip(after);
            var component = proposal.graph().components().stream().filter(value -> value.unit().id().equals(review.unitId())).findFirst().orElseThrow();
            var unit = proposal.units().stream().filter(value -> value.unit().id().equals(review.unitId())).findFirst().orElseThrow();
            assertEquals(after.runtime(), component.reviewedRuntime());
            assertEquals(after.resources(), component.resources());
            assertEquals(after.configuration(), unit.configuration());
            assertEquals(after.secrets(), unit.secrets());
            assertTrue(after.adapterRisk());
            assertEquals(600, after.limits().timeoutSeconds());
            assertEquals(1, after.resources().fileBindings().size());
        }
        reject(connection, original, node -> editDescription(node, "source", "archive", "f".repeat(64)));
        reject(connection, original, node -> editDescription(node, "configuration", "sha256", "f".repeat(64)));
        reject(connection, original, node -> ((ObjectNode) node.path("buildReviews").get(0)).put("previousRoot", "f".repeat(64)));
        reject(connection, original, node -> {
            var review = node.path("buildReviews").get(0);
            try {
                var before = DeploymentAssistedBuildReviewCodec.decodeDescription(review.path("previousPlan").textValue());
                var components = node.path("publicationTemplate").path("runtime").path("components");
                for (var component : components)
                    if (component.path("unitId").textValue().equals(before.applicationId()))
                        ((ObjectNode) component).put("reviewedRuntime", Base64.getEncoder().encodeToString(new DeploymentRuntimeCodec().write(before.runtime())));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        if (!DeploymentExecutionCodec.singleRequest(request))
            reject(connection, original, node -> {
                var review = (ObjectNode) node.path("buildReviews").get(0);
                try {
                    var graph = DeploymentJsonDocument.parse(review.path("originalGraph").textValue());
                    ((ObjectNode) graph.path("identities")).put(graph.path("order").get(1).textValue(), "f".repeat(64));
                    review.put("originalGraph", graph.toString());
                } catch (SQLException failure) {
                    throw new AssertionError(failure);
                }
            });
        assertEquals(original.requestJson(), read(connection).requestJson());
        return original.requestJson();
    }

    private static void editDescription(ObjectNode request, String object, String field, String value) {
        var review = (ObjectNode) request.path("buildReviews").get(0);
        try {
            var plan = DeploymentJsonDocument.parse(review.path("nextPlan").textValue(), DeploymentAssistedBuildReviewCodec.LIMIT);
            ((ObjectNode) plan.path(object)).put(field, value);
            review.put("nextPlan", plan.toString());
        } catch (SQLException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void nanoRoundTrip(DeploymentAssistedBuildReviewCodec.Description actual) throws Exception {
        var at = java.time.Instant.ofEpochSecond(1_700_000_000L, 123_456_789);
        var facts = new LinkedHashMap<>(actual.facts());
        facts.put("applicationId", actual.applicationId());
        var source = actual.source();
        String digest = DeploymentReviewedPlanDigest.from(new DeploymentReviewedPlanDigest.Values(actual.serverId(), UUID.fromString(source.get("snapshot")), source.get("manifest"), source.get("archive"),
            Long.parseLong(source.get("bytes")), Long.parseLong(source.get("uncompressedBytes")), facts, actual.requirements(), actual.configuration(), actual.secrets(), actual.resources(), actual.runtime(),
            actual.userAccessUrl(), actual.limits(), at, actual.containerRisk(), actual.adapterRisk()));
        var precise = new DeploymentAssistedBuildReviewCodec.Description(actual.applicationId(), actual.serverId(), source, actual.facts(), actual.requirements(), actual.userAccessUrl(), actual.configuration(),
            actual.secrets(), actual.resources(), actual.runtime(), actual.limits(), actual.steps(), actual.inputs(), at, actual.containerRisk(), actual.adapterRisk(), digest);
        String encoded = DeploymentAssistedBuildReviewCodec.encodeDescription(precise);
        var decoded = DeploymentAssistedBuildReviewCodec.decodeDescription(encoded);
        assertEquals(at, decoded.reviewedAt());
        assertEquals(digest, decoded.reviewedRevision());
        assertEquals(encoded, DeploymentAssistedBuildReviewCodec.encodeDescription(decoded));
        var node = DeploymentJsonDocument.parse(encoded, DeploymentAssistedBuildReviewCodec.LIMIT);
        int bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(encoded, DeploymentJsonDocument.encode(node, bytes));
        assertThrows(SQLException.class, () -> DeploymentJsonDocument.encode(node, bytes - 1), "A real small UTF-8 envelope exceeding its injected bound must reject rather than truncate");
    }

    /** Tests a real same-unit chain against self-consistent replacement of its prior full source and script. / 用真实同单元链检验对原完整来源及脚本的自洽替换。
     * @param connection real retained failed execution connection / 真实留存失败执行连接
     * @throws Exception if the actual reader accepts a forged continuation / 实际读取器接受伪造续接时 */
    public static void verifyContinuation(Connection connection) throws Exception {
        var original = read(connection);
        var reviews = DeploymentExecutionCodec.buildReviews(original);
        assertEquals(2, reviews.size());
        assertEquals(reviews.getFirst().unitId(), reviews.getLast().unitId());
        assertEquals(reviews.getFirst().nextPlan(), reviews.getLast().previousPlan());
        assertEquals(reviews.getFirst().nextScript(), reviews.getLast().previousScript());
        assertEquals(reviews.getFirst().nextTools(), reviews.getLast().previousTools());
        reject(connection, original, request -> {
            var second = (ObjectNode) request.path("buildReviews").get(1);
            try {
                for (String field : List.of("previousPlan", "nextPlan")) {
                    var plan = DeploymentJsonDocument.parse(second.path(field).textValue(), DeploymentAssistedBuildReviewCodec.LIMIT);
                    ((ObjectNode) plan.path("source")).put("relativeRoot", "unrelated-root");
                    second.put(field, plan.toString());
                }
                second.put("failureBinding", DeploymentAssistedBuildReview.failureBinding(second.path("previousPlan").textValue(), second.path("previousScript").textValue(),
                    second.path("previousTools").textValue(), second.path("failureEvidence").textValue()));
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        });
        reject(connection, original, request -> {
            var second = (ObjectNode) request.path("buildReviews").get(1);
            second.put("previousScript", second.path("previousScript").textValue() + "\n# forged prior execution\n");
            second.put("failureBinding", DeploymentAssistedBuildReview.failureBinding(second.path("previousPlan").textValue(), second.path("previousScript").textValue(),
                second.path("previousTools").textValue(), second.path("failureEvidence").textValue()));
        });
        assertEquals(original.requestJson(), read(connection).requestJson());
    }

    private static void reject(Connection connection, DeploymentExecutionRecord original, Consumer<ObjectNode> corrupt) throws Exception {
        var request = DeploymentJsonDocument.parse(original.requestJson(), DeploymentExecutionCodec.REQUEST_LIMIT);
        corrupt.accept(request);
        connection.setAutoCommit(false);
        try (var update = connection.prepareStatement("UPDATE deployment_execution SET request_payload=? WHERE execution_id=?")) {
            update.setBytes(1, request.toString().getBytes(StandardCharsets.UTF_8));
            update.setObject(2, original.identity().executionId());
            assertEquals(1, update.executeUpdate());
            assertThrows(SQLException.class, () -> read(connection), "A persisted conflicting review must not become recovery permission");
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private static DeploymentExecutionRecord read(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM deployment_execution")) {
            assertTrue(rows.next());
            return DeploymentExecutionStore.row(rows);
        }
    }
}
