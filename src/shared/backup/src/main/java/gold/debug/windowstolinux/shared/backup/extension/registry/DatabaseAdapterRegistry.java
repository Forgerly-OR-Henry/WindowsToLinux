package gold.debug.windowstolinux.shared.backup.extension.registry;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupAdapter;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.extension.adapter.MysqlDatabaseAdapter;
import gold.debug.windowstolinux.shared.backup.extension.adapter.PostgresqlDatabaseAdapter;
import gold.debug.windowstolinux.shared.backup.extension.adapter.SqliteDatabaseAdapter;
import gold.debug.windowstolinux.shared.backup.extension.adapter.LinuxDatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Closed database-family adapter registry. / 闭合的数据库族适配器注册表。 */
public final class DatabaseAdapterRegistry {
    private final Map<BackupDatabaseType, DatabaseBackupAdapter> adapters;

    /** Builds a duplicate-free registry. / 构建无重复注册表。 */
    public DatabaseAdapterRegistry(List<DatabaseBackupAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters");
        EnumMap<BackupDatabaseType, DatabaseBackupAdapter> indexed = new EnumMap<>(BackupDatabaseType.class);
        for (DatabaseBackupAdapter adapter : adapters) {
            if (indexed.putIfAbsent(adapter.type(), adapter) != null) {
                throw new IllegalArgumentException("duplicate database backup adapter");
            }
        }
        this.adapters = Map.copyOf(indexed);
    }

    /** Creates the complete built-in adapter set. / 创建完整内置适配器集合。 */
    public static DatabaseAdapterRegistry defaults(DatabaseOperationPort operations) {
        return new DatabaseAdapterRegistry(List.of(
                new SqliteDatabaseAdapter(operations),
                new PostgresqlDatabaseAdapter(operations),
                new MysqlDatabaseAdapter(BackupDatabaseType.MYSQL, operations),
                new MysqlDatabaseAdapter(BackupDatabaseType.MARIADB, operations)));
    }

    /** Creates the complete adapter set over the public Linux contract. / 基于 Linux 公共契约创建完整适配器集合。 */
    public static DatabaseAdapterRegistry defaults(RemoteDatabasePort remote) {
        return defaults(new LinuxDatabaseOperationPort(remote));
    }

    /** Returns the exact adapter or rejects unsupported database families. / 返回精确适配器或拒绝不受支持数据库族。 */
    public DatabaseBackupAdapter require(BackupDatabaseType type) {
        DatabaseBackupAdapter adapter = adapters.get(Objects.requireNonNull(type, "type"));
        if (adapter == null) throw new IllegalArgumentException("no database backup adapter exists for " + type);
        return adapter;
    }
}
