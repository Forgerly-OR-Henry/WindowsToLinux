package gold.debug.windowstolinux.shared.linux.protocol.backup;

import java.util.Objects;

/**
 * Verified remote artifact evidence; its opaque identifier is usable only with the originating operation. / 已验证远端制品证据；不透明标识仅能用于原操作。
 *
 * @param operationId identifier shared by the remote operation and its maintenance markers / 远端操作及其维护标记共享的标识
 * @param artifactId artifact id / 制品标识
 * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
 * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
 * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
 */
public record RemoteBackupArtifact(String operationId, String artifactId, RemoteBackupArtifactKind kind, long byteCount,
        String sha256) {
    /**
     * Validates bounded helper evidence. / 校验有界 helper 证据。
     *
     * @param operationId identifier shared by the remote operation and its maintenance markers / 远端操作及其维护标记共享的标识
     * @param artifactId artifact id / 制品标识
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
     * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RemoteBackupArtifact {
        operationId = Objects.requireNonNull(operationId, "operationId").trim();
        artifactId = Objects.requireNonNull(artifactId, "artifactId").trim();
        kind = Objects.requireNonNull(kind, "kind");
        sha256 = Objects.requireNonNull(sha256, "sha256").trim();
        if (!operationId.matches("backup-[0-9a-f]{32}") || !artifactId.matches("artifact-[0-9a-f]{32}") || byteCount < 1
                || byteCount > 64L * 1024 * 1024 * 1024 || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("remote backup artifact evidence is invalid");
        }
    }
}
