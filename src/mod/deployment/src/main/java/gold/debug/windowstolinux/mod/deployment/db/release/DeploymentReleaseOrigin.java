package gold.debug.windowstolinux.mod.deployment.db.release;

import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.fields;
import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.object;
import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.parse;
import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.string;
import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.text;
import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.uuid;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentPayloadReader;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity;

/** Immutable creating identity owned by a verified release, independent of recovery work. / 已验证发布自有的不可变创建身份，独立于恢复工作。 */
public final class DeploymentReleaseOrigin {
    public static final int LIMIT = 8192;

    private DeploymentReleaseOrigin() {
    }

    public static String encode(DeploymentExecutionIdentity origin, DeploymentReleaseRecord release) throws SQLException {
        if (!origin.executionId().equals(release.executionId()) || !origin.applicationId().equals(release.applicationId()))
            throw new SQLException("Release creating identity differs");
        var node = object().put("formatVersion", 1).put("executionId", origin.executionId().toString())
            .put("taskId", origin.taskId().toString()).put("applicationId", origin.applicationId())
            .put("serverId", origin.server().id()).put("mode", origin.mode().name())
            .put("outcome", "SUCCEEDED").put("businessCommitted", true)
            .put("reviewedRevision", origin.reviewedRevision()).put("releaseId", release.identity().id().toString())
            .put("releaseSha256", release.identity().sha256()).put("verifiedAt", release.createdAt().toEpochMilli());
        if (origin.projectId().isPresent()) node.put("projectId", origin.projectId().orElseThrow().toString());
        else node.putNull("projectId");
        String text = gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.encode(node, LIMIT);
        decode(text, release);
        return text;
    }

    public static DeploymentExecutionIdentity decode(String text, DeploymentReleaseRecord release) throws SQLException {
        var node = parse(text, LIMIT);
        fields(node, Set.of("formatVersion", "executionId", "taskId", "applicationId", "serverId", "mode",
            "reviewedRevision", "projectId", "releaseId", "releaseSha256", "verifiedAt", "outcome", "businessCommitted"));
        try {
            var origin = new DeploymentExecutionIdentity(uuid(node, "executionId"), uuid(node, "taskId"),
                node.path("projectId").isNull() ? Optional.empty() : Optional.of(uuid(node, "projectId")),
                string(node, "applicationId"), new ServerId(string(node, "serverId")),
                DeploymentExecutionIdentity.DeploymentExecutionMode.valueOf(string(node, "mode")), string(node, "reviewedRevision"));
            if (!node.path("formatVersion").isIntegralNumber() || !node.path("formatVersion").canConvertToInt()
                || node.path("formatVersion").intValue() != 1 || !origin.executionId().equals(release.executionId())
                || !string(node, "outcome").equals("SUCCEEDED") || !node.path("businessCommitted").isBoolean()
                || !node.path("businessCommitted").booleanValue()
                || !origin.applicationId().equals(release.applicationId()) || !uuid(node, "releaseId").equals(release.identity().id())
                || !string(node, "releaseSha256").equals(release.identity().sha256())
                || !node.path("verifiedAt").isIntegralNumber() || !node.path("verifiedAt").canConvertToLong()
                || node.path("verifiedAt").longValue() != release.createdAt().toEpochMilli()
                || DeploymentReleaseCodec.snapshot(release).graph().components().stream()
                .anyMatch(c -> !c.unit().server().equals(origin.server())))
                throw new SQLException("Release origin does not match its immutable publication");
            return origin;
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Release origin is invalid", invalid);
        }
    }

    public static DeploymentExecutionIdentity read(Connection connection, DeploymentReleaseRecord release) throws SQLException {
        try (var query = connection.prepareStatement("SELECT origin_payload FROM deployment_release WHERE application_id=? AND release_id=?")) {
            query.setString(1, release.applicationId());
            query.setObject(2, release.identity().id());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Formal release origin is absent");
                try {
                    return decode(text(DeploymentPayloadReader.read(rows, "origin_payload", LIMIT), LIMIT), release);
                } catch (java.io.IOException invalid) {
                    throw new SQLException("Formal release origin cannot be read", invalid);
                }
            }
        }
    }
}
