package gold.debug.windowstolinux.shared.config.resource;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation;

import java.util.Objects;

/**
 * Reviewed non-secret database connection; passwords remain exact secret references. / 经审阅的非秘密数据库连接；密码保持为精确秘密引用。
 */
public sealed interface ManagedDatabaseConnection
        permits ManagedDatabaseConnection.Sqlite, ManagedDatabaseConnection.Server {
    /**
     * Returns the reviewed database family. / 返回经审阅的数据库族。
     *
     * @return the reviewed database family / 经审阅的数据库族
     */
    ManagedDatabaseEngineType engine();

    /**
     * SQLite file name within its managed database directory. / SQLite 受管数据库目录内的文件名。
     *
     * @param fileName file name / 文件名称
     * @param location the remote URI / 远端 URI
     * @param accessPath access path / 访问路径
     * @param seedFile seed file / 初始种子文件
     * @param initializationFiles initialization files / 初始化文件集合
     */
    record Sqlite(String fileName, ManagedStorageLocation location, String accessPath, String seedFile, java.util.List<String> initializationFiles) implements ManagedDatabaseConnection {
        /**
         * Validates one plain file name without path syntax. / 校验不含路径语法的普通文件名。
         *
         * @param fileName file name / 文件名称
         * @param location the remote URI / 远端 URI
         * @param accessPath access path / 访问路径
         * @param seedFile seed file / 初始种子文件
         * @param initializationFiles initialization files / 初始化文件集合
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Sqlite {
            seedFile = gold.debug.windowstolinux.shared.model.ecosystem.db.SqliteFileRequirement.relativeSourceFile(seedFile);
            initializationFiles = java.util.List.copyOf(initializationFiles);
            if (initializationFiles.size() > 32) throw new IllegalArgumentException("too many initialization files");
            initializationFiles.forEach(gold.debug.windowstolinux.shared.model.ecosystem.db.SqliteFileRequirement::relativeSourceFile);
            location = Objects.requireNonNull(location, "location");
            accessPath = Objects.requireNonNull(accessPath, "accessPath").trim();
            if (!accessPath.isEmpty()) accessPath = ManagedStorageLocation.validatedPath(accessPath);
            fileName = Objects.requireNonNull(fileName, "fileName").trim();
            if (!fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || fileName.equals(".") || fileName.equals("..")) {
                throw new IllegalArgumentException("SQLite fileName must be a bounded plain file name");
            }
        }

        /**
         * Initializes sqlite through its shared constructor contract.
         * <p>通过共享构造契约初始化Sqlite。
         *
         * @param fileName file name / 文件名称
         * @param location the remote URI / 远端 URI
         * @param accessPath access path / 访问路径
         */
        public Sqlite(String fileName, ManagedStorageLocation location, String accessPath) { this(fileName, location, accessPath, "", java.util.List.of()); }

        /**
         * Initializes sqlite through its shared constructor contract.
         * <p>通过共享构造契约初始化Sqlite。
         *
         * @param fileName file name / 文件名称
         */
        public Sqlite(String fileName) { this(fileName, ManagedStorageLocation.defaults(), ""); }

        /**
         * Resolves the SQLite filename beneath the application's managed database location.
         * <p>在应用受管数据库位置下解析 SQLite 文件名。
         *
         * @param applicationId managed application identifier / 受管应用标识
         * @param databaseId database id / 数据库标识
         * @return the SQLite filename beneath the application's managed database location / 在应用受管数据库位置下解析 SQLite 文件名
         */
        public String physicalPath(String applicationId, String databaseId) {
            return location.resolve(applicationId, ManagedStorageLocation.StorageResourceType.DATABASE, databaseId) + "/" + fileName;
        }

        /**
         * Returns engine.
         * <p>返回引擎。
         *
         * @return engine / 引擎
         */
        @Override public ManagedDatabaseEngineType engine() { return ManagedDatabaseEngineType.SQLITE; }
    }

    /**
     * Server database endpoint whose password is represented only by an exact secret reference. / 密码仅以精确秘密引用表示的服务器数据库端点。
     *
     * @param engine engine / 引擎
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param passwordReference password reference / 密码引用
     * @param tlsRequired tls required / tls必需
     */
    record Server(
            ManagedDatabaseEngineType engine,
            String host,
            int port,
            String database,
            String username,
            SecretReference passwordReference,
            boolean tlsRequired
    ) implements ManagedDatabaseConnection {
        /**
         * Validates one supported server connection without accepting secret material. / 校验一个受支持且不含秘密内容的服务器连接。
         *
         * @param engine engine / 引擎
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

    /**
     * Validates textual content against the declared length and character constraints.
     * <p>按声明的长度及字符约束校验文本内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param maximumLength maximum length / 最大长度
     * @return text text / 文本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String text(String value, String name, int maximumLength) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isEmpty() || value.length() > maximumLength
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
