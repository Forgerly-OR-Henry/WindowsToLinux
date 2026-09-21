package gold.debug.windowstolinux.shared.linux.sshd.backup.execution.protocol;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifact;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactKind;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict parser for bounded helper backup evidence. / 有界 helper 备份证据的严格解析器。
 */
public final class ManagedBackupProtocolParser {
    /**
     * Parses exact artifact identity, kind, size and digest. / 解析精确制品身份、种类、长度和摘要。
     *
     * @param operationId identifier shared by the remote operation and its maintenance markers / 远端操作及其维护标记共享的标识
     * @param expectedKind expected kind / 预期种类
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @return exact artifact identity, kind, size and digest / 精确制品身份、种类、长度和摘要
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RemoteBackupArtifact artifact(String operationId, RemoteBackupArtifactKind expectedKind, String output)
            throws LinuxOperationException {
        try {
            Map<String, String> values = SshCommandExecutor.lines(Objects.requireNonNull(output, "output"));
            if (!values.keySet().equals(Set.of("ARTIFACT", "KIND", "SIZE", "SHA256"))
                    || !kind(expectedKind).equals(values.get("KIND"))) {
                throw new IllegalArgumentException("backup evidence fields or kind differ");
            }
            long size = Long.parseLong(values.get("SIZE"));
            return new RemoteBackupArtifact(operationId, values.get("ARTIFACT"), expectedKind, size,
                    values.get("SHA256"));
        } catch (RuntimeException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.BACKUP_ARTIFACT_EVIDENCE_INVALID,
                    "managed backup helper evidence is malformed", exception);
        }
    }

    /**
     * Maps the artifact kind to its fixed managed-helper protocol token.
     * <p>将制品类型映射为固定受管 helper 协议令牌。
     *
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @return kind text / 种类文本
     */
    private static String kind(RemoteBackupArtifactKind kind) {
        return switch (kind) {
            case FILE_TREE -> "file";
            case RELEASE_TREE -> "release";
            case VOLUME -> "volume";
            case OCI_IMAGE -> "oci";
        };
    }
}
