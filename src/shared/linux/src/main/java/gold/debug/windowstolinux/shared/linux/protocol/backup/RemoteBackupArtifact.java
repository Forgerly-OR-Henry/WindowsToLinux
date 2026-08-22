package gold.debug.windowstolinux.shared.linux.protocol.backup;

import java.util.Objects;

/** Verified remote artifact evidence; its opaque identifier is usable only with the originating operation. / 已验证远端制品证据；不透明标识仅能用于原操作。 */
public record RemoteBackupArtifact(
        String operationId,
        String artifactId,
        RemoteBackupArtifactKind kind,
        long byteCount,
        String sha256
) {
    /** Validates bounded helper evidence. / 校验有界 helper 证据。 */
    public RemoteBackupArtifact {
        operationId = Objects.requireNonNull(operationId, "operationId").trim();
        artifactId = Objects.requireNonNull(artifactId, "artifactId").trim();
        kind = Objects.requireNonNull(kind, "kind");
        sha256 = Objects.requireNonNull(sha256, "sha256").trim();
        if (!operationId.matches("backup-[0-9a-f]{32}")
                || !artifactId.matches("artifact-[0-9a-f]{32}")
                || byteCount < 1 || byteCount > 64L * 1024 * 1024 * 1024
                || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("remote backup artifact evidence is invalid");
        }
    }
}
