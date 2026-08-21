package gold.debug.windowstolinux.shared.backup.contract.spi;

import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;

import java.util.List;
import java.util.Objects;

/** Tool, engine and table evidence collected before export or restore. / 导出或恢复前采集的工具、引擎与表证据。 */
public record DatabaseCompatibilityEvidence(
        BackupDatabaseType type,
        String engineVersion,
        String toolVersion,
        boolean toolAvailable,
        boolean engineVersionCompatible,
        boolean onlineBackupAvailable,
        boolean allTablesTransactional,
        List<String> evidence
) {
    /** Requires explicit, bounded compatibility evidence. / 要求显式、有界的兼容性证据。 */
    public DatabaseCompatibilityEvidence {
        type = Objects.requireNonNull(type, "type");
        engineVersion = DatabaseContractRules.text(engineVersion, "engineVersion", 128);
        toolVersion = DatabaseContractRules.text(toolVersion, "toolVersion", 128);
        evidence = DatabaseContractRules.evidence(evidence);
    }
}
