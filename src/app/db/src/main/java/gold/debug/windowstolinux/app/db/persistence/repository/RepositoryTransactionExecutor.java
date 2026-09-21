package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.failure.DesktopPersistenceException;
import gold.debug.windowstolinux.app.db.failure.DesktopPersistenceFailureType;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Shared transaction primitives used by focused repositories. / 由聚焦仓库使用的共享事务原语。
 */
final class RepositoryTransactionExecutor {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private RepositoryTransactionExecutor() {
    }

    /**
     * Executes repository transaction.
     * <p>执行仓库事务。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param work work / 工作
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    static void execute(Connection connection, SqlWork work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            work.execute();
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                rollbackFailure.addSuppressed(exception);
                throw DesktopPersistenceException.create(
                        DesktopPersistenceFailureType.ROLLBACK_FAILED,
                        "A database transaction failed and its rollback could not be verified",
                        rollbackFailure);
            }
            throw exception;
        }
    }

    /**
     * Inserts or updates server identity or selected server configuration.
     * <p>插入或更新服务器身份或所选服务器配置。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    static void upsertServer(Connection connection, ServerIdentity server) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO server (id, host, ssh_port, host_key_sha256, host_key_format) VALUES (?, ?, ?, ?, 'SSH_WIRE')
                ON CONFLICT(id) DO UPDATE SET id=excluded.id
                WHERE server.host=excluded.host AND server.ssh_port=excluded.ssh_port
                  AND server.host_key_sha256=excluded.host_key_sha256
                """)) {
            statement.setString(1, server.id());
            statement.setString(2, server.host());
            statement.setInt(3, server.sshPort());
            statement.setString(4, server.hostKeySha256());
            if (statement.executeUpdate() != 1) throw new SQLException("server trust identity changed concurrently");
        }
    }

    /**
     * SQL work executed inside the repository transaction boundary.
     * <p>在仓库事务边界内执行的 SQL 工作。
     */
    @FunctionalInterface
    interface SqlWork {
        /**
         * Executes sql work.
         * <p>执行SQL工作。
         *
         * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
         */
        void execute() throws SQLException;
    }
}
