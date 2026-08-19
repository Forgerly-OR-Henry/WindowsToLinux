package gold.debug.windowstolinux.app.db.repository;

import gold.debug.windowstolinux.app.db.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** Stores server trust identities and credential-free connection profiles. / 保存服务器信任身份与不含凭据的连接资料。 */
public final class ServerProfileRepository {
    private final DesktopConnectionFactory connections;

    /** Creates the repository. / 创建仓库。 */
    public ServerProfileRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** Saves a trusted server identity. / 保存可信服务器身份。 */
    public void saveServer(ServerIdentity server) throws SQLException {
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.upsertServer(connection, server);
        }
    }

    /** Finds a trusted server identity. / 查找可信服务器身份。 */
    public Optional<ServerIdentity> findServer(String serverId) throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, host, ssh_port, host_key_sha256 FROM server WHERE id=?")) {
            statement.setString(1, serverId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new ServerIdentity(result.getString("id"), result.getString("host"),
                        result.getInt("ssh_port"), result.getString("host_key_sha256"))) : Optional.empty();
            }
        }
    }

    /** Saves a credential-free server profile. / 保存不含凭据的服务器资料。 */
    public void saveServerProfile(StoredServerProfile profile) throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO server_profile (id, host, ssh_port, username, credential_key, credential_mode)
                     VALUES (?, ?, ?, ?, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET host=excluded.host, ssh_port=excluded.ssh_port,
                         username=excluded.username, credential_key=excluded.credential_key,
                         credential_mode=excluded.credential_mode
                     """)) {
            statement.setString(1, profile.id());
            statement.setString(2, profile.host());
            statement.setInt(3, profile.sshPort());
            statement.setString(4, profile.username());
            statement.setString(5, profile.credentialKey());
            statement.setString(6, profile.credentialMode());
            statement.executeUpdate();
        }
    }

    /** Finds a credential-free server profile. / 查找不含凭据的服务器资料。 */
    public Optional<StoredServerProfile> findServerProfile(String serverId) throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, host, ssh_port, username, credential_key, credential_mode FROM server_profile WHERE id=?
                     """)) {
            statement.setString(1, serverId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new StoredServerProfile(result.getString("id"), result.getString("host"),
                        result.getInt("ssh_port"), result.getString("username"), result.getString("credential_key"),
                        result.getString("credential_mode"))) : Optional.empty();
            }
        }
    }
}
