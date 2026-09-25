package gold.debug.windowstolinux.app.db.persistence.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import gold.debug.windowstolinux.app.db.failure.DesktopPersistenceException;
import gold.debug.windowstolinux.app.db.failure.DesktopPersistenceFailureType;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

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
        if (!connection.getAutoCommit()) {
            work.execute();
            return;
        }
        connection.setAutoCommit(false);
        try {
            work.execute();
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                rollbackFailure.addSuppressed(exception);
                throw DesktopPersistenceException.create(DesktopPersistenceFailureType.ROLLBACK_FAILED,
                        "A database transaction failed and its rollback could not be verified", rollbackFailure);
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
                UPDATE server_profile SET trusted_host=?,trusted_ssh_port=?,host_key_sha256=?,host_key_format='SSH_WIRE'
                WHERE server_profile_id=? AND host=? AND ssh_port=? AND
                (host_key_sha256 IS NULL OR (trusted_host=? AND trusted_ssh_port=? AND host_key_sha256=?))
                """)) {
            statement.setString(1, server.host());
            statement.setInt(2, server.sshPort());
            statement.setString(3, server.hostKeySha256());
            statement.setString(4, server.id());
            statement.setString(5, server.host());
            statement.setInt(6, server.sshPort());
            statement.setString(7, server.host());
            statement.setInt(8, server.sshPort());
            statement.setString(9, server.hostKeySha256());
            if (statement.executeUpdate() != 1)
                throw new SQLException("server trust identity changed concurrently");
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
