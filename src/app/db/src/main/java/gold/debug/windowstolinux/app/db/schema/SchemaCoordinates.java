package gold.debug.windowstolinux.app.db.schema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;

/** Encodes exact logical identities into bounded H2 schema and owner names. / 将精确逻辑身份编码为有界的 H2 数据结构及所有者名称。 */
public final class SchemaCoordinates {
    /** Logical owner, data version and unique generation. / 逻辑所有者、数据版本及唯一代次。 */
    private final SchemaIdentity identity;

    /** Collision-resistant physical schema identifier. / 抗碰撞的物理数据结构标识。 */
    private final String physicalName;

    /** Non-administrator owner user identifier. / 非管理员所有者用户标识。 */
    private final String databaseUser;

    /**
     * Encodes the unchanged owner ID and canonical decimal data version. / 编码未改变的所有者 ID 及规范十进制数据版本。
     * @param identity logical data identity / 逻辑数据身份
     */
    public SchemaCoordinates(SchemaIdentity identity) {
        this.identity = Objects.requireNonNull(identity);
        if (identity.ownerId().isBlank() || identity.ownerId().length() > 512 || identity.ownerId().indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid schema owner identity");
        physicalName = "m_" + hash(identity.ownerId()) + "_v" + identity.schemaVersion();
        databaseUser = "u_" + hash(identity.ownerId() + "\0" + identity.schemaVersion());
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
}
