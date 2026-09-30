package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;

/** Verifies the full formal operation chain through its actual package codecs. / 通过实际包内codec核验完整正式操作链。 */
public final class DeploymentAssistedHealthEvidenceAssertions {
    private DeploymentAssistedHealthEvidenceAssertions() {
    }

    /** Checks actual retired child proofs and source-bound distinct candidates. / 检查实际已退出子项证明和绑定来源的独立候选。
     * @param request actual H2 formal request / 实际H2正式请求
     * @param taskId authentic root / 真实原根
     * @param count complete child count / 完整子项数
     * @param retries consumed original retries / 已消费原重试
     * @param archives original frozen archive identities / 原冻结归档身份
     * @param candidateArchives actual uploaded candidate bytes / 实际上传候选字节
     * @throws Exception if retained chain or evidence rejects / 留存链或证据拒绝时 */
    public static void verify(JsonNode request, UUID taskId, int count, int retries, Map<String, String> archives,
            Map<String, String> candidateArchives) throws Exception {
        UUID previousOperation = taskId;
        var candidates = new HashSet<String>();
        for (int index = 0; index < retries; index++) {
            var review = gold.debug.windowstolinux.mod.deployment.db.DeploymentPublicationAttemptReview
                    .decode(request.path("attemptReviews").get(index));
            var proof = gold.debug.windowstolinux.mod.deployment.db.DeploymentPublicationAttemptProof
                    .decode(request.path("attemptProofs").get(index));
            assertEquals(index + 1, review.retry());
            assertEquals(previousOperation, review.previousOperation());
            assertNotEquals(previousOperation, review.nextOperation());
            assertEquals(previousOperation, proof.operation());
            assertEquals(taskId, proof.task());
            assertEquals(count, proof.children().size());
            assertEquals(0, request.path("attemptBuildReviews").get(index).size());
            var before = DeploymentAssistedBuildReviewCodec.decodeDescription(review.previousPlan());
            var after = DeploymentAssistedBuildReviewCodec.decodeDescription(review.nextPlan());
            assertEquals(before.source(), after.source());
            assertEquals(before.configuration(), after.configuration());
            assertNotEquals(before.runtime(), after.runtime());
            assertEquals(index == 0 ? "1.24" : "1.25", before.inputs().get("version"));
            assertEquals(index == 0 ? "1.25" : "1.24", after.inputs().get("version"));
            for (var child : proof.children()) {
                assertEquals("SESSION_CLOSED", child.checkpoint().path("phase").asText());
                assertTrue(child.current().isEmpty());
                assertFalse(child.running());
                assertEquals(600, child.limitSeconds());
                assertTrue(child.remainingSeconds() <= child.limitSeconds());
                assertTrue(candidates.add(child.checkpoint().path("candidate").asText()));
                String archive = archives.values().stream()
                        .filter(sha -> candidateArchives.get(child.checkpoint().path("candidate").asText()).equals(sha))
                        .findFirst().orElseThrow();
                var oldWorkspace = new DeploymentRemoteWorkspace(child.unitId(), archive,
                        index == 0 ? Optional.empty() : Optional.of(previousOperation));
                assertEquals(oldWorkspace.candidateId(), child.checkpoint().path("candidate").asText());
            }
            previousOperation = review.nextOperation();
        }
        assertEquals(previousOperation.toString(), request.path("attemptOperation").asText());
    }

    /** Exercises real retained review binding without changing the formal H2 record. / 不修改正式H2记录，检验实际留存审阅绑定。
     * @param connection actual completed CLASS scope / 实际已结束CLASS作用域
     * @throws Exception if complete canonical configuration binding differs / 完整规范配置绑定不符时 */
    public static void verifyConfigurationPrecision(java.sql.Connection connection) throws Exception {
        final DeploymentExecutionRecord original;
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT * FROM deployment_execution")) {
            assertTrue(rows.next());
            original = DeploymentExecutionRepository.row(rows);
            assertFalse(rows.next());
        }
        var proposal = DeploymentExecutionCodec.publicationTemplate(original).orElseThrow();
        var request = DeploymentJsonDocument.parse(original.requestJson(), DeploymentExecutionCodec.READ_LIMIT);
        assertFalse(request.path("attemptReviews").isEmpty());
        assertFalse(request.path("attemptTemplate").isNull());
        request.putNull("attemptTemplate");
        var beforeTemplate = new DeploymentExecutionRecord(original.identity(), original.phase(), original.outcome(),
                request.toString(), original.recoveryJson(), original.resultJson(), original.businessCommitted(),
                original.cleanupComplete(), original.createdAt(), original.updatedAt());
        var unit = proposal.units().getFirst();
        var configuration = unit.configuration();
        var nanoTime = java.time.Instant.ofEpochMilli(configuration.createdAt().toEpochMilli()).plusNanos(123456);
        assertNotEquals(configuration.createdAt(), nanoTime);
        var equivalent = gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationSnapshot
                .create(configuration.applicationId(), configuration.revision(), configuration.schemaVersion(),
                        nanoTime, configuration.entries());
        assertEquals(configuration.sha256(), equivalent.sha256());
        assertNotEquals(configuration, equivalent);
        assertEquals(DeploymentPublicationAttemptCodec.withTemplate(beforeTemplate, proposal),
                DeploymentPublicationAttemptCodec.withTemplate(beforeTemplate,
                        replaceConfiguration(proposal, equivalent)));
        var revision = gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationSnapshot
                .create(configuration.applicationId(), configuration.revision() + 1, configuration.schemaVersion(),
                        configuration.createdAt(), configuration.entries());
        var entries = new ArrayList<>(configuration.entries());
        entries.add(new gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationEntry(
                "CODEC_SENTINEL",
                gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationScopeType.RUNTIME,
                new gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationValue.Text(
                        "changed")));
        var content = gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationSnapshot
                .create(configuration.applicationId(), configuration.revision(), configuration.schemaVersion(),
                        configuration.createdAt(), entries);
        assertNotEquals(configuration.sha256(), revision.sha256());
        assertNotEquals(configuration.sha256(), content.sha256());
        var time = gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationSnapshot.create(
                configuration.applicationId(), configuration.revision(), configuration.schemaVersion(),
                configuration.createdAt().plusMillis(1), configuration.entries());
        for (var changed : List.of(revision, content, time)) {
            var rejected = assertThrows(java.sql.SQLException.class, () -> DeploymentPublicationAttemptCodec
                    .withTemplate(beforeTemplate, replaceConfiguration(proposal, changed)));
            assertEquals("Attempt changed original complete configuration", rejected.getMessage());
        }
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT * FROM deployment_execution")) {
            assertTrue(rows.next());
            assertEquals(original, DeploymentExecutionRepository.row(rows));
            assertFalse(rows.next());
        }
    }

    private static DeploymentPublicationProposal replaceConfiguration(DeploymentPublicationProposal proposal,
            gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationSnapshot configuration) {
        var units = new ArrayList<>(proposal.units());
        var original = units.getFirst();
        units.set(0, new DeploymentUnitCommit(original.unit(), original.runtime(), original.release(), configuration,
                original.secrets()));
        return new DeploymentPublicationProposal(proposal.graph(), units);
    }
}
