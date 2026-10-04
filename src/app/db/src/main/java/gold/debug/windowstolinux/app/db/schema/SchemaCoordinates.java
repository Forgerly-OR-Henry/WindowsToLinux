package gold.debug.windowstolinux.app.db.schema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;

/** Encodes exact logical identities into bounded H2 schema and owner names. / 将精确逻辑身份编码为有界的 H2 数据结构及所有者名称。 */
public final class SchemaCoordinates {
    /** Logical owner, data version and unique generation. / 逻辑所有者、数据版本及唯一代次。 */
    private final SchemaIdentity identity;

    /** Collision-resistant physical schema identifier. / 抗碰撞的物理数据结构标识。 */
    private final String physicalName;

    /** Non-administrator owner user identifier. / 非管理员所有者用户标识。 */
    private final String databaseUser;

    /** Owning migration for an intermediate schema, otherwise null. / 中间数据结构所属迁移，否则为空。 */
    private final java.util.UUID operation;

    /** Zero-based intermediate step, or minus one for selectable data. / 从零开始的中间步骤，可选数据为负一。 */
    private final int step;

    /**
     * Encodes the unchanged owner ID and canonical decimal data version. / 编码未改变的所有者 ID 及规范十进制数据版本。
     * @param identity logical data identity / 逻辑数据身份
     */
    public SchemaCoordinates(SchemaIdentity identity) {
        this(identity, null, -1);
    }

