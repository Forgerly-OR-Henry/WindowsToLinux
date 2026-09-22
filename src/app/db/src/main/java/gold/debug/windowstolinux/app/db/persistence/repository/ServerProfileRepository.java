package gold.debug.windowstolinux.app.db.persistence.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.app.db.entity.StoredServerObservation;
import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

/**
 * Stores server trust identities and credential-free connection profiles. / 保存服务器信任身份与不含凭据的连接资料。
 */
public final class ServerProfileRepository {
    /**
     * Factory for scoped database connections.
     * <p>限定作用域数据库连接的工厂。
     */
    private final DesktopConnectionFactory connections;

    /**
     * Creates the repository. / 创建仓库。
     *
     * @param connections factory for scoped database connections / 限定作用域数据库连接的工厂
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ServerProfileRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Lists saved connection profiles without loading any secrets. / 列出已保存连接配置，不加载任何秘密。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public java.util.List<StoredServerProfile> listServerProfiles() throws SQLException {
        java.util.List<StoredServerProfile> profiles = new java.util.ArrayList<>();
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, host, ssh_port, username, credential_key, credential_mode, display_name FROM server_profile ORDER BY id");
                ResultSet result = statement.executeQuery()) {
            while (result.next())
                profiles.add(new StoredServerProfile(result.getString("id"), result.getString("host"),
                        result.getInt("ssh_port"), result.getString("username"), result.getString("credential_key"),
                        result.getString("credential_mode"), result.getString("display_name")));
        }
        return java.util.List.copyOf(profiles);
    }

    /**
     * Saves a trusted server identity. / 保存可信服务器身份。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void saveServer(ServerIdentity server) throws SQLException {
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.upsertServer(connection, server);
        }
    }

    /**
     * Finds a trusted server identity. / 查找可信服务器身份。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<ServerIdentity> findServer(String serverId) throws SQLException {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection
                        .prepareStatement("SELECT id, host, ssh_port, host_key_sha256 FROM server WHERE id=?")) {
            statement.setString(1, serverId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(new ServerIdentity(result.getString("id"), result.getString("host"),
                                result.getInt("ssh_port"), result.getString("host_key_sha256")))
                        : Optional.empty();
            }
        }
    }

    /**
     * Checks the format of the exact trust record rather than guessing from its text. / 核对精确信任记录的格式，不通过文本猜测。
     *
     * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
     * @return true when checks the format of the exact trust record rather than guessing from its text, false otherwise / 核对精确信任记录的格式，不通过文本猜测时为 true，否则为 false
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public boolean hasLegacyHostKey(ServerIdentity expected) throws SQLException {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT host_key_format FROM server WHERE id=? AND host=? AND ssh_port=? AND host_key_sha256=?")) {
            statement.setString(1, expected.id());
            statement.setString(2, expected.host());
            statement.setInt(3, expected.sshPort());
            statement.setString(4, expected.hostKeySha256());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && "LEGACY_X509".equals(result.getString(1));
            }
        }
    }

    /**
     * Atomically records authenticated trust or migrates an unchanged historical key. / 原子记录认证后的信任，或迁移未发生变化的历史公钥。
     *
     * @param observed observed / 已观测
     * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void saveAuthenticatedServer(ServerIdentity observed, Optional<ServerIdentity> expected)
            throws SQLException {
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                if (expected.isPresent() && !expected.orElseThrow().hostKeySha256().equals(observed.hostKeySha256())) {
                    ServerIdentity prior = expected.orElseThrow();
                    if (!prior.id().equals(observed.id()) || !prior.host().equals(observed.host())
                            || prior.sshPort() != observed.sshPort()) {
                        throw new SQLException("authenticated server endpoint differs from trusted endpoint");
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE server SET host_key_sha256=?, host_key_format='SSH_WIRE'
                            WHERE id=? AND host=? AND ssh_port=? AND host_key_sha256=? AND host_key_format='LEGACY_X509'
                            """)) {
                        statement.setString(1, observed.hostKeySha256());
                        statement.setString(2, prior.id());
                        statement.setString(3, prior.host());
                        statement.setInt(4, prior.sshPort());
                        statement.setString(5, prior.hostKeySha256());
                        if (statement.executeUpdate() != 1)
                            throw new SQLException("historical host key changed before migration");
                    }
                }
                RepositoryTransactionExecutor.upsertServer(connection, observed);
            });
        }
    }

    /**
     * Saves a credential-free server profile. / 保存不含凭据的服务器资料。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void saveServerProfile(StoredServerProfile profile) throws SQLException {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(
                        """
                                INSERT INTO server_profile (id, host, ssh_port, username, credential_key, credential_mode, display_name)
                                VALUES (?, ?, ?, ?, ?, ?, ?)
                                ON CONFLICT(id) DO UPDATE SET host=excluded.host, ssh_port=excluded.ssh_port,
                                    username=excluded.username, credential_key=excluded.credential_key,
                                    credential_mode=excluded.credential_mode, display_name=excluded.display_name,
                                    last_checked=CASE WHEN server_profile.host=excluded.host AND server_profile.ssh_port=excluded.ssh_port
                                      AND server_profile.username=excluded.username THEN server_profile.last_checked ELSE NULL END,
                                    operating_system=CASE WHEN server_profile.host=excluded.host AND server_profile.ssh_port=excluded.ssh_port
                                      AND server_profile.username=excluded.username THEN server_profile.operating_system ELSE '' END
                                """)) {
            statement.setString(1, profile.id());
            statement.setString(2, profile.host());
            statement.setInt(3, profile.sshPort());
            statement.setString(4, profile.username());
            statement.setString(5, profile.credentialKey());
            statement.setString(6, profile.credentialMode());
            statement.setString(7, profile.displayName());
            statement.executeUpdate();
        }
    }

    /**
     * Finds a credential-free server profile. / 查找不含凭据的服务器资料。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<StoredServerProfile> findServerProfile(String serverId) throws SQLException {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(
                        """
                                SELECT id, host, ssh_port, username, credential_key, credential_mode, display_name FROM server_profile WHERE id=?
                                """)) {
            statement.setString(1, serverId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(new StoredServerProfile(result.getString("id"), result.getString("host"),
                                result.getInt("ssh_port"), result.getString("username"),
                                result.getString("credential_key"), result.getString("credential_mode"),
                                result.getString("display_name")))
                        : Optional.empty();
            }
        }
    }

    /**
     * Reads the dated result of a connection check. / 读取带时间的连接检查结果。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @return the dated result of a connection check / 带时间的连接检查结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public StoredServerObservation observation(String serverId) throws SQLException {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT last_checked, connected, operating_system FROM server_profile WHERE id=?")) {
            statement.setString(1, serverId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next())
                    throw new SQLException("server profile no longer exists");
                return new StoredServerObservation(
                        Optional.ofNullable(result.getString(1)).map(java.time.Instant::parse), result.getBoolean(2),
                        result.getString(3));
            }
        }
    }

    /**
     * Ignores results for endpoints edited during a check. / 忽略检查期间被修改的端点结果。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param connected connected / 已连接
     * @param system system / 系统
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void recordObservation(StoredServerProfile profile, boolean connected, String system) throws SQLException {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE server_profile SET last_checked=?, connected=?, operating_system=? WHERE id=? AND host=? AND ssh_port=? AND username=?")) {
            statement.setString(1, java.time.Instant.now().toString());
            statement.setBoolean(2, connected);
            statement.setString(3, system);
            statement.setString(4, profile.id());
            statement.setString(5, profile.host());
            statement.setInt(6, profile.sshPort());
            statement.setString(7, profile.username());
            statement.executeUpdate();
        }
    }

}
