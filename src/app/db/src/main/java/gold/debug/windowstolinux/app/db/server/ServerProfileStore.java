package gold.debug.windowstolinux.app.db.server;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.server.StoredServerObservation;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.connect.StoreConnections;
import gold.debug.windowstolinux.app.db.server.StoredServerIdentity;

/** Stores connection metadata and endpoint-bound authenticated trust. / 保存连接元数据和绑定端点的认证信任。 */
public final class ServerProfileStore {
    /** Scoped database connection factory. / 限定数据库连接工厂。
     */
    private final StoreConnections connections;

    /** Query protecting referenced server identities from rebinding. / 保护被引用服务器身份免于换绑的查询。 */
    private final String referenceQuery;

    /** Binds owned platform connections and repository transactions. / 绑定所属平台连接和仓库事务。
     * @param connections platform transaction scope / 平台事务作用域
     */
    public ServerProfileStore(StoreConnections connections) {
        this.connections = java.util.Objects.requireNonNull(connections);
        this.referenceQuery = "SELECT 1 FROM mod_server_reference WHERE server_profile_id=? LIMIT 1";
    }

    /** Lists metadata without credential values. / 列出不含凭据值的元数据。
     * @return saved profiles / 已保存配置
     * @throws SQLException on database failure / 数据库失败
     */
    public List<StoredServerProfile> listServerProfiles() throws SQLException {
        List<StoredServerProfile> profiles = new ArrayList<>();
        try (var c = connections.open();
             var s = c.prepareStatement("SELECT * FROM server_profile ORDER BY display_order");
             var rows = s.executeQuery()) {
            while (rows.next())
                profiles.add(read(rows));
        }
        return List.copyOf(profiles);
    }

