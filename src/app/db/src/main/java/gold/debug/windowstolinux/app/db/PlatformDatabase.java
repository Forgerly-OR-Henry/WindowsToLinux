package gold.debug.windowstolinux.app.db;

import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.bootstrap.DatabaseBootstrap;
import gold.debug.windowstolinux.app.db.bootstrap.DatabaseProtection;
import org.h2.tools.RunScript;

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
    private final Set<Connection> connections = Collections.newSetFromMap(new IdentityHashMap<>());

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
        var channel = FileChannel.open(root.resolve("platform.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock lock = null;
        DatabaseBootstrap material = null;
        Connection anchor = null;
        try {
            lock = channel.tryLock();
            if (lock == null)
                throw new java.io.IOException("Another application owns this database");
            material = DatabaseBootstrap.load(root, protection);
            String url = "jdbc:h2:" + root.resolve("platform")
                    + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE;LOCK_TIMEOUT=5000;WRITE_DELAY=0"
                    + (material.fresh() ? "" : ";IFEXISTS=TRUE");
            anchor = DriverManager.getConnection(url, "app_internal", material.password());
            var database = new PlatformDatabase(root, channel, lock, material, anchor,
                    "jdbc:h2:" + root.resolve("platform") + ";DATABASE_TO_LOWER=TRUE;IFEXISTS=TRUE;LOCK_TIMEOUT=5000");
            database.initialize();
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
     * Initializes once and validates existing identity before updating the boot marker. / 仅首次初始化，并在更新启动标记前校验已有身份。
     * @throws Exception if schema or protected identity does not match / 数据结构或受保护身份不匹配时
     */
    private void initialize() throws Exception {
        byte[] ddl;
        try (var stream = Objects.requireNonNull(
                getClass().getResourceAsStream("/gold/debug/windowstolinux/app/db/schema/platform-v1.sql"))) {
            ddl = stream.readAllBytes();
        }
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(ddl));
        if (bootstrap.fresh()) {
            try (var s = anchor.createStatement()) {
                s.execute("CREATE SCHEMA app AUTHORIZATION app_internal");
            }
            anchor.setSchema("app");
            RunScript.execute(anchor,
                    new java.io.StringReader(new String(ddl, java.nio.charset.StandardCharsets.UTF_8)));
            try (var p = anchor.prepareStatement(
                    "INSERT INTO app_state(singleton_id,database_generation,boot_id,shutdown_state,intent_revision,updated_at) VALUES(1,?,?,'STARTING',0,?)")) {
                p.setObject(1, bootstrap.generation());
                p.setObject(2, bootId);
                p.setLong(3, System.currentTimeMillis());
                p.executeUpdate();
            }
            try (var p = anchor.prepareStatement(
                    "INSERT INTO app_schema_history(schema_version,script_sha256,state,started_at,ended_at) VALUES(1,?,'READY',?,?)")) {
                p.setString(1, digest);
                p.setLong(2, System.currentTimeMillis());
                p.setLong(3, System.currentTimeMillis());
                p.executeUpdate();
            }
        } else {
            anchor.setSchema("app");
            try (var s = anchor.createStatement();
                    var r = s.executeQuery("SELECT database_generation FROM app_state WHERE singleton_id=1")) {
                if (!r.next() || !bootstrap.generation().equals(r.getObject(1, UUID.class)))
                    throw new SQLException("Protected database identity mismatch");
            }
            try (var s = anchor.createStatement();
                    var r = s.executeQuery(
                            "SELECT schema_version,script_sha256,state FROM app_schema_history ORDER BY schema_version DESC")) {
                if (!r.next() || r.getInt(1) != 1 || !r.getString(2).equals(digest) || !r.getString(3).equals("READY"))
                    throw new SQLException("Unsupported or incomplete platform schema");
            }
            anchor.setAutoCommit(false);
            try {
                try (var p = anchor.prepareStatement(
                        "UPDATE app_state SET boot_id=?,shutdown_state='STARTING',updated_at=? WHERE singleton_id=1")) {
                    p.setObject(1, bootId);
                    p.setLong(2, System.currentTimeMillis());
                    p.executeUpdate();
                }
                try (var s = anchor.createStatement()) {
                    s.executeUpdate(
                            "UPDATE platform_task SET state='UNKNOWN',outcome=NULL,ended_at=NULL WHERE state<>'ENDED'");
                }
                anchor.commit();
            } catch (SQLException failure) {
                try {
                    anchor.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            } finally {
                anchor.setAutoCommit(true);
            }
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
        var physical = DriverManager.getConnection(jdbcUrl, "app_internal", bootstrap.password());
        connections.add(physical);
        var managed = gold.debug.windowstolinux.app.db.persistence.connection.ManagedJdbcConnection.wrap(physical,
                () -> released(physical));
        try {
            managed.setSchema("app");
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
