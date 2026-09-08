package gold.debug.windowstolinux.shared.config.resource;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;

import java.util.Objects;

/** Reviewed non-secret database connection; passwords remain exact secret references. / 经审阅的非秘密数据库连接；密码保持为精确秘密引用。 */
public sealed interface ManagedDatabaseConnection
        permits ManagedDatabaseConnection.Sqlite, ManagedDatabaseConnection.Server {
    /** Returns the reviewed database family. / 返回经审阅的数据库族。 */
    ManagedDatabaseEngineType engine();

    /** SQLite file name within its managed database directory. / SQLite 受管数据库目录内的文件名。 */
    record Sqlite(String fileName) implements ManagedDatabaseConnection {
        /** Validates one plain file name without path syntax. / 校验不含路径语法的普通文件名。 */
        public Sqlite {
            fileName = Objects.requireNonNull(fileName, "fileName").trim();
            if (!fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || fileName.equals(".") || fileName.equals("..")) {
                throw new IllegalArgumentException("SQLite fileName must be a bounded plain file name");
            }
        }

        @Override public ManagedDatabaseEngineType engine() { return ManagedDatabaseEngineType.SQLITE; }
    }

    /** Server database endpoint whose password is represented only by an exact secret reference. / 密码仅以精确秘密引用表示的服务器数据库端点。 */
    record Server(
            ManagedDatabaseEngineType engine,
            String host,
            int port,
            String database,
            String username,
            SecretReference passwordReference,
            boolean tlsRequired
    ) implements ManagedDatabaseConnection {
        /** Validates one supported server connection without accepting secret material. / 校验一个受支持且不含秘密内容的服务器连接。 */
        public Server {
            engine = Objects.requireNonNull(engine, "engine");
            if (engine != ManagedDatabaseEngineType.POSTGRESQL
                    && engine != ManagedDatabaseEngineType.MYSQL
                    && engine != ManagedDatabaseEngineType.MARIADB
                    && engine != ManagedDatabaseEngineType.REDIS) {
                throw new IllegalArgumentException("server connection requires PostgreSQL, MySQL, MariaDB or Redis");
            }
            host = text(host, "host", 253);
            if (host.contains("/") || host.contains("\\") || host.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("database host contains path or whitespace syntax");
            }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("database port is invalid");
            database = text(database, "database", 128);
            username = text(username, "username", 128);
            passwordReference = Objects.requireNonNull(passwordReference, "passwordReference");
        }
    }

    private static String text(String value, String name, int maximumLength) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isEmpty() || value.length() > maximumLength
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
