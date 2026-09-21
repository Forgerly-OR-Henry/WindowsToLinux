package gold.debug.windowstolinux.shared.backup.contract.spi;

import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;

import java.util.Objects;

/**
 * Non-secret database connection identity; passwords remain opaque secret references. / 非秘密数据库连接身份；密码始终是不透明秘密引用。
 */
public sealed interface DatabaseConnectionProfile
        permits DatabaseConnectionProfile.Sqlite, DatabaseConnectionProfile.Server {
    /**
     * Returns the database family. / 返回数据库族。
     *
     * @return the database family / 数据库族
     */
    BackupDatabaseType type();

    /**
     * Reviewed SQLite physical binding. / 已审阅的 SQLite 物理绑定。
     *
     * @param bindingId binding id / 绑定标识
     * @param location the remote URI / 远端 URI
     * @param fileName file name / 文件名称
     */
    record Sqlite(String bindingId, gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation location, String fileName) implements DatabaseConnectionProfile {
        /**
         * Validates the reviewed storage binding and plain database file name. / 校验已审阅存储绑定和数据库文件名。
         *
         * @param bindingId binding id / 绑定标识
         * @param location the remote URI / 远端 URI
         * @param fileName file name / 文件名称
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Sqlite {
            if (!Objects.requireNonNull(bindingId).matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("invalid SQLite storage binding");
            Objects.requireNonNull(location);
            if (location.type() == gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageLocationType.UNRESOLVED) throw new IllegalArgumentException("SQLite location must be reviewed");
            if (!Objects.requireNonNull(fileName).matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new IllegalArgumentException("invalid SQLite file name");
        }

        /**
         * Returns selected member of the supported type set.
         * <p>返回受支持类型集合中的所选项。
         *
         * @return selected member of the supported type set / 受支持类型集合中的所选项
         */
        @Override public BackupDatabaseType type() { return BackupDatabaseType.SQLITE; }
    }

    /**
     * Server database endpoint and password reference without password material. / 不含密码内容的服务器数据库端点与密码引用。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param passwordReference password reference / 密码引用
     * @param tlsRequired tls required / tls必需
     */
    record Server(
            BackupDatabaseType type,
            String host,
            int port,
            String database,
            String username,
            SecretReference passwordReference,
            boolean tlsRequired
    ) implements DatabaseConnectionProfile {
        /**
         * Validates a supported server database profile. / 校验受支持的服务器数据库配置。
         *
         * @param type selected member of the supported type set / 受支持类型集合中的所选项
         * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
         * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
         * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
         * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
         * @param passwordReference password reference / 密码引用
         * @param tlsRequired tls required / tls必需
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Server {
            type = Objects.requireNonNull(type, "type");
            if (type != BackupDatabaseType.POSTGRESQL && type != BackupDatabaseType.MYSQL
                    && type != BackupDatabaseType.MARIADB) {
                throw new IllegalArgumentException("server profile requires PostgreSQL, MySQL or MariaDB");
            }
            host = DatabaseContractRules.host(host);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("database port is invalid");
            database = DatabaseContractRules.name(database, "database");
            username = DatabaseContractRules.name(username, "username");
            passwordReference = Objects.requireNonNull(passwordReference, "passwordReference");
        }
    }
}
