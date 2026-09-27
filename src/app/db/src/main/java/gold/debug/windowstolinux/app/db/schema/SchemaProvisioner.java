package gold.debug.windowstolinux.app.db.schema;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;

/** Creates explicitly registered Mod schemas without adopting preexisting database objects. / 创建明确登记的 Mod 数据结构，不接管已有数据库对象。 */
public final class SchemaProvisioner {
    /** Engine whose privileged connection performs bounded provisioning. / 通过特权连接执行有限准备操作的引擎。 */
    private final PlatformDatabase database;

    /**
     * Binds the provisioning service to the host-owned engine. / 将准备服务绑定到宿主持有的引擎。
     * @param database host-owned engine / 宿主持有的引擎
     */
    public SchemaProvisioner(PlatformDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    /**
     * Creates an owner and schema only after durable PREPARING registration. / 仅在持久化 PREPARING 登记后创建所有者及数据结构。
     * DDL commits independently; failure preserves residue for operation reconciliation. / DDL 独立提交；失败保留残留供操作核对。
     * The caller serializes schema operations and records their recovery identity first. / 调用方串行化数据结构操作并预先记录恢复身份。
     * @param identity freshly registered target generation / 新登记的目标代次
     * @throws Exception if the registration, collision check or DDL fails / 登记、冲突检查或 DDL 失败时
     */
    public void create(SchemaIdentity identity) throws Exception {
        create(new SchemaCoordinates(identity));
    }

    /** Creates exactly the permanent or intermediate target registered by the operation. / 仅创建操作登记的正式或中间目标。
     * @param coordinates exact persisted target / 精确已持久目标
     * @throws Exception if registration or physical creation is unconfirmed / 登记或物理创建未确认时 */
    public void create(SchemaCoordinates coordinates) throws Exception {
        coordinates.requireRegistered(database, Set.of("PREPARING"));
        try (var c = database.openConnection()) {
            try (var p = c.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=? UNION ALL SELECT COUNT(*) FROM information_schema.users WHERE user_name=?")) {
                p.setString(1, coordinates.physicalName());
                p.setString(2, coordinates.databaseUser());
                try (var r = p.executeQuery()) {
                    while (r.next())
                        if (r.getLong(1) != 0)
                            throw new SQLException("Schema target or owner already exists; reconciliation is required");
                }
            }
            try {
                try (var p = c.prepareStatement(
                        "CREATE USER " + SchemaAccessScope.quote(coordinates.databaseUser()) + " PASSWORD ?")) {
                    p.setString(1, database.schemaPassword(coordinates.databaseUser()));
                    p.execute();
                }
                try (var s = c.createStatement()) {
                    s.execute("CREATE SCHEMA " + SchemaAccessScope.quote(coordinates.physicalName()) + " AUTHORIZATION "
                            + SchemaAccessScope.quote(coordinates.databaseUser()));
                }
            } catch (SQLException failure) {
                throw new SQLException("Schema provisioning failed; its recorded target requires reconciliation",
                        failure.getSQLState(), failure.getErrorCode());
            }
        }
    }

    /** Releases a proven operation-created intermediate after all its scopes close. / 全部作用域关闭后释放已证明由操作创建的中间目标。
     * @param coordinates original operation target / 原操作目标
     * @throws SQLException if original registration, ownership or release is unconfirmed / 原登记、归属或释放未确认时 */
    public void dropIntermediate(SchemaCoordinates coordinates) throws SQLException {
        if (!coordinates.physicalName().startsWith("t_"))
            throw new SQLException("Permanent Mod data cannot be removed as an intermediate");
        coordinates.requireRegistered(database, Set.of("PREPARING"));
        try (var c = database.openConnection()) {
            try (var p = c
                    .prepareStatement("SELECT schema_owner FROM information_schema.schemata WHERE schema_name=?")) {
                p.setString(1, coordinates.physicalName());
                try (var r = p.executeQuery()) {
                    if (r.next() && !coordinates.databaseUser().equals(r.getString(1)))
                        throw new SQLException("Intermediate physical ownership changed");
                }
            }
            try (var s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + SchemaAccessScope.quote(coordinates.physicalName()) + " CASCADE");
                s.execute("DROP USER IF EXISTS " + SchemaAccessScope.quote(coordinates.databaseUser()));
            }
        }
    }

