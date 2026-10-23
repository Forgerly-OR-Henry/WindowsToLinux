package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.schema.SchemaProvisioner;
import gold.debug.windowstolinux.app.db.schema.SchemaSnapshot;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

/** Checks the current single version through actual restricted platform accounts. / 通过真实平台受限账户核验当前单一版本。 */
class DeploymentSchemaTest extends DeploymentRuntimeFixture {
    /** Verifies current management actions and SELECT-only validation without granting the reader writes. / 核验当前管理动作与仅SELECT验证，不授予读者写权限。
     * @throws Exception if actual initialization, constraints or cleanup fail / 实际初始化、约束或收尾失败时 */
    @Test
    void currentVersionOneAcceptsExactAutostartActionsAndValidatesThroughReadOnlyAccount() throws Exception {
        assertEquals("1", DeploymentSchema.VERSION);
        try (var runtime = open()) {
            var identity = register(runtime);
            var accepted = Set.of("LIFECYCLE_START", "LIFECYCLE_ENABLE_AUTOSTART", "LIFECYCLE_DISABLE_AUTOSTART");
            try (var writer = scope(runtime, identity, false)) {
                new DeploymentSchema().initialize(writer);
                try (var connection = writer.openConnection()) {
                    try (var stream = java.util.Objects.requireNonNull(getClass().getResourceAsStream("column-order-v1.txt"))) {
                        var lines = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
                        assertEquals(20, lines.size());
                        for (var line : lines) {
                            var parts = line.split("=", 2);
                            var actual = new java.util.ArrayList<String>();
                            try (var statement = connection.createStatement(); var columns = statement.executeQuery("SELECT * FROM " + parts[0] + " WHERE 1=0")) {
                                for (int i = 1; i <= columns.getMetaData().getColumnCount(); i++)
                                    actual.add(columns.getMetaData().getColumnName(i));
                            }
                            assertEquals(java.util.List.of(parts[1].split(",")), actual, parts[0]);
                            var keys = new java.util.TreeMap<Short, String>();
                            try (var primary = connection.getMetaData().getPrimaryKeys(null, connection.getSchema(), parts[0])) {
                                while (primary.next())
                                    keys.put(primary.getShort("KEY_SEQ"), primary.getString("COLUMN_NAME"));
                            }
                            assertFalse(keys.isEmpty(), parts[0]);
                            assertEquals(java.util.List.copyOf(keys.values()), actual.subList(0, keys.size()), parts[0]);
                        }
                    }
                    for (var action : accepted) insertManagementAction(connection, action);
                    var failure = assertThrows(SQLException.class,
                        () -> insertManagementAction(connection, "LIFECYCLE_UNREVIEWED_ACTION"));
                    assertEquals("23513", failure.getSQLState(), "The current SQL CHECK must reject the unknown action");
                }
                assertEquals(0, writer.openConnections());
            }
            try (var reader = scope(runtime, identity, true)) {
                var check = new DeploymentSchema().validate(reader);
                assertTrue(check.valid(), check.violations().toString());
                assertTrue(check.businessDigest().isPresent());
                try (var connection = reader.openConnection(); var statement = connection.createStatement()) {
                    var actual = new HashSet<String>();
                    try (var rows = statement.executeQuery("SELECT action FROM deployment_management_operation")) {
                        while (rows.next()) actual.add(rows.getString(1));
                    }
                    assertEquals(accepted, actual);
                    assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "INSERT INTO preference(preference_key,preference_value) VALUES('reader-write','forbidden')"));
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM preference WHERE preference_key='reader-write'")) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1));
                    }
                }
                assertEquals(0, reader.openConnections());
            }
        }
    }

    /** Rejects added columns without DDL, row rewrites or deletion of preserved data. / 拒绝新增列，不执行DDL、行重写或删除保留数据。
     * @throws Exception if original structure evidence cannot be established / 无法确立原结构依据时 */
    @Test
    void readOnlyValidationRejectsUnexpectedColumnWithoutChangingOriginalSchemaOrRows() throws Exception {
        assertEquals("1", DeploymentSchema.VERSION);
        try (var runtime = open()) {
            var identity = register(runtime);
            try (var writer = scope(runtime, identity, false)) {
                new DeploymentSchema().initialize(writer);
                try (var connection = writer.openConnection(); var statement = connection.createStatement()) {
                    assertEquals(1, statement.executeUpdate(
                        "INSERT INTO preference(preference_key,preference_value) VALUES('schema-validation-retained','original-row')"));
                    statement.executeUpdate("ALTER TABLE preference ADD COLUMN unexpected_extra VARCHAR(32)");
                }
                assertEquals(0, writer.openConnections());
            }
            var before = SchemaSnapshot.digest(runtime.persistence().database(), new SchemaCoordinates(identity));
            try (var reader = scope(runtime, identity, true)) {
                var check = new DeploymentSchema().validate(reader);
                assertFalse(check.valid());
                assertTrue(check.violations().contains("Unexpected deployment columns: preference"),
                    check.violations().toString());
                assertTrue(check.businessDigest().isEmpty());
                try (var connection = reader.openConnection(); var statement = connection.createStatement();
                     var row = statement.executeQuery("SELECT preference_value,unexpected_extra FROM preference WHERE preference_key='schema-validation-retained'")) {
                    assertTrue(row.next());
                    assertEquals("original-row", row.getString(1));
                    assertNull(row.getString(2));
                    assertFalse(row.next());
                }
                assertEquals(0, reader.openConnections());
            }
            assertEquals(before, SchemaSnapshot.digest(runtime.persistence().database(), new SchemaCoordinates(identity)),
                "Read-only validation must not change the original structure or rows");
        }
    }

    @Test
    void readOnlyValidationRejectsReorderedColumnsWithoutChangingSavedRows() throws Exception {
        try (var runtime = open()) {
            var identity = register(runtime);
            try (var writer = scope(runtime, identity, false)) {
                new DeploymentSchema().initialize(writer);
                try (var connection = writer.openConnection(); var statement = connection.createStatement()) {
                    statement.executeUpdate("ALTER TABLE preference RENAME TO preference_original");
                    statement.executeUpdate("CREATE TABLE preference(preference_value CHARACTER VARYING NOT NULL, preference_key VARCHAR(128) PRIMARY KEY)");
                    statement.executeUpdate("INSERT INTO preference(preference_key,preference_value) VALUES('retained','original')");
                    statement.executeUpdate("DROP TABLE preference_original");
                }
            }
            var before = SchemaSnapshot.digest(runtime.persistence().database(), new SchemaCoordinates(identity));
            try (var reader = scope(runtime, identity, true)) {
                var result = new DeploymentSchema().validate(reader);
                assertFalse(result.valid());
                assertTrue(result.violations().stream().anyMatch(value -> value.startsWith("Deployment column order differs: preference")));
                try (var connection = reader.openConnection(); var statement = connection.createStatement();
                     var row = statement.executeQuery("SELECT preference_value FROM preference WHERE preference_key='retained'")) {
                    assertTrue(row.next());
                    assertEquals("original", row.getString(1));
                }
            }
            assertEquals(before, SchemaSnapshot.digest(runtime.persistence().database(), new SchemaCoordinates(identity)));
        }
    }

    /** Inserts a real management row whose only varying constraint input is the action. / 写入真实管理行，仅动作是变化的约束输入。
     * @param connection original restricted writer / 原受限写者
     * @param action exact tested action / 精确受测动作
     * @throws SQLException if actual SQL rejects the row / 实际SQL拒绝行时 */
    private static void insertManagementAction(Connection connection, String action) throws SQLException {
        try (var insert = connection.prepareStatement("""
            INSERT INTO deployment_management_operation(operation_id,platform_task_id,owner_mod_id,owner_mod_version,
                application_id,server_profile_id,action,current_release_sha256,phase,component_state,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, UUID.randomUUID());
            insert.setString(3, OWNER);
            insert.setString(4, VERSION);
            insert.setString(5, "schema-test-application");
            insert.setString(6, "schema-test-server");
            insert.setString(7, action);
            insert.setString(8, "a".repeat(64));
            insert.setString(9, "REVIEWED");
            insert.setBytes(10, new byte[]{1});
            insert.setLong(11, 1);
            assertEquals(1, insert.executeUpdate());
        }
    }

    /** Registers one current version-1 target before actual provisioning. / 实际准备前登记一个当前版本1目标。
     * @param runtime actual platform / 实际平台
     * @return exact registered identity / 精确已登记身份
     * @throws Exception if registration or preparation fails / 登记或准备失败时 */
    private static SchemaIdentity register(PlatformRuntime runtime) throws Exception {
        var identity = new SchemaIdentity(OWNER, DeploymentSchema.VERSION, UUID.randomUUID());
        var coordinates = new SchemaCoordinates(identity);
        try (var connection = runtime.persistence().database().openConnection();
             var registry = connection.prepareStatement("MERGE INTO mod_registry(mod_id,updated_at) KEY(mod_id) VALUES(?,1)");
             var schema = connection.prepareStatement("""
                 INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at)
                 VALUES(?,?,?,?,?,'PREPARING',1)
                 """)) {
            registry.setString(1, OWNER);
            registry.executeUpdate();
            schema.setString(1, OWNER);
            schema.setString(2, DeploymentSchema.VERSION);
            schema.setObject(3, identity.generation());
            schema.setString(4, coordinates.physicalName());
            schema.setString(5, coordinates.databaseUser());
            schema.executeUpdate();
        }
        new SchemaProvisioner(runtime.persistence().database()).create(identity);
        return identity;
    }

    /** Uses real independent writer or SELECT-only platform credentials. / 使用真实独立写者或仅SELECT平台凭据。
     * @param runtime actual platform / 实际平台
     * @param identity registered current target / 已登记当前目标
     * @param readOnly whether only SELECT is granted / 是否仅授予SELECT
     * @return actual restricted scope / 实际受限作用域
     * @throws Exception if original access cannot be opened / 原访问无法打开时 */
    private static SchemaAccessScope scope(PlatformRuntime runtime, SchemaIdentity identity, boolean readOnly) throws Exception {
        return new SchemaAccessScope(runtime.persistence().database(), identity, UUID.randomUUID(), readOnly);
    }
}
