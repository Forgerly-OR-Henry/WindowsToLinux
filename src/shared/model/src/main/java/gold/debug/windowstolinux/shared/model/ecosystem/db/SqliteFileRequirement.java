package gold.debug.windowstolinux.shared.model.ecosystem.db;

import java.util.Objects;

import gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation;

/**
 * File location and application input for embedded SQLite. / 嵌入式 SQLite 的文件位置与应用接入声明。
 *
 * @param location the remote URI / 远端 URI
 * @param fileName file name / 文件名称
 * @param accessPath access path / 访问路径
 * @param pathEnvironment path environment / 路径环境
 * @param seedFile seed file / 初始种子文件
 * @param hostLocationExplicit host location explicit / 主机位置显式
 */
public record SqliteFileRequirement(ManagedStorageLocation location, String fileName, String accessPath,
        String pathEnvironment, String seedFile, boolean hostLocationExplicit) {
    /**
     * Initializes sqlite file requirement through its shared constructor contract.
     * <p>通过共享构造契约初始化Sqlite文件要求。
     *
     * @param location the remote URI / 远端 URI
     * @param fileName file name / 文件名称
     * @param accessPath access path / 访问路径
     * @param pathEnvironment path environment / 路径环境
     * @param seedFile seed file / 初始种子文件
     */
    public SqliteFileRequirement(ManagedStorageLocation location, String fileName, String accessPath,
            String pathEnvironment, String seedFile) {
        this(location, fileName, accessPath, pathEnvironment, seedFile, false);
    }

    /**
     * Validates and binds the inputs required by sqlite file requirement.
     * <p>校验并绑定Sqlite文件要求所需输入。
     *
     * @param location the remote URI / 远端 URI
     * @param fileName file name / 文件名称
     * @param accessPath access path / 访问路径
     * @param pathEnvironment path environment / 路径环境
     * @param seedFile seed file / 初始种子文件
     * @param hostLocationExplicit host location explicit / 主机位置显式
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SqliteFileRequirement {
        Objects.requireNonNull(location);
        if (!Objects.requireNonNull(fileName).matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("SQLite requires a plain database file name");
        accessPath = Objects.requireNonNull(accessPath);
        if (!accessPath.isEmpty())
            accessPath = ManagedStorageLocation.validatedPath(accessPath);
        if (!Objects.requireNonNull(pathEnvironment).isEmpty() && !pathEnvironment.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("invalid SQLite path environment variable");
        seedFile = relativeSourceFile(seedFile);
    }

    /**
     * Reconstructs the typed contract from path.
     * <p>从路径重建类型化契约。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param environment environment / 环境
     * @param seed seed / 初始种子
     * @return constructed or resolved sqlite file requirement / 构造或解析得到的Sqlite文件要求
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static SqliteFileRequirement fromPath(String path, String environment, String seed) {
        if (path == null || path.equals("DEFAULT"))
            return new SqliteFileRequirement(ManagedStorageLocation.defaults(), "application.db", "", environment,
                    seed);
        if (path.isBlank() || path.contains("$") || path.contains("{{"))
            return new SqliteFileRequirement(ManagedStorageLocation.unresolved(), "application.db", "", environment,
                    seed);
        if (path.equals(":memory:") || path.startsWith("file:"))
            throw new IllegalArgumentException(
                    "in-memory databases and SQLite URI options are not persistent file declarations");
        path = ManagedStorageLocation.validatedPath(path);
        int separator = path.lastIndexOf('/');
        String file = path.substring(separator + 1);
        String directory = path.startsWith("/") ? path.substring(0, separator) : path;
        return new SqliteFileRequirement(ManagedStorageLocation.custom(directory), file, path, environment, seed);
    }

    /**
     * Allows an omitted source file or a validated relative path within the reviewed source boundary.
     * <p>允许省略源码文件，或使用已审阅源码边界内的已校验相对路径。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return relative source file text / 相对源码文件文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static String relativeSourceFile(String value) {
        if (Objects.requireNonNull(value).isEmpty())
            return value;
        value = ManagedStorageLocation.validatedPath(value);
        if (value.startsWith("/"))
            throw new IllegalArgumentException("seed and initialization files must belong to the reviewed source");
        return value;
    }
}
