package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.Objects;

/** Isolated database restore request bound to one verified artifact. / 绑定到一个已验证导出物的隔离数据库恢复请求。 */
public record DatabaseRestoreRequest(
        String applicationId,
        String candidateId,
        DatabaseConnectionProfile target,
        DatabaseBackupArtifact artifact
) {
    /** Validates candidate and database type identity. / 校验候选与数据库类型身份。 */
    public DatabaseRestoreRequest {
        applicationId = DatabaseContractRules.identifier(applicationId, "applicationId");
        candidateId = DatabaseContractRules.candidate(applicationId, candidateId);
        target = Objects.requireNonNull(target, "target");
        artifact = Objects.requireNonNull(artifact, "artifact");
        if (target.type() != artifact.database().type()) {
            throw new IllegalArgumentException("restore target database type differs from the artifact");
        }
    }
}
