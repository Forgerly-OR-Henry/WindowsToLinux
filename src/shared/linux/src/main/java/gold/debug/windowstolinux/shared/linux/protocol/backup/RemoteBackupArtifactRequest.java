package gold.debug.windowstolinux.shared.linux.protocol.backup;

import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.util.Objects;

/**
 * Identity-bound request for one helper-generated managed backup artifact. / 一个由 helper 生成且绑定身份的受管备份制品请求。
 *
 * @param operationId identifier shared by the remote operation and its maintenance markers / 远端操作及其维护标记共享的标识
 * @param applicationId managed application identifier / 受管应用标识
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param managedApplication managed application / 受管应用
 * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
 * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
 * @param resourceId resource id / 资源标识
 * @param maximumBytes maximum bytes / 最大字节
 */
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
    /**
     * Validates the closed remote collection scope without accepting any path. / 校验封闭远端取材范围且不接受任何路径。
     *
     * @param operationId identifier shared by the remote operation and its maintenance markers / 远端操作及其维护标记共享的标识
     * @param applicationId managed application identifier / 受管应用标识
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param managedApplication managed application / 受管应用
     * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param resourceId resource id / 资源标识
     * @param maximumBytes maximum bytes / 最大字节
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Validates a managed identifier before it reaches a remote resource boundary.
     * <p>在标识到达远端资源边界前验证受管标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return managed id text / 受管标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String managedId(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " must be a bounded managed identifier");
        }
        return value;
    }
}