    /** Removes the exact unused failed physical target only under its explicit retry claim. / 仅在明确重试取得记录下移除精确未使用失败物理目标。
     * @param target newly registered generation at the same logical target / 同一逻辑目标的新登记代次
     * @param operation original explicit retry / 原明确重试
     * @throws SQLException if original ownership, claim or physical release is uncertain / 原归属、取得记录或物理释放不确定时 */
    public void resetFailedTarget(SchemaIdentity target, java.util.UUID operation) throws SQLException {
        var coordinates = new SchemaCoordinates(target);
        coordinates.requireRegistered(database, Set.of("PREPARING"));
        try (var c = database.openConnection()) {
            try (var p = c.prepareStatement(
                    "SELECT request_json,recovery_json FROM mod_operation WHERE operation_id=? AND operation_type='MIGRATE' AND state='RUNNING' AND phase='MIGRATING' AND target_generation=?")) {
                p.setObject(1, operation);
                p.setObject(2, target.generation());
                try (var r = p.executeQuery()) {
                    if (!r.next())
                        throw new SQLException("Original retry reset is not active");
                    try {
                        var json = new com.fasterxml.jackson.databind.ObjectMapper();
                        var request = json.readTree(r.getString(1));
                        var recovery = json.readTree(r.getString(2));
                        if (!"RETRY".equals(request.path("mode").asText())
                                || !operation.toString().equals(recovery.path("intent").asText())
                                || !database.bootId().toString().equals(recovery.path("bootId").asText())
                                || target.generation().toString().equals(request.path("previousGeneration").asText())
                                || recovery.path("facts").path("retryResetComplete").asBoolean()
                                || recovery.path("facts").has("created"))
                            throw new SQLException("Original reset evidence changed");
                    } catch (java.io.IOException invalid) {
                        throw new SQLException("Invalid retry reset evidence");
                    }
                }
            }
            try (var p = c.prepareStatement(
                    "SELECT (SELECT COUNT(*) FROM information_schema.users WHERE user_name=? AND is_admin)+(SELECT COUNT(*) FROM information_schema.schemata WHERE schema_owner=? AND schema_name<>?)+(SELECT COUNT(*) FROM information_schema.sessions WHERE LOWER(user_name)=?)")) {
                p.setString(1, coordinates.databaseUser());
                p.setString(2, coordinates.databaseUser());
                p.setString(3, coordinates.physicalName());
                p.setString(4, coordinates.databaseUser());
                try (var r = p.executeQuery()) {
                    r.next();
                    if (r.getLong(1) != 0)
                        throw new SQLException("Failed target has altered ownership or active sessions");
                }
            }
            try (var p = c.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                p.setString(1, coordinates.physicalName());
                try (var r = p.executeQuery()) {
                    r.next();
                    if (r.getLong(1) != 0)
                        coordinates.requirePhysicalOwnership(c);
                }
            }
            try (var p = c.prepareStatement(
                    "SELECT grantedrole,table_schema FROM information_schema.rights WHERE grantee=?")) {
                p.setString(1, coordinates.databaseUser());
                try (var r = p.executeQuery()) {
                    while (r.next())
                        if (r.getString(1) != null || !coordinates.physicalName().equals(r.getString(2)))
                            throw new SQLException("Failed owner has unexpected rights");
                }
            }
            try (var s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + SchemaAccessScope.quote(coordinates.physicalName()) + " CASCADE");
                s.execute("DROP USER IF EXISTS " + SchemaAccessScope.quote(coordinates.databaseUser()));
            }
        }
    }

    /** Releases one inventory-recorded SELECT identity after old connection ownership ended. / 旧连接归属结束后释放一个清单记录的 SELECT 身份。
     * @param scope original persisted scope / 原持久作用域
     * @param coordinates only permitted schema / 唯一允许的数据结构
     * @throws SQLException if the user gained other rights, ownership or still has a connection / 用户获得其他权限、归属或仍有连接时 */
    public void releaseRecordedReader(java.util.UUID scope, SchemaCoordinates coordinates) throws SQLException {
        if (database.openConnections(scope.toString()) != 0)
            throw new SQLException("Original reader connections remain");
        String user = SchemaAccessScope.readerUser(scope);
        try (var c = database.openConnection()) {
            try (var p = c.prepareStatement(
                    "SELECT (SELECT COUNT(*) FROM information_schema.users WHERE user_name=? AND is_admin)+(SELECT COUNT(*) FROM information_schema.schemata WHERE schema_owner=?)+(SELECT COUNT(*) FROM information_schema.sessions WHERE LOWER(user_name)=?)")) {
                p.setString(1, user);
                p.setString(2, user);
                p.setString(3, user);
                try (var r = p.executeQuery()) {
                    r.next();
                    if (r.getLong(1) != 0)
                        throw new SQLException("Recorded reader gained ownership or remains active");
                }
            }
            try (var p = c.prepareStatement(
                    "SELECT grantedrole,rights,table_schema FROM information_schema.rights WHERE grantee=?")) {
                p.setString(1, user);
                try (var r = p.executeQuery()) {
                    while (r.next()) {
                        if (r.getString(1) != null || !"SELECT".equals(r.getString(2))
                                || !coordinates.physicalName().equals(r.getString(3)))
                            throw new SQLException("Recorded reader has unexpected privileges");
                    }
                }
            }
            try (var s = c.createStatement()) {
                s.execute("DROP USER IF EXISTS " + SchemaAccessScope.quote(user));
            }
        }
    }

    /** Removes only a proven-created original intermediate during explicit reconciliation. / 明确核对时仅移除有创建证明的原中间目标。
     * @param coordinates exact original inventory entry / 精确原清单项
     * @param creationAcknowledged whether original creation was durably acknowledged / 原创建是否已持久确认
     * @throws SQLException if an unowned, partial or altered object remains / 仍有无归属、部分或变化对象时 */
    public void dropReconciledIntermediate(SchemaCoordinates coordinates, boolean creationAcknowledged)
            throws SQLException {
        if (!coordinates.physicalName().startsWith("t_"))
            throw new SQLException("Final data is not an intermediate");
        try (var c = database.openConnection()) {
            boolean present;
            try (var p = c.prepareStatement(
                    "SELECT (SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?)+(SELECT COUNT(*) FROM information_schema.users WHERE user_name=?)")) {
                p.setString(1, coordinates.physicalName());
                p.setString(2, coordinates.databaseUser());
                try (var r = p.executeQuery()) {
                    r.next();
                    present = r.getLong(1) != 0;
                }
            }
            if (!present)
                return;
            if (!creationAcknowledged)
                throw new SQLException("Intermediate creation was not acknowledged");
            try (var p = c.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                p.setString(1, coordinates.physicalName());
                try (var r = p.executeQuery()) {
                    r.next();
                    if (r.getLong(1) != 0)
                        coordinates.requirePhysicalOwnership(c);
                }
            }
            try (var p = c.prepareStatement(
                    "SELECT (SELECT COUNT(*) FROM information_schema.users WHERE user_name=? AND is_admin)+(SELECT COUNT(*) FROM information_schema.schemata WHERE schema_owner=? AND schema_name<>?)+(SELECT COUNT(*) FROM information_schema.sessions WHERE LOWER(user_name)=?)")) {
                p.setString(1, coordinates.databaseUser());
                p.setString(2, coordinates.databaseUser());
                p.setString(3, coordinates.physicalName());
                p.setString(4, coordinates.databaseUser());
                try (var r = p.executeQuery()) {
                    r.next();
                    if (r.getLong(1) != 0)
                        throw new SQLException("Intermediate ownership or occupancy changed");
                }
            }
            try (var s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + SchemaAccessScope.quote(coordinates.physicalName()) + " CASCADE");
                s.execute("DROP USER IF EXISTS " + SchemaAccessScope.quote(coordinates.databaseUser()));
            }
        }
    }
}