    /** Saves a complete permutation only while the caller's ordered snapshot is still current. / 仅在调用方完整顺序快照仍有效时保存全量排列。
     * @param expectedIds original complete order / 原完整顺序
     * @param requestedIds requested complete order / 请求的完整顺序
     * @throws SQLException if either snapshot is invalid or stale / 任一快照无效或过期时
     */
    public void reorderServerProfiles(List<String> expectedIds, List<String> requestedIds) throws SQLException {
        if (expectedIds == null || requestedIds == null || expectedIds.stream().anyMatch(Objects::isNull)
            || requestedIds.stream().anyMatch(Objects::isNull))
            throw new SQLException("Server order requires two complete snapshots");
        var expected = List.copyOf(expectedIds);
        var requested = List.copyOf(requestedIds);
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                StoreConnections.lockPlatformState(connection);
                var current = listServerProfiles().stream().map(StoredServerProfile::id).toList();
                if (!current.equals(expected) || requested.size() != current.size()
                    || !new HashSet<>(requested).equals(new HashSet<>(current)))
                    throw new SQLException("Server order changed or is not a complete permutation");
                long offset;
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COALESCE(MAX(display_order),-1)+CAST(1 AS BIGINT) FROM server_profile")) {
                    rows.next();
                    offset = rows.getLong(1);
                }
                if (offset + requested.size() > Integer.MAX_VALUE)
                    throw new SQLException("Server display order exceeds its supported range");
                try (var statement = connection.prepareStatement(
                    "UPDATE server_profile SET display_order=? WHERE server_profile_id=?")) {
                    writeOrder(statement, requested, offset);
                    writeOrder(statement, requested, 0);
                }
            }
        });
    }

    /** Writes one collision-free phase of the locked permutation. / 写入持锁排列的一个无冲突阶段。
     * @param statement scoped update / 限定更新
     * @param order complete order / 完整顺序
     * @param offset phase base / 阶段基数
     * @throws SQLException if a row disappears / 记录消失时
     */
    private static void writeOrder(PreparedStatement statement, List<String> order, long offset) throws SQLException {
        for (int index = 0; index < order.size(); index++) {
            statement.setLong(1, offset + index);
            statement.setString(2, order.get(index));
            if (statement.executeUpdate() != 1)
                throw new SQLException("Server order could not be confirmed");
        }
    }

    /** Saves an authenticated trust identity against an existing profile. / 为已有配置保存认证信任身份。
     * @param server authenticated server / 已认证服务器
     * @throws SQLException on a missing profile or changed identity / 配置缺失或身份变化
     */
    public void saveServer(StoredServerIdentity server) throws SQLException {
        connections.transaction(() -> {
            try (var c = connections.open()) {
                StoreConnections.lockPlatformState(c);
                upsertServer(c, server);
            }
        });
    }

    /** Updates authenticated identity within the existing transaction. / 在现有事务内更新已认证身份。
     * @param connection transaction connection / 事务连接
     * @param server authenticated identity / 已认证身份
     * @throws SQLException if the original endpoint or trust changed / 原端点或信任改变时
     */
    private static void upsertServer(Connection connection, StoredServerIdentity server) throws SQLException {
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

    /** Returns the trusted endpoint snapshot, independent of editable connection fields. / 返回独立于可编辑连接字段的信任端点快照。
     * @param serverId profile identifier / 配置标识
     * @return stored trust / 已存信任
     * @throws SQLException on database failure / 数据库失败
     */
    public Optional<StoredServerIdentity> findServer(String serverId) throws SQLException {
        try (var c = connections.open();
             var s = c.prepareStatement(
                 "SELECT * FROM server_profile WHERE server_profile_id=? AND host_key_sha256 IS NOT NULL")) {
            s.setString(1, serverId);
            try (var rows = s.executeQuery()) {
                return rows.next()
                    ? Optional.of(new StoredServerIdentity(serverId, rows.getString("trusted_host"),
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
    public void saveAuthenticatedServer(StoredServerIdentity observed, Optional<StoredServerIdentity> expected)
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
            try (var c = connections.open()) {
                StoreConnections.lockPlatformState(c);
                var prior = findServerProfile(profile.id());
                boolean changed = prior.isPresent()
                    && (!prior.get().host().equals(profile.host()) || prior.get().sshPort() != profile.sshPort());
                if (prior.isPresent() && (changed || !prior.get().username().equals(profile.username())
                    || !prior.get().credentialKey().equals(profile.credentialKey())))
                    gold.debug.windowstolinux.app.db.task.TaskLedger.TaskLedgerQueries.requireServerIdle(c, profile.id());
                if (changed) {
                    try (var s = c.prepareStatement(referenceQuery)) {
                        s.setString(1, profile.id());
                        try (var rows = s.executeQuery()) {
                            if (rows.next())
                                throw new SQLException("a referenced server cannot be rebound; create a new profile");
                        }
                    }
                }
                String sql = prior.isPresent()
                    ? "UPDATE server_profile SET credential_id=?,host=?,ssh_port=?,username=?,display_name=? WHERE server_profile_id=?"
                    : "INSERT INTO server_profile(credential_id,host,ssh_port,username,display_name,server_profile_id,display_order) VALUES(?,?,?,?,?,?,(SELECT COALESCE(MAX(display_order),-1)+1 FROM server_profile))";
                try (var statement = c.prepareStatement(sql)) {
                    statement.setString(1, profile.credentialKey());
                    statement.setString(2, profile.host());
                    statement.setInt(3, profile.sshPort());
                    statement.setString(4, profile.username());
                    statement.setString(5, profile.displayName());
                    statement.setString(6, profile.id());
                    statement.executeUpdate();
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

    /**
     * Deletes only an idle server without persistent business references. / 仅删除没有活动任务及持久业务引用的服务器。
     * @param serverId exact saved server identity / 精确已保存服务器身份
     * @return previous credential identity for separately checked cleanup / 原凭据身份，供单独核验清理
     * @throws SQLException if a task, reference or database failure blocks deletion / 任务、引用或数据库失败阻止删除时
     */
    public Optional<String> deleteServerProfile(String serverId) throws SQLException {
        var credential = new java.util.concurrent.atomic.AtomicReference<Optional<String>>(Optional.empty());
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                StoreConnections.lockPlatformState(connection);
                gold.debug.windowstolinux.app.db.task.TaskLedger.TaskLedgerQueries.requireServerIdle(connection, serverId);
                var profile = findServerProfile(serverId);
                if (profile.isEmpty())
                    return;
                try (var references = connection.prepareStatement(referenceQuery)) {
                    references.setString(1, serverId);
                    try (var rows = references.executeQuery()) {
                        if (rows.next())
                            throw new SQLException("Persistent business references still require this server");
                    }
                }
                try (var statement = connection
                    .prepareStatement("DELETE FROM server_profile WHERE server_profile_id=?")) {
                    statement.setString(1, serverId);
                    if (statement.executeUpdate() != 1)
                        throw new SQLException("Server deletion could not be confirmed");
                }
                credential.set(Optional.of(profile.orElseThrow().credentialKey()));
            }
        });
        return credential.get();
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
            s.setInt(2, connected ? 1 : 0);
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
