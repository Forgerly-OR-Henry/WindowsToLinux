package gold.debug.windowstolinux.shared.backup.extension.registry;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupAdapter;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.extension.adapter.LinuxDatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.extension.adapter.MysqlDatabaseAdapter;
import gold.debug.windowstolinux.shared.backup.extension.adapter.PostgresqlDatabaseAdapter;
import gold.debug.windowstolinux.shared.backup.extension.adapter.SqliteDatabaseAdapter;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;

/**
 * Closed database-family adapter registry. / 闭合的数据库族适配器注册表。
 */
public final class DatabaseAdapterRegistry {
    /**
     * Adapters.
     * <p>适配器集合。
     */
    private final Map<BackupDatabaseType, DatabaseBackupAdapter> adapters;

    /**
     * Builds a duplicate-free registry. / 构建无重复注册表。
     *
     * @param adapters adapters / 适配器集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Creates the complete built-in adapter set. / 创建完整内置适配器集合。
     *
     * @param operations operations / 操作集合
     * @return the complete built-in adapter set / 完整内置适配器集合
     */
    public static DatabaseAdapterRegistry defaults(DatabaseOperationPort operations) {
        return new DatabaseAdapterRegistry(
                List.of(new SqliteDatabaseAdapter(operations), new PostgresqlDatabaseAdapter(operations),
                        new MysqlDatabaseAdapter(BackupDatabaseType.MYSQL, operations),
                        new MysqlDatabaseAdapter(BackupDatabaseType.MARIADB, operations)));
    }

    /**
     * Creates the complete adapter set over the public Linux contract. / 基于 Linux 公共契约创建完整适配器集合。
     *
     * @param remote the credential-free remote / 不含凭据的远端
     * @return the complete adapter set over the public Linux contract / 基于 Linux 公共契约创建完整适配器集合
     */
    public static DatabaseAdapterRegistry defaults(RemoteDatabasePort remote) {
        return defaults(new LinuxDatabaseOperationPort(remote));
    }

    /**
     * Returns the exact adapter or rejects unsupported database families. / 返回精确适配器或拒绝不受支持数据库族。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @return the exact adapter or rejects unsupported database families / 精确适配器或拒绝不受支持数据库族
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DatabaseBackupAdapter require(BackupDatabaseType type) {
        DatabaseBackupAdapter adapter = adapters.get(Objects.requireNonNull(type, "type"));
        if (adapter == null)
            throw new IllegalArgumentException("no database backup adapter exists for " + type);
        return adapter;
    }
}
