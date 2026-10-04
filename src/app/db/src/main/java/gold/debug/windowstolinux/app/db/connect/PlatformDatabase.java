package gold.debug.windowstolinux.app.db.connect;

import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.init.DatabaseBootstrap;
import gold.debug.windowstolinux.app.db.init.DatabaseProtection;

/** Owns one embedded H2 engine and its protected bootstrap material. / 持有唯一嵌入式 H2 引擎及受保护引导材料。 */
public final class PlatformDatabase implements AutoCloseable {
    /** Canonical data root, independent of the working directory. / 独立于当前工作目录的规范数据根。 */
    private final Path root;

    /** Locked local instance file channel. / 持有实例锁的本地文件通道。 */
    private final FileChannel lockChannel;

    /** Exclusive ownership across platform instances. / 跨平台实例的独占所有权。 */
    private final FileLock lock;

    /** Wipeable bootstrap material. / 可清零的引导材料。 */
    private final DatabaseBootstrap bootstrap;

    /** Engine anchor retained until all callers release connections. / 保留到所有调用方释放连接后的引擎锚点。 */
    private final Connection anchor;

    /** Canonical private H2 URL with non-admin connection settings. / 带非管理员连接设置的私有规范 H2 URL。 */
    private final String jdbcUrl;

    /** Current process startup identity. / 当前进程的启动身份。 */
    private final UUID bootId = UUID.randomUUID();

    /** Owned connections still requiring actual close. / 尚待实际关闭的所属连接集合。 */
    private final Map<Connection, String> connections = new IdentityHashMap<>();

    /** Actual closed state; failed close does not set it. / 实际关闭状态；关闭失败不会设置该值。 */
    private boolean closed;