    /** Encodes a registered migration intermediate without adding a selectable data version. / 编码已登记迁移中间目标，不增加可选数据版本。
     * @param identity operation-owned logical identity / 操作所属逻辑身份
     * @param operation original migration, or null for permanent data / 原迁移，正式数据为空
     * @param step original intermediate index, or minus one for permanent data / 原中间序号，正式数据为负一 */
    public SchemaCoordinates(SchemaIdentity identity, java.util.UUID operation, int step) {
        this.identity = Objects.requireNonNull(identity);
        this.operation = operation;
        this.step = step;
        if (identity.ownerId().isBlank() || identity.ownerId().length() > 512 || identity.ownerId().indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid schema owner identity");
        if (operation == null && step != -1 || operation != null && (step < 0 || step >= 256))
            throw new IllegalArgumentException("Invalid intermediate identity");
        physicalName = operation == null
            ? "m_" + hash(identity.ownerId()) + "_v" + identity.schemaVersion()
            : "t_" + operation.toString().replace("-", "") + "_" + step;
        databaseUser = operation == null
            ? "u_" + hash(identity.ownerId() + "\0" + identity.schemaVersion())
            : "u_" + hash(physicalName);
    }

    /**
     * Hashes exact UTF-8 without case or Unicode normalization. / 对精确 UTF-8 求散列，不变更大小写或 Unicode 形式。
     * @param input exact identity material / 精确身份材料
     * @return complete lowercase SHA-256 / 完整小写 SHA-256
     */
    private static String hash(String input) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JDK SHA-256 unavailable", unavailable);
        }
    }

    /** Returns the bound logical generation. / 返回绑定的逻辑代次。
     * @return immutable logical identity / 不可变逻辑身份 */
    public SchemaIdentity identity() {
        return identity;
    }

    /** Returns the physical schema identifier. / 返回物理数据结构标识。
     * @return bounded physical name / 有界物理名称 */
    public String physicalName() {
        return physicalName;
    }

    /** Returns the non-administrator owner name. / 返回非管理员所有者名称。
     * @return owner user identifier / 所有者用户标识 */
    public String databaseUser() {
        return databaseUser;
    }

    /**
     * Rejects stale generations, mismatched ownership and unavailable states. / 拒绝过期代次、归属不匹配及不可用状态。
     * @param database engine holding the authoritative registry / 持有权威登记的引擎
     * @param allowedStates states accepted by the current operation / 当前操作接受的状态
     * @throws SQLException if the exact registration is absent or unavailable / 精确登记不存在或不可用时
     */
    public void requireRegistered(PlatformDatabase database, Set<String> allowedStates) throws SQLException {
        try (var connection = database.openConnection()) {
            requireRegistered(connection, allowedStates);
        }
    }

    /**
     * Checks registration inside an existing platform transaction. / 在已有平台事务中核对登记。
     * @param connection caller-owned platform connection / 调用方所属平台连接
     * @param allowedStates accepted lifecycle states / 接受的生命周期状态
     * @throws SQLException if exact registration is unavailable / 精确登记不可用时
     */
    public void requireRegistered(java.sql.Connection connection, Set<String> allowedStates) throws SQLException {
        if (operation != null) {
            requireIntermediate(connection, allowedStates);
            return;
        }
        try (var p = connection.prepareStatement(
            "SELECT generation,physical_name,database_user,state FROM mod_schema WHERE mod_id=? AND schema_version=?")) {
            p.setString(1, identity.ownerId());
            p.setString(2, identity.schemaVersion());
            try (var r = p.executeQuery()) {
                if (!r.next() || !identity.generation().equals(r.getObject(1, java.util.UUID.class))
                    || !physicalName.equals(r.getString(2)) || !databaseUser.equals(r.getString(3))
                    || !allowedStates.contains(r.getString(4)))
                    throw new SQLException("Schema registration is stale or unavailable", "55000");
            }
        }
    }

    /** Checks live schema ownership and the original non-administrator account. / 检查实际数据结构归属及原非管理员账户。
     * @param connection privileged host connection / 宿主特权连接
     * @throws SQLException if physical objects no longer match the registration / 物理对象不再匹配登记时 */
    public void requirePhysicalOwnership(java.sql.Connection connection) throws SQLException {
        try (var p = connection.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.schemata s JOIN information_schema.users u ON u.user_name=s.schema_owner WHERE s.schema_name=? AND s.schema_owner=? AND NOT u.is_admin")) {
            p.setString(1, physicalName);
            p.setString(2, databaseUser);
            try (var r = p.executeQuery()) {
                r.next();
                if (r.getLong(1) != 1)
                    throw new SQLException("Schema physical ownership no longer matches its original registration",
                        "55000");
            }
        }
    }

    /** Checks original recovery inventory and step ownership for an intermediate. / 检查中间目标的原恢复清单及步骤归属。
     * @param connection platform connection / 平台连接
     * @param allowedStates caller's permitted data states / 调用者允许的数据状态
     * @throws SQLException if the original migration identity changed / 原迁移身份发生变化时 */
    private void requireIntermediate(java.sql.Connection connection, Set<String> allowedStates) throws SQLException {
        if (!allowedStates.contains("PREPARING"))
            throw new SQLException("Intermediate data cannot be selected for business");
        try (var p = connection.prepareStatement(
            "SELECT o.recovery_json FROM mod_operation o JOIN mod_migration_step s ON s.operation_id=o.operation_id WHERE o.operation_id=? AND o.mod_id=? AND o.operation_type='MIGRATE' AND o.state='RUNNING' AND o.outcome IS NULL AND s.step_number=? AND s.to_version=? AND s.target_schema=?")) {
            p.setObject(1, operation);
            p.setString(2, identity.ownerId());
            p.setInt(3, step);
            p.setString(4, identity.schemaVersion());
            p.setString(5, physicalName);
            try (var r = p.executeQuery()) {
                if (!r.next())
                    throw new SQLException("Original intermediate registration is absent");
                try {
                    var json = new com.fasterxml.jackson.databind.ObjectMapper()
                        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(r.getString(1));
                    var target = json.path("steps").path(step);
                    if (!identity.generation().toString().equals(target.path("generation").textValue())
                        || !physicalName.equals(target.path("physicalSchema").textValue())
                        || !databaseUser.equals(target.path("databaseUser").textValue()))
                        throw new SQLException("Original intermediate inventory changed");
                } catch (java.io.IOException invalid) {
                    throw new SQLException("Invalid intermediate inventory", invalid);
                }
            }
        }
    }
}
