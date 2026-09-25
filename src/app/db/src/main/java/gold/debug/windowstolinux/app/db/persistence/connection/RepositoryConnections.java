package gold.debug.windowstolinux.app.db.persistence.connection;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Shares one owned connection across nested platform repository transactions. / 在嵌套平台仓库事务间共享一个所属连接。 */
public final class RepositoryConnections {
    /** Creates a fresh connection when no transaction owns one. / 没有事务持有连接时创建新连接。 */
    private final Source source;

    /** Connection owned by the current thread's outer transaction. / 当前线程最外层事务持有的连接。 */
    private final ThreadLocal<Connection> current = new ThreadLocal<>();

    /**
     * Binds an internal connection source; callers still close every returned view. / 绑定内部连接来源；调用方仍须关闭每个返回视图。
     * @param source source of owned connections / 所属连接的来源
     */
    public RepositoryConnections(Source source) {
        this.source = Objects.requireNonNull(source);
    }

    /**
     * Opens a connection or a non-owning view of the active transaction. / 打开连接或活动事务的非所有者视图。
     * @return connection view that the caller closes / 调用方需要关闭的连接视图
     * @throws SQLException if opening fails / 打开失败时
     */
    public Connection open() throws SQLException {
        var connection = current.get();
        if (connection == null)
            return source.open();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close"))
                        return null;
                    if (java.util.Set.of("commit", "rollback", "setAutoCommit", "abort").contains(method.getName()))
                        throw new SQLException("Nested repository cannot finish the owning transaction");
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /**
     * Commits an outer transaction only after all nested writes return successfully. / 仅在所有嵌套写入成功返回后提交外层事务。
     * @param work related writes on this thread / 当前线程上的关联写入
     * @throws SQLException if work, commit, rollback or close fails; commit failure remains uncertain / 写入、提交、回滚或关闭失败时；提交失败仍属不确定
     */
    public void transaction(Work work) throws SQLException {
        Objects.requireNonNull(work);
        if (current.get() != null) {
            work.run();
            return;
        }
        try (var connection = source.open()) {
            connection.setAutoCommit(false);
            current.set(connection);
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
                current.remove();
            }
        }
    }

    /** Source of internal owned JDBC connections. / 内部所属 JDBC 连接来源。 */
    @FunctionalInterface
    public interface Source {
        /**
         * Creates a fresh tracked connection. / 创建新的受跟踪连接。
         * @return an owned connection / 所属连接
         * @throws SQLException if creation fails / 创建失败时
         */
        Connection open() throws SQLException;
    }

    /** Related repository writes that share a transaction. / 共享事务的关联仓库写入。 */
    @FunctionalInterface
    public interface Work {
        /**
         * Performs repository work without owning the outer commit. / 执行仓库工作，不拥有外层提交权。
         * @throws SQLException if a repository operation fails / 仓库操作失败时
         */
        void run() throws SQLException;
    }
}
