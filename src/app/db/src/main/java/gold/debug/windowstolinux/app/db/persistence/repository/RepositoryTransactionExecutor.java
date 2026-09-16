package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.failure.DesktopPersistenceException;
import gold.debug.windowstolinux.app.db.failure.DesktopPersistenceFailureType;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Shared transaction primitives used by focused repositories. / 由聚焦仓库使用的共享事务原语。 */
final class RepositoryTransactionExecutor {
    private RepositoryTransactionExecutor() {
    }

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

    @FunctionalInterface
    interface SqlWork {
        /** Performs the {@code execute} operation. / 执行 {@code execute} 操作。 */
        void execute() throws SQLException;
    }
}
