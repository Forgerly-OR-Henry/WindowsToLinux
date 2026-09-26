package gold.debug.windowstolinux.app.db.schema;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.app.db.PlatformDatabase;
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
        var coordinates = new SchemaCoordinates(identity);
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
                    p.setString(1, database.schemaPassword(coordinates));
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
}
