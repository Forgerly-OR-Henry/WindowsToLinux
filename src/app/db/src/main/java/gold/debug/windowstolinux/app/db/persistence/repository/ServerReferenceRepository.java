package gold.debug.windowstolinux.app.db.persistence.repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.app.db.execution.schema.SchemaCoordinates;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.server.*;

/** Stores generation-owned server references without inspecting private business records. / 保存代次所属服务器引用，不检查私有业务记录。 */
public final class ServerReferenceRepository {
    /** Platform transaction scope shared with configuration and credentials. / 与配置及凭据共享的平台事务作用域。 */
    private final RepositoryConnections connections;

    /**
     * Binds existing platform transaction ownership. / 绑定已有平台事务归属。
     * @param connections common platform scope / 共同平台作用域
     */
    public ServerReferenceRepository(RepositoryConnections connections) {
        this.connections = Objects.requireNonNull(connections);
    }

    /**
     * Lists only servers held by the original task, using stable identifier pagination. / 仅列出原任务持有的服务器，按稳定标识分页。
     * @param taskId original active task / 原始活动任务
     * @param owner exact caller identity / 精确调用方身份
     * @param after exclusive previous identifier or null / 不含端点的前一标识，可为空
     * @param limit bounded requested row count / 有界请求行数
     * @return non-secret configuration observations / 不含秘密的配置观测
     * @throws SQLException if reading fails / 读取失败时
     */
    public List<ServerInfo> list(UUID taskId, String owner, String after, int limit) throws SQLException {
        if (limit < 1 || limit > 201)
            throw new SQLException("Invalid server page size");
        var result = new ArrayList<ServerInfo>();
        try (var connection = connections.open();
                var statement = connection.prepareStatement(
                        "SELECT s.* FROM server_profile s WHERE (? IS NULL OR s.server_profile_id>?) AND EXISTS (SELECT 1 FROM platform_task_resource r WHERE r.task_id=? AND r.owner_id=? AND r.resource_kind='SERVER' AND r.resource_id=s.server_profile_id AND r.released_at IS NULL) ORDER BY s.server_profile_id LIMIT ?")) {
            statement.setString(1, after);
            statement.setString(2, after);
            statement.setObject(3, taskId);
            statement.setString(4, owner);
            statement.setInt(5, limit);
            try (var rows = statement.executeQuery()) {
                while (rows.next())
                    result.add(read(rows));
            }
        }
        return List.copyOf(result);
    }

