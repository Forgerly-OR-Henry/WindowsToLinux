package gold.debug.windowstolinux.app.db.repository;

import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Shared transaction primitives used by focused repositories. / 由聚焦仓库使用的共享事务原语。 */
final class RepositoryTransactions {
    private RepositoryTransactions() {
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
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        }
    }

    static void upsertServer(Connection connection, ServerIdentity server) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO server (id, host, ssh_port, host_key_sha256) VALUES (?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET host=excluded.host, ssh_port=excluded.ssh_port,
                    host_key_sha256=excluded.host_key_sha256
                """)) {
            statement.setString(1, server.id());
            statement.setString(2, server.host());
            statement.setInt(3, server.sshPort());
            statement.setString(4, server.hostKeySha256());
            statement.executeUpdate();
        }
    }

    @FunctionalInterface
    interface SqlWork {
        void execute() throws SQLException;
    }
}
