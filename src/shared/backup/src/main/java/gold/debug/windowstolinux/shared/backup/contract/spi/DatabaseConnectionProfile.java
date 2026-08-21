package gold.debug.windowstolinux.shared.backup.contract.spi;

import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;

import java.util.Objects;

/** Non-secret database connection identity; passwords remain opaque secret references. / 非秘密数据库连接身份；密码始终是不透明秘密引用。 */
public sealed interface DatabaseConnectionProfile
        permits DatabaseConnectionProfile.Sqlite, DatabaseConnectionProfile.Server {
    /** Returns the database family. / 返回数据库族。 */
    BackupDatabaseType type();

    /** Managed relative SQLite path. / 受管相对 SQLite 路径。 */
    record Sqlite(String relativePath) implements DatabaseConnectionProfile {
        /** Validates a relative path without traversal or platform drive syntax. / 校验无穿越或平台驱动器语法的相对路径。 */
        public Sqlite {
            relativePath = DatabaseContractRules.relativePath(relativePath, "relativePath");
        }

        @Override public BackupDatabaseType type() { return BackupDatabaseType.SQLITE; }
    }

    /** Server database endpoint and password reference without password material. / 不含密码内容的服务器数据库端点与密码引用。 */
    record Server(
            BackupDatabaseType type,
            String host,
            int port,
            String database,
            String username,
            SecretReference passwordReference,
            boolean tlsRequired
    ) implements DatabaseConnectionProfile {
        /** Validates a supported server database profile. / 校验受支持的服务器数据库配置。 */
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
