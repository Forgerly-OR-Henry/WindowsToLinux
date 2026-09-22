package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;

/**
 * Tool, engine and table evidence collected before export or restore. / 导出或恢复前采集的工具、引擎与表证据。
 *
 * @param type selected member of the supported type set / 受支持类型集合中的所选项
 * @param engineVersion engine version / 引擎版本
 * @param toolVersion tool version / 工具版本
 * @param toolAvailable tool available / 工具可用
 * @param engineVersionCompatible engine version compatible / 引擎版本兼容
 * @param onlineBackupAvailable online backup available / 在线备份可用
 * @param allTablesTransactional all tables transactional / 全部表集合Transactional
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record DatabaseCompatibilityEvidence(BackupDatabaseType type, String engineVersion, String toolVersion,
        boolean toolAvailable, boolean engineVersionCompatible, boolean onlineBackupAvailable,
        boolean allTablesTransactional, List<String> evidence) {
    /**
     * Requires explicit, bounded compatibility evidence. / 要求显式、有界的兼容性证据。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param engineVersion engine version / 引擎版本
     * @param toolVersion tool version / 工具版本
     * @param toolAvailable tool available / 工具可用
     * @param engineVersionCompatible engine version compatible / 引擎版本兼容
     * @param onlineBackupAvailable online backup available / 在线备份可用
     * @param allTablesTransactional all tables transactional / 全部表集合Transactional
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DatabaseCompatibilityEvidence {
        type = Objects.requireNonNull(type, "type");
        engineVersion = DatabaseContractRules.text(engineVersion, "engineVersion", 128);
        toolVersion = DatabaseContractRules.text(toolVersion, "toolVersion", 128);
        evidence = DatabaseContractRules.evidence(evidence);
    }
}
