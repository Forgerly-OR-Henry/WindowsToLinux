package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseConnectionProfile;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseEngineType;

/** Maps stored database bindings to backup profiles without remote work. / 将已存数据库绑定映射为备份配置，不执行远端操作。 */
final class BackupDatabaseProfileMapper {
    private BackupDatabaseProfileMapper() { }
    static DatabaseConnectionProfile profile(ManagedDatabaseConnection connection) {
        if (connection instanceof ManagedDatabaseConnection.Sqlite sqlite) {
            return new DatabaseConnectionProfile.Sqlite(sqlite.fileName());
        }
        ManagedDatabaseConnection.Server server = (ManagedDatabaseConnection.Server) connection;
        return new DatabaseConnectionProfile.Server(type(server.engine()), server.host(), server.port(),
                server.database(), server.username(), server.passwordReference(), server.tlsRequired());
    }

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
