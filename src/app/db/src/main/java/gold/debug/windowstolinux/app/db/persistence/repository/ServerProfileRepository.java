package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
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

    /** Lists saved connection profiles without loading any secrets. / 列出已保存连接配置，不加载任何秘密。 */
    public java.util.List<StoredServerProfile> listServerProfiles() throws SQLException {
        java.util.List<StoredServerProfile> profiles = new java.util.ArrayList<>();
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, host, ssh_port, username, credential_key, credential_mode FROM server_profile ORDER BY id");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) profiles.add(new StoredServerProfile(result.getString("id"), result.getString("host"),
                    result.getInt("ssh_port"), result.getString("username"), result.getString("credential_key"),
                    result.getString("credential_mode")));
        }
        return java.util.List.copyOf(profiles);
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

    /** Checks the format of the exact trust record rather than guessing from its text. / 核对精确信任记录的格式，不通过文本猜测。 */
    public boolean hasLegacyHostKey(ServerIdentity expected) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT host_key_format FROM server WHERE id=? AND host=? AND ssh_port=? AND host_key_sha256=?")) {
            statement.setString(1, expected.id()); statement.setString(2, expected.host());
            statement.setInt(3, expected.sshPort()); statement.setString(4, expected.hostKeySha256());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && "LEGACY_X509".equals(result.getString(1));
            }
        }
    }

    /** Atomically records authenticated trust or migrates an unchanged historical key. / 原子记录认证后的信任，或迁移未发生变化的历史公钥。 */
    public void saveAuthenticatedServer(ServerIdentity observed, Optional<ServerIdentity> expected) throws SQLException {
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                if (expected.isPresent() && !expected.orElseThrow().hostKeySha256().equals(observed.hostKeySha256())) {
                    ServerIdentity prior = expected.orElseThrow();
                    if (!prior.id().equals(observed.id()) || !prior.host().equals(observed.host()) || prior.sshPort() != observed.sshPort()) {
                        throw new SQLException("authenticated server endpoint differs from trusted endpoint");
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE server SET host_key_sha256=?, host_key_format='SSH_WIRE'
                            WHERE id=? AND host=? AND ssh_port=? AND host_key_sha256=? AND host_key_format='LEGACY_X509'
                            """)) {
                        statement.setString(1, observed.hostKeySha256()); statement.setString(2, prior.id());
                        statement.setString(3, prior.host()); statement.setInt(4, prior.sshPort());
                        statement.setString(5, prior.hostKeySha256());
                        if (statement.executeUpdate() != 1) throw new SQLException("historical host key changed before migration");
                    }
                }
                RepositoryTransactionExecutor.upsertServer(connection, observed);
            });
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
