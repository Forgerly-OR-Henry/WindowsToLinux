package gold.debug.windowstolinux.shared.linux.protocol.backup;

import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.util.Objects;

/** Identity-bound request for one helper-generated managed backup artifact. / 一个由 helper 生成且绑定身份的受管备份制品请求。 */
public record RemoteBackupArtifactRequest(
        String operationId,
        String applicationId,
        String componentId,
        ManagedApplication managedApplication,
        String releaseSha256,
        RemoteBackupArtifactKind kind,
        String resourceId,
        long maximumBytes
) {
    /** Validates the closed remote collection scope without accepting any path. / 校验封闭远端取材范围且不接受任何路径。 */
    public RemoteBackupArtifactRequest {
        operationId = Objects.requireNonNull(operationId, "operationId").trim();
        if (!operationId.matches("backup-[0-9a-f]{32}")) {
            throw new IllegalArgumentException("operationId must be one generated backup identity");
        }
        applicationId = managedId(applicationId, "applicationId");
        componentId = managedId(componentId, "componentId");
        managedApplication = Objects.requireNonNull(managedApplication, "managedApplication");
        releaseSha256 = Objects.requireNonNull(releaseSha256, "releaseSha256").trim();
        if (!releaseSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("releaseSha256 must be lowercase SHA-256");
        }
        kind = Objects.requireNonNull(kind, "kind");
        resourceId = Objects.requireNonNull(resourceId, "resourceId").trim();
        if (!resourceId.matches("[a-z0-9][a-z0-9-]{0,127}")) {
            throw new IllegalArgumentException("resourceId must be a bounded managed identifier");
        }
        if (maximumBytes < 1 || maximumBytes > 64L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("maximumBytes is outside the managed backup bound");
        }
    }

    private static String managedId(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " must be a bounded managed identifier");
        }
        return value;
    }
}
