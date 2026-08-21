package gold.debug.windowstolinux.shared.backup.manifest;

import java.util.List;
import java.util.Objects;

/** Database identity, compatibility evidence, and consistency limitations. / 数据库身份、兼容性证据和一致性限制。 */
public record BackupDatabase(
        BackupDatabaseType type,
        String reference,
        String engineVersion,
        String toolVersion,
        BackupConsistencyMode consistencyMode,
        List<String> limitations
) {
    /** Validates database evidence without accepting implicit consistency claims. / 校验数据库证据且不接受隐式一致性声明。 */
    public BackupDatabase {
        type = Objects.requireNonNull(type, "type");
        consistencyMode = Objects.requireNonNull(consistencyMode, "consistencyMode");
        reference = BackupManifestRules.requiredText(reference, "reference", 256);
        engineVersion = BackupManifestRules.requiredText(engineVersion, "engineVersion", 128);
        toolVersion = BackupManifestRules.requiredText(toolVersion, "toolVersion", 128);
        limitations = BackupManifestRules.distinctTexts(limitations, "limitations", 64, 512);
        if (type == BackupDatabaseType.NONE && consistencyMode != BackupConsistencyMode.NOT_APPLICABLE) {
            throw new IllegalArgumentException("a database-free backup cannot claim a consistency method");
        }
        if (type != BackupDatabaseType.NONE && consistencyMode == BackupConsistencyMode.NOT_APPLICABLE) {
            throw new IllegalArgumentException("a database backup must record its consistency method");
        }
        if (type == BackupDatabaseType.SQLITE
                && consistencyMode != BackupConsistencyMode.SQLITE_ONLINE_BACKUP
                && consistencyMode != BackupConsistencyMode.SQLITE_WRITES_STOPPED) {
            throw new IllegalArgumentException("SQLite requires online-backup or stopped-writes evidence");
        }
        if (type == BackupDatabaseType.POSTGRESQL
                && consistencyMode != BackupConsistencyMode.POSTGRESQL_LOGICAL_DUMP) {
            throw new IllegalArgumentException("PostgreSQL requires a controlled logical dump");
        }
        if ((type == BackupDatabaseType.MYSQL || type == BackupDatabaseType.MARIADB)
                && consistencyMode != BackupConsistencyMode.MYSQL_TRANSACTION_SNAPSHOT
                && consistencyMode != BackupConsistencyMode.MYSQL_WRITES_STOPPED) {
            throw new IllegalArgumentException("MySQL-compatible databases require transaction or stopped-writes evidence");
        }
    }

    /** Creates the explicit database-free descriptor. / 创建显式无数据库描述。 */
    public static BackupDatabase none() {
        return new BackupDatabase(BackupDatabaseType.NONE, "none", "none", "none",
                BackupConsistencyMode.NOT_APPLICABLE, List.of());
    }
}
