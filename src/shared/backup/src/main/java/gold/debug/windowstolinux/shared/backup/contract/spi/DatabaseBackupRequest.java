package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.Objects;

/**
 * Explicit consistency facts supplied before database export. / 数据库导出前提供的显式一致性事实。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
 * @param applicationWritesStopped application writes stopped / 应用写入集合已停止
 * @param exclusiveWriterConfirmed exclusive writer confirmed / 独占写入器已确认
 */
public record DatabaseBackupRequest(String applicationId, DatabaseConnectionProfile connection,
        boolean applicationWritesStopped, boolean exclusiveWriterConfirmed) {
    /**
     * Validates request identity and rejects contradictory write-state claims. / 校验请求身份并拒绝矛盾写入状态声明。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param applicationWritesStopped application writes stopped / 应用写入集合已停止
     * @param exclusiveWriterConfirmed exclusive writer confirmed / 独占写入器已确认
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DatabaseBackupRequest {
        applicationId = DatabaseContractRules.identifier(applicationId, "applicationId");
        connection = Objects.requireNonNull(connection, "connection");
        if (exclusiveWriterConfirmed && !applicationWritesStopped) {
            throw new IllegalArgumentException("exclusive writer confirmation requires stopped application writes");
        }
    }
}
