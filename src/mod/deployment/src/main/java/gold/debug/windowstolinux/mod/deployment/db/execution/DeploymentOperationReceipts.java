package gold.debug.windowstolinux.mod.deployment.db.execution;

import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.fields;
import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.object;
import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.parse;
import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.string;
import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.text;
import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.uuid;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.db.check.DeploymentReleaseChecker;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentPayloadReader;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseIdentity;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseOrigin;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseRecord;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseStore;

/** Durable settlement receipts, never an App or an executable recovery request. / 持久结清凭据，绝非App或可执行恢复请求。 */
public final class DeploymentOperationReceipts {
    public static final int LIMIT = 8192;

    private DeploymentOperationReceipts() {
    }

    public record Receipt(UUID operationId, UUID taskId, ServerId server,
                          DeploymentExecutionIdentity.DeploymentExecutionMode mode, TaskResult outcome,
                          Optional<String> applicationId, Optional<DeploymentReleaseIdentity> release,
                          Optional<ProblemCode> problemCode, String eventCode, String proofSha256, Instant settledAt) {
        public Receipt {
            Objects.requireNonNull(operationId);
            Objects.requireNonNull(taskId);
            Objects.requireNonNull(server);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(applicationId);
            Objects.requireNonNull(release);
            Objects.requireNonNull(problemCode);
            Objects.requireNonNull(settledAt);
            settledAt = Instant.ofEpochMilli(settledAt.toEpochMilli());
            if (outcome == TaskResult.UNKNOWN || (outcome == TaskResult.SUCCEEDED) != applicationId.isPresent()
                || applicationId.isPresent() != release.isPresent() || (outcome != TaskResult.SUCCEEDED) != problemCode.isPresent()
                || applicationId.filter(v -> !v.matches("[a-z0-9][a-z0-9-]{0,62}")).isPresent()
                || eventCode == null || !eventCode.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")
                || proofSha256 == null || !proofSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid operation settlement receipt");
        }

        public OperationResult<Void> result() {
            return new OperationResult<>(outcome, Optional.empty(), problemCode.map(code ->
                new Problem(code, eventCode, "Original deployment was settled", Map.of(), Optional.of(taskId))));
        }
    }

    public static Optional<Receipt> read(Connection connection, UUID taskId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM deployment_operation_receipt WHERE platform_task_id=?")) {
            query.setObject(1, taskId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(row(rows));
            }
        }
    }

    public static Receipt row(ResultSet rows) throws SQLException {
        try {
            var node = parse(text(DeploymentPayloadReader.read(rows, "settlement_payload", LIMIT), LIMIT), LIMIT);
            fields(node, Set.of("formatVersion", "settlement", "operationId", "taskId", "serverId", "mode", "outcome",
                "applicationId", "releaseId", "releaseSha256", "problemCode", "eventCode", "proofSha256", "settledAt"));
            if (!node.path("formatVersion").isIntegralNumber() || !node.path("formatVersion").canConvertToInt()
                || node.path("formatVersion").intValue() != 1 || !string(node, "settlement").equals("SETTLED")
                || !node.path("settledAt").isIntegralNumber() || !node.path("settledAt").canConvertToLong())
                throw new SQLException("Settlement proof version or time is invalid");
            var receipt = new Receipt(uuid(node, "operationId"), uuid(node, "taskId"), new ServerId(string(node, "serverId")),
                DeploymentExecutionIdentity.DeploymentExecutionMode.valueOf(string(node, "mode")), TaskResult.valueOf(string(node, "outcome")),
                node.path("applicationId").isNull() ? Optional.empty() : Optional.of(string(node, "applicationId")),
                node.path("releaseId").isNull() ? Optional.empty() : Optional.of(new DeploymentReleaseIdentity(uuid(node, "releaseId"), string(node, "releaseSha256"))),
                node.path("problemCode").isNull() ? Optional.empty() : Optional.of(ProblemCode.valueOf(string(node, "problemCode"))),
                string(node, "eventCode"), string(node, "proofSha256"), Instant.ofEpochMilli(node.path("settledAt").longValue()));
            if (!receipt.operationId().equals(rows.getObject("operation_id", UUID.class))
                || !receipt.taskId().equals(rows.getObject("platform_task_id", UUID.class))
                || !receipt.server().id().equals(rows.getString("server_profile_id")) || !receipt.mode().name().equals(rows.getString("mode"))
                || !receipt.outcome().name().equals(rows.getString("outcome"))
                || !receipt.applicationId().equals(Optional.ofNullable(rows.getString("application_id")))
                || !receipt.release().map(DeploymentReleaseIdentity::id).equals(Optional.ofNullable(rows.getObject("release_id", UUID.class)))
                || !receipt.release().map(DeploymentReleaseIdentity::sha256).equals(Optional.ofNullable(rows.getString("release_sha256")))
                || receipt.settledAt().toEpochMilli() != rows.getLong("settled_at")
                || !encode(receipt).equals(node.toString()))
                throw new SQLException("Settlement proof differs from its original row");
            return receipt;
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            throw new SQLException("Stored operation settlement is invalid", invalid);
        }
    }

