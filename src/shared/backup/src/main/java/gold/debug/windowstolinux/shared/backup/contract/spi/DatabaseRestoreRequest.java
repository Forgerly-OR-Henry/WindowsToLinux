package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.Objects;

/**
 * Isolated database restore request bound to one verified artifact. / 绑定到一个已验证导出物的隔离数据库恢复请求。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param credentialApplicationId credential application id / 凭据应用标识
 * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
 * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
 * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
 */
public record DatabaseRestoreRequest(
        String applicationId,
        String credentialApplicationId,
        String candidateId,
        DatabaseConnectionProfile target,
        DatabaseBackupArtifact artifact
) {
    /**
     * Validates candidate and database type identity. / 校验候选与数据库类型身份。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param credentialApplicationId credential application id / 凭据应用标识
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DatabaseRestoreRequest {
        applicationId = DatabaseContractRules.identifier(applicationId, "applicationId");
        credentialApplicationId = DatabaseContractRules.identifier(
                credentialApplicationId, "credentialApplicationId");
        candidateId = DatabaseContractRules.candidate(applicationId, candidateId);
        target = Objects.requireNonNull(target, "target");
        artifact = Objects.requireNonNull(artifact, "artifact");
        if (target.type() != artifact.database().type()) {
            throw new IllegalArgumentException("restore target database type differs from the artifact");
        }
    }

    /**
     * Creates a single-component request whose candidate and credential namespace are identical. / 创建候选与凭据命名空间相同的单组件请求。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     */
    public DatabaseRestoreRequest(
            String applicationId,
            String candidateId,
            DatabaseConnectionProfile target,
            DatabaseBackupArtifact artifact
    ) {
        this(applicationId, applicationId, candidateId, target, artifact);
    }
}
