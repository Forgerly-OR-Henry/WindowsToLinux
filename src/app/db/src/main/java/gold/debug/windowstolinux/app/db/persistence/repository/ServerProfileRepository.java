package gold.debug.windowstolinux.app.db.persistence.repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.entity.StoredServerObservation;
import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

/** Stores connection metadata and endpoint-bound authenticated trust. / 保存连接元数据和绑定端点的认证信任。 */
public final class ServerProfileRepository {
    /** Scoped database connection factory. / 限定数据库连接工厂。
     */
    private final DesktopConnectionFactory connections;

    /** Binds the database. / 绑定数据库。
     * @param connections connection factory / 连接工厂
     */
    public ServerProfileRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections);
    }

    /** Lists metadata without credential values. / 列出不含凭据值的元数据。
     * @return saved profiles / 已保存配置
     * @throws SQLException on database failure / 数据库失败
     */
    public List<StoredServerProfile> listServerProfiles() throws SQLException {
        List<StoredServerProfile> profiles = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("SELECT * FROM server_profile ORDER BY server_profile_id");
                var rows = s.executeQuery()) {
            while (rows.next())
                profiles.add(read(rows));
        }
        return List.copyOf(profiles);
    }

    /** Saves an authenticated trust identity against an existing profile. / 为已有配置保存认证信任身份。
     * @param server authenticated server / 已认证服务器
     * @throws SQLException on a missing profile or changed identity / 配置缺失或身份变化
     */
    public void saveServer(ServerIdentity server) throws SQLException {
        try (var c = connections.open()) {
            RepositoryTransactionExecutor.upsertServer(c, server);
        }
    }

    /** Returns the trusted endpoint snapshot, independent of editable connection fields. / 返回独立于可编辑连接字段的信任端点快照。
     * @param serverId profile identifier / 配置标识
     * @return stored trust / 已存信任
     * @throws SQLException on database failure / 数据库失败
     */
    public Optional<ServerIdentity> findServer(String serverId) throws SQLException {
        try (var c = connections.open();
                var s = c.prepareStatement(
                        "SELECT * FROM server_profile WHERE server_profile_id=? AND host_key_sha256 IS NOT NULL")) {
            s.setString(1, serverId);
            try (var rows = s.executeQuery()) {
                return rows.next()
                        ? Optional.of(new ServerIdentity(serverId, rows.getString("trusted_host"),
                                rows.getInt("trusted_ssh_port"), rows.getString("host_key_sha256")))
                        : Optional.empty();
            }
        }
    }

    /** Commits only an unchanged identity after successful authentication. / 认证成功后仅提交未变化的身份。
     * @param observed observed identity / 已观测身份
     * @param expected previous identity / 原身份
     * @throws SQLException when identity changed / 身份发生变化
     */
    public void saveAuthenticatedServer(ServerIdentity observed, Optional<ServerIdentity> expected)
            throws SQLException {
        if (expected.isPresent() && !expected.orElseThrow().equals(observed))
            throw new SQLException("authenticated server identity differs from trusted identity");
        saveServer(observed);
    }

    /** Saves connection fields without overwriting unrelated trust or observations. / 保存连接字段而不覆盖无关信任或观测。
     * @param profile connection settings / 连接设置
     * @throws SQLException on a forbidden rebind or database failure / 禁止换绑或数据库失败
     */
    public void saveServerProfile(StoredServerProfile profile) throws SQLException {
        connections.transaction(() -> {
            var prior = findServerProfile(profile.id());
            boolean changed = prior.isPresent()
                    && (!prior.get().host().equals(profile.host()) || prior.get().sshPort() != profile.sshPort());
            try (var c = connections.open()) {
                if (changed) {
                    try (var s = c
                            .prepareStatement("SELECT 1 FROM deployment_unit WHERE server_profile_id=? LIMIT 1")) {
                        s.setString(1, profile.id());
                        try (var rows = s.executeQuery()) {
                            if (rows.next())
                                throw new SQLException(
                                        "a server with managed deployments cannot be rebound; create a new profile");
                        }
                    }
                }
                try (var s = c.prepareStatement(
                        """
                                INSERT INTO server_profile(server_profile_id,credential_id,host,ssh_port,username,display_name) VALUES(?,?,?,?,?,?)
                                ON CONFLICT(server_profile_id) DO UPDATE SET credential_id=excluded.credential_id,host=excluded.host,
                                ssh_port=excluded.ssh_port,username=excluded.username,display_name=excluded.display_name
                                """)) {
                    s.setString(1, profile.id());
                    s.setString(2, profile.credentialKey());
                    s.setString(3, profile.host());
                    s.setInt(4, profile.sshPort());
                    s.setString(5, profile.username());
                    s.setString(6, profile.displayName());
                    s.executeUpdate();
                }
                if (changed) {
                    try (var s = c.prepareStatement(
                            "UPDATE server_profile SET host_key_sha256=NULL,host_key_format=NULL,trusted_host=NULL,trusted_ssh_port=NULL WHERE server_profile_id=?")) {
                        s.setString(1, profile.id());
                        s.executeUpdate();
                    }
                }
                if (changed || prior.isPresent() && !prior.get().username().equals(profile.username())) {
                    try (var s = c.prepareStatement(
                            "UPDATE server_profile SET last_checked_at=NULL,last_connection_succeeded=NULL,last_operating_system=NULL WHERE server_profile_id=?")) {
                        s.setString(1, profile.id());
                        s.executeUpdate();
                    }
                }
            }
        });
    }

    /** Finds metadata without accessing credentials. / 查询配置而不访问凭据。
     * @param serverId profile identifier / 配置标识
     * @return stored profile / 已存配置
     * @throws SQLException on database failure / 数据库失败
     */
    public Optional<StoredServerProfile> findServerProfile(String serverId) throws SQLException {
        try (var c = connections.open();
                var s = c.prepareStatement("SELECT * FROM server_profile WHERE server_profile_id=?")) {
            s.setString(1, serverId);
            try (var rows = s.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    /** Reads the last non-authoritative connection observation. / 读取最后一次非权威连接观测。
     * @param serverId profile identifier / 配置标识
     * @return observation / 观测
     * @throws SQLException on database failure / 数据库失败
     */
    public StoredServerObservation observation(String serverId) throws SQLException {
        try (var c = connections.open();
                var s = c.prepareStatement(
                        "SELECT last_checked_at,last_connection_succeeded,last_operating_system FROM server_profile WHERE server_profile_id=?")) {
            s.setString(1, serverId);
            try (var rows = s.executeQuery()) {
                if (!rows.next())
                    throw new SQLException("server profile no longer exists");
                Optional<Instant> time = rows.getObject(1) == null
                        ? Optional.empty()
                        : Optional.of(Instant.ofEpochMilli(rows.getLong(1)));
                return new StoredServerObservation(time, rows.getBoolean(2), Objects.toString(rows.getString(3), ""));
            }
        }
    }

    /** Records an observation only if the queried connection is still current. / 仅为仍然有效的连接记录观测。
     * @param profile queried connection / 被查询连接
     * @param connected whether connection succeeded / 是否连接成功
     * @param system observed system / 已观测系统
     * @throws SQLException on database failure / 数据库失败
     */
    public void recordObservation(StoredServerProfile profile, boolean connected, String system) throws SQLException {
        try (var c = connections.open();
                var s = c.prepareStatement(
                        "UPDATE server_profile SET last_checked_at=?,last_connection_succeeded=?,last_operating_system=? WHERE server_profile_id=? AND host=? AND ssh_port=? AND username=?")) {
            s.setLong(1, Instant.now().toEpochMilli());
            s.setBoolean(2, connected);
            s.setString(3, system);
            s.setString(4, profile.id());
            s.setString(5, profile.host());
            s.setInt(6, profile.sshPort());
            s.setString(7, profile.username());
            s.executeUpdate();
        }
    }

    /** Reads and validates one persisted record by its identifier. / 按标识读取并验证一条持久化记录。
     * @param rows rows supplied to this boundary / 传入当前边界的rows值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    private static StoredServerProfile read(ResultSet rows) throws SQLException {
        return new StoredServerProfile(rows.getString("server_profile_id"), rows.getString("host"),
                rows.getInt("ssh_port"), rows.getString("username"), rows.getString("credential_id"),
                rows.getString("display_name"));
    }
}