    /**
     * Takes ownership after the instance lock and driver connection succeed. / 实例锁及驱动连接成功后接管资源。
     * @param root canonical data root / 规范数据根
     * @param lockChannel owned lock channel / 所属锁通道
     * @param lock exclusive lock / 独占锁
     * @param bootstrap protected bootstrap material / 受保护引导材料
     * @param anchor owned engine connection / 所属引擎连接
     * @param jdbcUrl private connection URL / 私有连接地址
     */
    private PlatformDatabase(Path root, FileChannel lockChannel, FileLock lock, DatabaseBootstrap bootstrap,
                             Connection anchor, String jdbcUrl) {
        this.root = root;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.bootstrap = bootstrap;
        this.anchor = anchor;
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Opens a matched existing database or an entirely new protected pair. / 打开匹配的已有数据库或全新的受保护配对。
     * @param directory absolute data root selected by the runtime mode / 运行模式选定的绝对数据根
     * @param protection current-user protector injected by the host / 宿主注入的当前用户保护器
     * @return the exclusive engine owner / 引擎独占所有者
     * @throws Exception if paths, pairing, locks, DDL or database identity fail / 路径、配对、锁、DDL 或数据库身份失败时
     */
    public static PlatformDatabase open(Path directory, DatabaseProtection protection) throws Exception {
        return open(directory, protection, java.util.Map.of());
    }

    /** Seeds only a newly created database. / 仅为新数据库写入初始偏好。
     * @param directory selected root / 数据根
     * @param protection original protector / 原保护器
     * @param initialPreferences non-secret initial preferences / 非秘密初始偏好
     * @return owned engine / 所属引擎
     * @throws Exception if bootstrap fails / 引导失败时 */
    public static PlatformDatabase open(Path directory, DatabaseProtection protection, java.util.Map<String, String> initialPreferences) throws Exception {
        return open(directory, protection, initialPreferences, true);
    }

    /** Opens APP storage while optionally deferring Mod recovery queries. / 打开 APP 存储，可延后 Mod 恢复查询。
     * @param directory selected data root / 数据根
     * @param protection user protection / 用户保护
     * @param initialPreferences new-database preferences / 新库偏好
     * @param modLoading whether Mod recovery is enabled / 是否启用 Mod 恢复
     * @return owned database / 所属数据库
     * @throws Exception if opening fails / 打开失败时 */
    public static PlatformDatabase open(Path directory, DatabaseProtection protection,
                                        java.util.Map<String, String> initialPreferences, boolean modLoading) throws Exception {
        try {
            return openOwned(directory, protection, java.util.Map.copyOf(initialPreferences), modLoading);
        } catch (gold.debug.windowstolinux.app.db.error.DesktopStorageException classified) {
            throw classified;
        } catch (FileAlreadyExistsException | NotDirectoryException unavailable) {
            throw gold.debug.windowstolinux.app.db.error.DesktopStorageException.create(
                gold.debug.windowstolinux.app.db.error.DesktopStorageFailureType.DATA_DIRECTORY_UNAVAILABLE,
                "The platform data directory is unavailable", unavailable);
        } catch (OverlappingFileLockException active) {
            throw gold.debug.windowstolinux.app.db.error.DesktopStorageException.create(
                gold.debug.windowstolinux.app.db.error.DesktopStorageFailureType.DATABASE_LOCKED,
                "Another application owns the original platform database", active);
        } catch (SQLException failure) {
            var type = switch (failure.getErrorCode()) {
                case org.h2.api.ErrorCode.FILE_CORRUPTED_1 ->
                    gold.debug.windowstolinux.app.db.error.DesktopStorageFailureType.DATABASE_CORRUPTED;
                case org.h2.api.ErrorCode.IO_EXCEPTION_1 -> corruptStore(failure)
                    ? gold.debug.windowstolinux.app.db.error.DesktopStorageFailureType.DATABASE_CORRUPTED
                    : gold.debug.windowstolinux.app.db.error.DesktopStorageFailureType.DATABASE_OPEN_FAILED;
                case org.h2.api.ErrorCode.DATABASE_ALREADY_OPEN_1 ->
                    gold.debug.windowstolinux.app.db.error.DesktopStorageFailureType.DATABASE_LOCKED;
                case org.h2.api.ErrorCode.FILE_VERSION_ERROR_1 ->
                    gold.debug.windowstolinux.app.db.error.DesktopStorageFailureType.SCHEMA_INCOMPATIBLE;
                default -> gold.debug.windowstolinux.app.db.error.DesktopStorageFailureType.DATABASE_OPEN_FAILED;
            };
            throw gold.debug.windowstolinux.app.db.error.DesktopStorageException.create(type,
                "The original platform database could not be opened safely", failure);
        }
    }

    /** Distinguishes proven damaged MVStore data from ordinary input/output failures. / 区分确证损坏的MVStore数据与普通输入输出失败。
     * @param failure actual H2 opening failure / 实际H2打开失败
     * @return whether the store proves corruption or truncated input / 存储是否证明损坏或截断输入 */
    private static boolean corruptStore(SQLException failure) {
        if (!(failure.getCause() instanceof org.h2.mvstore.MVStoreException store))
            return false;
        return store.getErrorCode() == org.h2.mvstore.DataUtils.ERROR_FILE_CORRUPT
            || store.getErrorCode() == org.h2.mvstore.DataUtils.ERROR_READING_FAILED
            && store.getCause() instanceof java.io.EOFException;
    }

    /** Opens the original owned engine and releases every acquired physical handle on failure. / 打开原所属引擎，失败时释放每个已取得的物理句柄。
     * @param directory selected data root / 已选数据根
     * @param protection original bootstrap protector / 原引导保护器
     * @return exclusive engine owner / 独占引擎所有者
     * @throws Exception if original acquisition or validation fails / 原取得或校验失败时
     * @param initialPreferences initial values for absent desktop keys / 缺失桌面字段的初始值
     * @param modLoading whether startup validates Mod records / 启动是否校验 Mod 记录
     */
    private static PlatformDatabase openOwned(Path directory, DatabaseProtection protection, java.util.Map<String, String> initialPreferences, boolean modLoading) throws Exception {
        Objects.requireNonNull(directory);
        Objects.requireNonNull(protection);
        if (!directory.isAbsolute())
            throw new java.io.IOException("Database root must be absolute");
        var root = directory.normalize();
        if (root.toString().contains(";") || root.toString().indexOf('\0') >= 0)
            throw new java.io.IOException("Unsupported database path");
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new java.io.IOException("Unsafe database root");
        var channel = FileChannel.open(root.resolve("windowstolinux.lock"), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock lock = null;
        DatabaseBootstrap material = null;
        Connection anchor = null;
        try {
            lock = channel.tryLock();
            if (lock == null)
                throw gold.debug.windowstolinux.app.db.error.DesktopStorageException.create(
                    gold.debug.windowstolinux.app.db.error.DesktopStorageFailureType.DATABASE_LOCKED,
                    "Another application owns the original platform database", null);
            material = DatabaseBootstrap.load(root, protection);
            String url = "jdbc:h2:" + root.resolve("windowstolinux")
                + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE;LOCK_TIMEOUT=5000;WRITE_DELAY=0"
                + (material.fresh() ? "" : ";IFEXISTS=TRUE");
            anchor = DriverManager.getConnection(url, "app_internal", material.password());
            var database = new PlatformDatabase(root, channel, lock, material, anchor,
                "jdbc:h2:" + root.resolve("windowstolinux") + ";DATABASE_TO_LOWER=TRUE;IFEXISTS=TRUE;LOCK_TIMEOUT=5000");
            gold.debug.windowstolinux.app.db.init.PlatformDatabaseInitializer.initialize(anchor, material.fresh(),
                material.generation(), database.bootId, modLoading, initialPreferences);
            return database;
        } catch (Exception failure) {
            if (anchor != null)
                try {
                    anchor.close();
                } catch (Exception closing) {
                    failure.addSuppressed(closing);
                }
            if (material != null)
                material.close();
            if (lock != null)
                try {
                    lock.release();
                } catch (Exception closing) {
                    failure.addSuppressed(closing);
                }
            try {
                channel.close();
            } catch (Exception closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    /**
     * Opens an owned internal platform connection with automatic occupancy tracking. / 打开自动追踪占用的内部平台连接。
     * @return independent connection that the caller must close / 调用方必须关闭的独立连接
     * @throws SQLException if the database is closed or the driver fails / 数据库已关闭或驱动失败时
     */
    public synchronized Connection openConnection() throws SQLException {
        if (closed)
            throw new SQLException("Database is closed");
        return openInternalConnection("app", "app_internal", bootstrap.password(), "platform");
    }

    /**
     * Opens a tracked connection for an internal prepared identity, never a caller-supplied JDBC URL. / 为内部已准备身份打开跟踪连接，不接收调用方 JDBC 地址。
     * @param schema exact prepared default schema / 精确的已准备默认数据结构
     * @param user internal prepared database user / 内部已准备数据库用户
     * @param password private in-memory driver password / 私有内存驱动口令
     * @param ownerTag internal resource scope identifier / 内部资源作用域标识
     * @return an independent thread-bound managed connection / 独立且绑定线程的受管连接
     * @throws SQLException if the engine is closed or opening fails / 引擎已关闭或打开失败时
     */
    public Connection openInternalConnection(String schema, String user, String password, String ownerTag)
        throws SQLException {
        return openInternalConnection(schema, user, password, ownerTag, () -> {
        });
    }

    /**
     * Opens a single managed identity view with a retryable host close hook. / 打开具有可重试宿主关闭回调的单一受管身份视图。
     * @param schema prepared default schema / 已准备的默认数据结构
     * @param user prepared private database identity / 已准备的私有数据库身份
     * @param password private in-memory driver credential / 私有内存驱动凭据
     * @param ownerTag physical connection scope / 物理连接作用域
     * @param afterClose idempotent host action after physical close / 物理关闭后的幂等宿主操作
     * @return thread-bound managed connection / 绑定线程的受管连接
     * @throws SQLException if opening or default schema assignment fails / 打开或分配默认数据结构失败时
     */
    public Connection openInternalConnection(String schema, String user, String password, String ownerTag,
                                             ManagedJdbcConnection.Release afterClose) throws SQLException {
        Objects.requireNonNull(schema);
        Objects.requireNonNull(user);
        Objects.requireNonNull(password);
        Objects.requireNonNull(ownerTag);
        Objects.requireNonNull(afterClose);
        Connection physical;
        synchronized (this) {
            if (closed)
                throw new SQLException("Database is closed");
            physical = DriverManager.getConnection(jdbcUrl, user, password);
            connections.put(physical, ownerTag);
        }
        var managed = ManagedJdbcConnection.wrapTracked(physical, () -> {
            released(physical);
            afterClose.run();
        });
        try {
            managed.setSchema(schema);
            return managed;
        } catch (SQLException failure) {
            try {
                managed.close();
            } catch (SQLException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    /**
     * Derives the private non-administrator owner password for an encoded schema identity. / 为编码后的数据身份派生私有非管理员所有者口令。
     * @param databaseUser exact encoded schema owner name / 精确编码的数据结构所有者名称
     * @return private deterministic owner password / 私有确定性所有者口令
     * @throws java.security.GeneralSecurityException if the required HMAC is unavailable / 所需 HMAC 不可用时
     */
    public String schemaPassword(String databaseUser) throws java.security.GeneralSecurityException {
        return bootstrap.derive("WTL-SCHEMA-OWNER-1\0" + Objects.requireNonNull(databaseUser));
    }

    /**
     * Counts connections belonging to one internal operation scope. / 统计属于某一内部操作作用域的连接。
     * @param ownerTag exact internal scope / 精确的内部作用域
     * @return connections not yet actually closed / 尚未实际关闭的连接数
     */
    public synchronized int openConnections(String ownerTag) {
        return (int) connections.values().stream().filter(ownerTag::equals).count();
    }

    /**
     * Removes occupancy only after the wrapper confirms physical close. / 包装器确认物理关闭后才移除占用。
     * @param physical actually closed connection / 已实际关闭的连接
     */
    private synchronized void released(Connection physical) {
        connections.remove(physical);
    }

    /** Returns the selected root without revealing driver credentials. / 返回选定根目录，不暴露驱动凭据。
     * @return normalized data root / 规范数据根 */
    public Path root() {
        return root;
    }

    /** Returns the current startup identity. / 返回当前启动身份。
     * @return current boot identity / 当前启动身份 */
    public UUID bootId() {
        return bootId;
    }

    /** Returns the protected database generation. / 返回受保护数据库代次。
     * @return stable database generation / 稳定数据库代次 */
    public UUID generation() {
        return bootstrap.generation();
    }

    /** Reports connection occupancy for shutdown diagnostics. / 报告用于关闭诊断的连接占用。
     * @return number of connections not actually closed / 尚未实际关闭的连接数 */
    public synchronized int openConnections() {
        return connections.size();
    }

    /**
     * Records successful host startup after capability assembly. / 平台能力装配后记录成功启动。
     * @throws SQLException if the marker cannot be persisted / 标记无法持久化时
     */
    public synchronized void started() throws SQLException {
        try (var s = anchor.createStatement()) {
            s.executeUpdate("UPDATE app_state SET shutdown_state='RUNNING' WHERE singleton_id=1");
        }
    }

    /**
     * Closes only after all owned connections are physically released. / 仅在所属连接全部实际释放后关闭。
     * @throws Exception if resources remain or normal engine shutdown fails / 仍有资源占用或引擎正常关闭失败时
     */
    @Override
    public synchronized void close() throws Exception {
        if (closed)
            return;
        if (!connections.isEmpty())
            throw new SQLException("Database still has " + connections.size() + " open connections");
        if (!anchor.isClosed()) {
            try (var s = anchor.createStatement()) {
                s.executeUpdate("UPDATE app_state SET shutdown_state='CLEAN',updated_at=" + System.currentTimeMillis()
                    + " WHERE singleton_id=1");
                s.execute("SHUTDOWN");
            }
            anchor.close();
        }
        if (!anchor.isClosed())
            throw new SQLException("Database engine did not close");
        lock.release();
        lockChannel.close();
        bootstrap.close();
        closed = true;
    }
}
