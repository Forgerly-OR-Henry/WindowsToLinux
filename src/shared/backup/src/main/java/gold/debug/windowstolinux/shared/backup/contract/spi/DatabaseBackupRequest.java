package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.Objects;

/** Explicit consistency facts supplied before database export. / 数据库导出前提供的显式一致性事实。 */
public record DatabaseBackupRequest(
        String applicationId,
        DatabaseConnectionProfile connection,
        boolean applicationWritesStopped,
        boolean exclusiveWriterConfirmed
) {
    /** Validates request identity and rejects contradictory write-state claims. / 校验请求身份并拒绝矛盾写入状态声明。 */
    public DatabaseBackupRequest {
        applicationId = DatabaseContractRules.identifier(applicationId, "applicationId");
        connection = Objects.requireNonNull(connection, "connection");
        if (exclusiveWriterConfirmed && !applicationWritesStopped) {
            throw new IllegalArgumentException("exclusive writer confirmation requires stopped application writes");
        }
    }
}
