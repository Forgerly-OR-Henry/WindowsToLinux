package gold.debug.windowstolinux.shared.backup.extension.adapter;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupArtifact;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseCompatibilityEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;

/**
 * Validates database adapter output against the requested engine and consistency contract.
 * <p>按请求的数据库引擎及一致性契约验证适配器输出。
 */
final class DatabaseAdapterEvidence {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DatabaseAdapterEvidence() {
    }

    /**
     * Requires ready.
     * <p>要求就绪。
     *
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    static void requireReady(DatabaseCompatibilityEvidence evidence, BackupDatabaseType type) throws BackupException {
        if (evidence.type() != type || !evidence.toolAvailable() || !evidence.engineVersionCompatible()) {
            throw BackupException.create(BackupFailureType.DATABASE_PREFLIGHT_FAILED,
                    "database type, tool availability or engine compatibility preflight failed");
        }
    }

    /**
     * Verifies verified build or backup artifact metadata.
     * <p>验证已验证构建或备份制品元数据。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return constructed or resolved database backup artifact / 构造或解析得到的数据库备份制品
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    static DatabaseBackupArtifact verifyArtifact(
            DatabaseBackupArtifact artifact, BackupDatabaseType type, BackupConsistencyMode mode,
            DatabaseCompatibilityEvidence evidence) throws BackupException {
        if (artifact.database().type() != type || artifact.database().consistencyMode() != mode
                || !artifact.database().engineVersion().equals(evidence.engineVersion())
                || !artifact.database().toolVersion().equals(evidence.toolVersion())) {
            throw BackupException.create(BackupFailureType.DATABASE_EVIDENCE_INVALID,
                    "database export evidence differs from its completed preflight");
        }
        return artifact;
    }

    /**
     * Requires restore compatible.
     * <p>要求恢复兼容。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    static void requireRestoreCompatible(
            DatabaseRestoreRequest request, DatabaseCompatibilityEvidence evidence, BackupDatabaseType type)
            throws BackupException {
        requireReady(evidence, type);
        String sourceFamily = versionFamily(request.artifact().database().engineVersion());
        String targetFamily = versionFamily(evidence.engineVersion());
        if (!sourceFamily.equals(targetFamily)) {
            throw BackupException.create(BackupFailureType.DATABASE_PREFLIGHT_FAILED,
                    "database restore requires a matching engine major version family");
        }
    }

    /**
     * Extracts the numeric major database version or rejects evidence without one.
     * <p>提取数据库数字主版本，或拒绝没有主版本的证据。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @return the numeric major database version or rejects evidence without one / 数据库数字主版本，或拒绝没有主版本的证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private static String versionFamily(String version) throws BackupException {
        var matcher = java.util.regex.Pattern.compile("(?:^|[^0-9])([0-9]+)(?:[^0-9]|$)").matcher(version);
        if (!matcher.find()) {
            throw BackupException.create(BackupFailureType.DATABASE_EVIDENCE_INVALID,
                    "database engine evidence has no version family");
        }
        return matcher.group(1);
    }
}
