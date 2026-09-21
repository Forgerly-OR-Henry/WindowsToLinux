package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseConnectionProfile;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseEngineType;

/**
 * Maps stored database bindings to backup profiles without remote work. / 将已存数据库绑定映射为备份配置，不执行远端操作。
 */
final class BackupDatabaseProfileMapper {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private BackupDatabaseProfileMapper() { }
    /**
     * Builds database connection profile from the supplied profile inputs.
     * <p>根据所提供配置资料输入构建数据库连接配置资料。
     *
     * @param binding binding / 绑定
     * @return database connection profile from the supplied profile inputs / 根据所提供配置资料输入构建数据库连接配置资料
     */
    static DatabaseConnectionProfile profile(gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding binding) {
        var connection = binding.connection();
        if (connection instanceof ManagedDatabaseConnection.Sqlite sqlite) {
            return new DatabaseConnectionProfile.Sqlite(binding.databaseId(),sqlite.location(),sqlite.fileName());
        }
        ManagedDatabaseConnection.Server server = (ManagedDatabaseConnection.Server) connection;
        return new DatabaseConnectionProfile.Server(type(server.engine()), server.host(), server.port(),
                server.database(), server.username(), server.passwordReference(), server.tlsRequired());
    }

    /**
     * Maps the supplied engine or protocol discriminator to the supported type contract.
     * <p>将提供的引擎或协议判别码映射为受支持的类型契约。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @return constructed or resolved backup database type / 构造或解析得到的备份数据库类型
     */
    static BackupDatabaseType type(ManagedDatabaseEngineType type) {
        return switch (type) {
            case SQLITE -> BackupDatabaseType.SQLITE;
            case POSTGRESQL -> BackupDatabaseType.POSTGRESQL;
            case MYSQL -> BackupDatabaseType.MYSQL;
            case MARIADB -> BackupDatabaseType.MARIADB;
            case REDIS -> throw ApplicationServiceException.create(ApplicationServiceFailureType.BACKUP_INPUT_INCOMPLETE, "complete Redis backup is unsupported");
        };
    }
}