    private static String encode(Receipt receipt) throws SQLException {
        var node = object().put("formatVersion", 1).put("settlement", "SETTLED").put("operationId", receipt.operationId().toString())
            .put("taskId", receipt.taskId().toString()).put("serverId", receipt.server().id()).put("mode", receipt.mode().name())
            .put("outcome", receipt.outcome().name()).put("eventCode", receipt.eventCode()).put("proofSha256", receipt.proofSha256())
            .put("settledAt", receipt.settledAt().toEpochMilli());
        receipt.applicationId().ifPresentOrElse(v -> node.put("applicationId", v), () -> node.putNull("applicationId"));
        receipt.release().ifPresentOrElse(v -> {
                node.put("releaseId", v.id().toString());
                node.put("releaseSha256", v.sha256());
            },
            () -> {
                node.putNull("releaseId");
                node.putNull("releaseSha256");
            });
        receipt.problemCode().ifPresentOrElse(v -> node.put("problemCode", v.name()), () -> node.putNull("problemCode"));
        return gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.encode(node, LIMIT);
    }

    public static void store(Connection connection, Receipt receipt) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Settlement requires its original cleanup transaction");
        var existing = read(connection, receipt.taskId());
        if (existing.isPresent()) {
            if (!existing.orElseThrow().equals(receipt))
                throw new SQLException("Original settlement identity or result changed");
            return;
        }
        validateBusiness(connection, receipt);
        try (var insert = connection.prepareStatement("INSERT INTO deployment_operation_receipt(operation_id,platform_task_id,server_profile_id,mode,outcome,application_id,release_id,release_sha256,settlement_payload,settled_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            insert.setObject(1, receipt.operationId());
            insert.setObject(2, receipt.taskId());
            insert.setString(3, receipt.server().id());
            insert.setString(4, receipt.mode().name());
            insert.setString(5, receipt.outcome().name());
            insert.setString(6, receipt.applicationId().orElse(null));
            insert.setObject(7, receipt.release().map(DeploymentReleaseIdentity::id).orElse(null));
            insert.setString(8, receipt.release().map(DeploymentReleaseIdentity::sha256).orElse(null));
            insert.setBytes(9, encode(receipt).getBytes(StandardCharsets.UTF_8));
            insert.setLong(10, receipt.settledAt().toEpochMilli());
            if (insert.executeUpdate() != 1) throw new SQLException("Settlement insertion was not confirmed");
        }
    }

    public static void validateBusiness(Connection connection, Receipt receipt) throws SQLException {
        if (receipt.outcome() != TaskResult.SUCCEEDED) return;
        var release = DeploymentReleaseStore.read(connection, receipt.applicationId().orElseThrow(), receipt.release().orElseThrow().id())
            .orElseThrow(() -> new SQLException("Settled publication is absent"));
        if (!release.identity().equals(receipt.release().orElseThrow())
            || !Set.of(DeploymentReleaseRecord.DeploymentReleaseState.ACTIVE, DeploymentReleaseRecord.DeploymentReleaseState.RETIRED).contains(release.state()))
            throw new SQLException("Settled publication identity or state differs");
        var origin = DeploymentReleaseOrigin.read(connection, release);
        if (!origin.server().equals(receipt.server())) throw new SQLException("Settled publication server differs");
        DeploymentReleaseChecker.validateRecord(connection, release);
    }

    public static void validate(Connection connection) throws SQLException {
        try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT * FROM deployment_operation_receipt")) {
            while (rows.next()) validateBusiness(connection, row(rows));
        }
    }
}