    /** Lists configured non-secret summaries without creating a task resource or server reference. / 无须创建任务资源或服务器引用即可列出已配置非秘密摘要。
     * @param after exclusive previous identifier or null / 不含端点的前一标识，可为空
     * @param limit bounded requested rows / 有界请求行数
     * @return saved server summaries / 已保存服务器摘要
     * @throws SQLException if the platform read fails / 平台读取失败时 */
    public List<ServerInfo> listConfigured(String after, int limit) throws SQLException {
        if (limit < 1 || limit > 201)
            throw new SQLException("Invalid server page size");
        var result = new ArrayList<ServerInfo>();
        try (var connection = connections.open();
                var statement = connection.prepareStatement("SELECT * FROM server_profile "
                        + "WHERE (? IS NULL OR server_profile_id>?) ORDER BY server_profile_id LIMIT ?")) {
            statement.setString(1, after);
            statement.setString(2, after);
            statement.setInt(3, limit);
            try (var rows = statement.executeQuery()) {
                while (rows.next())
                    result.add(read(rows));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns one already admitted server observation without querying the remote host. / 返回一个已准入服务器观测，不查询远端主机。
     * @param serverId exact admitted server / 精确已准入服务器
     * @return current saved observation / 当前已保存观测
     * @throws SQLException if the profile is missing or unreadable / 配置缺失或不可读时
     */
    public ServerInfo get(String serverId) throws SQLException {
        try (var connection = connections.open();
                var statement = connection.prepareStatement("SELECT * FROM server_profile WHERE server_profile_id=?")) {
            statement.setString(1, serverId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next())
                    throw new SQLException("Admitted server no longer exists", "02000");
                return read(rows);
            }
        }
    }

    /**
     * Reserves the exact resource and business key without weakening an existing state. / 预留精确资源及业务键，不弱化已有状态。
     * @param identity admitted business schema generation / 已准入业务数据代次
     * @param serverId exact admitted server / 精确已准入服务器
     * @param businessKey author-owned association key / 作者所属关联键
     * @return original or newly reserved reference / 原引用或新预留引用
     * @throws SQLException if generation, server or persistence is unavailable / 代次、服务器或持久化不可用时
     */
    public ServerReference reserve(SchemaIdentity identity, String serverId, String businessKey) throws SQLException {
        var result = new java.util.concurrent.atomic.AtomicReference<ServerReference>();
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                RepositoryConnections.lockPlatformState(connection);
                new SchemaCoordinates(identity).requireRegistered(connection, Set.of("READY"));
                lockServer(connection, serverId);
                var existing = find(connection, identity, serverId, businessKey);
                if (existing.isPresent()) {
                    result.set(existing.orElseThrow());
                    return;
                }
                UUID id = UUID.randomUUID();
                try (var statement = connection.prepareStatement(
                        "INSERT INTO mod_server_reference(reference_id,server_profile_id,mod_id,schema_version,schema_generation,business_key,state,updated_at) VALUES(?,?,?,?,?,?,'RESERVED',?)")) {
                    statement.setObject(1, id);
                    statement.setString(2, serverId);
                    statement.setString(3, identity.ownerId());
                    statement.setString(4, identity.schemaVersion());
                    statement.setObject(5, identity.generation());
                    statement.setString(6, businessKey);
                    statement.setLong(7, System.currentTimeMillis());
                    statement.executeUpdate();
                }
                result.set(new ServerReference(id, new ServerRef(serverId), businessKey, ReferenceState.RESERVED));
            }
        });
        return result.get();
    }

    /**
     * Reads the original business-key association without locks or reference mutation. / 按原业务键读取关联，不加引用行锁或修改引用。
     * @param identity exact admitted data generation / 精确已准入数据代次
     * @param serverId original resource identity / 原资源身份
     * @param businessKey exact association key / 精确关联键
     * @return original reference or absence / 原引用或空值
     * @throws SQLException if ownership, generation or reading fails / 归属、代次或读取失败时
     */
    public Optional<ServerReference> find(SchemaIdentity identity, String serverId, String businessKey)
            throws SQLException {
        try (var connection = connections.open()) {
            new SchemaCoordinates(identity).requireRegistered(connection, Set.of("READY"));
            return find(connection, identity, serverId, businessKey);
        }
    }

    /**
     * Reads one exact association in the caller's existing connection scope. / 在调用方已有连接作用域中读取一个精确关联。
     * @param connection caller-owned connection / 调用方所属连接
     * @param identity exact generation / 精确代次
     * @param serverId original resource / 原资源
     * @param businessKey exact association key / 精确关联键
     * @return matching reference or absence / 匹配引用或空值
     * @throws SQLException if reading or stored identity validation fails / 读取或存储身份校验失败时
     */
    private static Optional<ServerReference> find(Connection connection, SchemaIdentity identity, String serverId,
            String businessKey) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT reference_id,state FROM mod_server_reference WHERE mod_id=? AND schema_version=? AND schema_generation=? AND server_profile_id=? AND business_key=?")) {
            statement.setString(1, identity.ownerId());
            statement.setString(2, identity.schemaVersion());
            statement.setObject(3, identity.generation());
            statement.setString(4, serverId);
            statement.setString(5, businessKey);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    return Optional.of(new ServerReference(rows.getObject(1, UUID.class), new ServerRef(serverId),
                            businessKey, ReferenceState.valueOf(rows.getString(2))));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Finds a reservation only inside its exact owning generation. / 仅在引用的精确所属代次中查询预留。
     * @param identity bound generation / 绑定代次
     * @param referenceId original reservation / 原始预留
     * @return owned reference, absent when already removed / 所属引用，已删除时为空
     * @throws SQLException if a found reference belongs to another generation / 找到的引用属于其他代次时
     */
    public Optional<ServerReference> find(SchemaIdentity identity, UUID referenceId) throws SQLException {
        try (var connection = connections.open();
                var statement = connection
                        .prepareStatement("SELECT * FROM mod_server_reference WHERE reference_id=?")) {
            statement.setObject(1, referenceId);
            try (var row = statement.executeQuery()) {
                if (!row.next())
                    return Optional.empty();
                if (!identity.ownerId().equals(row.getString("mod_id"))
                        || !identity.schemaVersion().equals(row.getString("schema_version"))
                        || !identity.generation().equals(row.getObject("schema_generation", UUID.class)))
                    throw new SQLException("Reference belongs to another schema generation", "42501");
                return Optional.of(new ServerReference(referenceId, new ServerRef(row.getString("server_profile_id")),
                        row.getString("business_key"), ReferenceState.valueOf(row.getString("state"))));
            }
        }
    }

    /**
     * Applies the author's known association result under the same lock as reserve and deletion. / 在与预留及删除相同的锁下应用作者已核实的关联结果。
     * @param identity exact admitted schema generation / 精确已准入数据代次
     * @param referenceId original owned reference / 原始所属引用
     * @param result author-confirmed association state / 作者确认的关联状态
     * @throws SQLException if identity, state or persistence cannot be verified / 无法核实身份、状态或持久化时
     */
    public void resolve(SchemaIdentity identity, UUID referenceId, ReferenceResolution result) throws SQLException {
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                RepositoryConnections.lockPlatformState(connection);
                new SchemaCoordinates(identity).requireRegistered(connection, Set.of("READY"));
                var reference = find(identity, referenceId);
                if (reference.isEmpty()) {
                    if (result == ReferenceResolution.NOT_REFERENCED)
                        return;
                    throw new SQLException("Server reservation no longer exists", "02000");
                }
                lockServer(connection, reference.orElseThrow().server().id());
                if (result == ReferenceResolution.NOT_REFERENCED) {
                    try (var statement = connection
                            .prepareStatement("DELETE FROM mod_server_reference WHERE reference_id=?")) {
                        statement.setObject(1, referenceId);
                        if (statement.executeUpdate() != 1)
                            throw new SQLException("Reference release was not confirmed");
                    }
                } else {
                    try (var statement = connection.prepareStatement(
                            "UPDATE mod_server_reference SET state=?,updated_at=? WHERE reference_id=?")) {
                        statement.setString(1, result == ReferenceResolution.REFERENCED ? "ACTIVE" : "UNKNOWN");
                        statement.setLong(2, System.currentTimeMillis());
                        statement.setObject(3, referenceId);
                        if (statement.executeUpdate() != 1)
                            throw new SQLException("Reference resolution was not confirmed");
                    }
                }
            }
        });
    }

    /**
     * Locks the same configuration row protected during resource deletion. / 锁定资源删除期间保护的同一配置行。
     * @param connection current owning transaction / 当前所属事务
     * @param serverId selected server / 所选服务器
     * @throws SQLException if the server disappeared / 服务器已消失时
     */
    private static void lockServer(Connection connection, String serverId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT server_profile_id FROM server_profile WHERE server_profile_id=? FOR UPDATE")) {
            statement.setString(1, serverId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next())
                    throw new SQLException("Referenced server does not exist", "02000");
            }
        }
    }

    /**
     * Converts configuration to the public observation contract. / 将配置转换为公开观测契约。
     * @param row current saved profile row / 当前已保存配置行
     * @return non-secret observation / 不含秘密的观测
     * @throws SQLException if persisted columns cannot be read / 无法读取持久列时
     */
    private static ServerInfo read(ResultSet row) throws SQLException {
        return new ServerInfo(new ServerRef(row.getString("server_profile_id")), row.getString("display_name"),
                row.getString("host"), row.getInt("ssh_port"), row.getString("username"),
                Optional.ofNullable(row.getString("host_key_sha256")),
                row.getObject("last_checked_at") == null
                        ? Optional.empty()
                        : Optional.of(Instant.ofEpochMilli(row.getLong("last_checked_at"))));
    }
}
