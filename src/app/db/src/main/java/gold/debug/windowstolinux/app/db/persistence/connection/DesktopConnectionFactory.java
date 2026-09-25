package gold.debug.windowstolinux.app.db.persistence.connection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Opens desktop SQLite connections with foreign-key enforcement and a bounded busy timeout.
 * <p>创建启用外键约束并设置有界忙等待时间的桌面 SQLite 连接。
 */
public final class DesktopConnectionFactory {
    /**
     * The SQLite JDBC connection URL.
     * <p>SQLite JDBC 连接 URL。
     */
    private final String jdbcUrl;

    /** Connection shared only by the current atomic repository operation. / 仅当前原子仓库操作共享的连接。 */
    private final ThreadLocal<Connection> transaction = new ThreadLocal<>();

    /**
     * Validates and binds the inputs required by desktop connection factory.
     * <p>校验并绑定Desktop连接工厂所需输入。
     *
     * @param jdbcUrl the SQLite JDBC connection URL / SQLite JDBC 连接 URL
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopConnectionFactory(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    }

    /**
     * Opens connection.
     * <p>打开连接。
     *
     * @return the operation result / 操作结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Connection open() throws SQLException {
        Connection current = transaction.get();
        if (current != null) {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("close"))
                            return null;
                        if (java.util.Set.of("commit", "rollback", "setAutoCommit").contains(method.getName()))
                            throw new SQLException("nested repository cannot complete the owning transaction");
                        try {
                            return method.invoke(current, arguments);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA secure_delete = ON");
            statement.execute("PRAGMA journal_mode = DELETE");
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    /** Runs related repository writes in one transaction on this thread. / 在当前线程的一个事务中执行相关仓库写入。
     * @param work related writes / 相关写入
     * @throws SQLException if writing or committing fails / 写入或提交失败
     */
    public void transaction(SqlWork work) throws SQLException {
        if (transaction.get() != null) {
            work.run();
            return;
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            transaction.set(connection);
            try {
                work.run();
                connection.commit();
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            } finally {
                transaction.remove();
            }
        }
    }

    /** Related database writes. / 相关数据库写入。 */
    @FunctionalInterface
    public interface SqlWork {
        /** Performs the writes. / 执行写入。
         * @throws SQLException when writing fails / 写入失败
         */
        void run() throws SQLException;
    }
}
