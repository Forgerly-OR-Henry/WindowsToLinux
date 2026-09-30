package gold.debug.windowstolinux.mod.deployment.db;

import static gold.debug.windowstolinux.mod.deployment.db.DeploymentSchemaMigration12Test.*;
import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.execution.schema.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

/** Verifies the real protected schema-2 source and independent schema-3 target. / 验证真实受保护结构2来源和独立结构3目标。 */
class DeploymentSchemaMigration23Test extends DeploymentRuntimeFixture {
    /** Preserves binary rows, metadata and source digest while creating an empty management journal. / 保留二进制原行、元数据和来源摘要并创建空管理账本。
     * @throws Exception if protected migration fails / 受保护迁移失败时 */
    @Test
    void preservesOriginalRowsAndSourceAcrossIndependentGenerations() throws Exception {
        try (var runtime = open()) {
            var source = register(runtime, "2");
            var target = register(runtime, "3");
            byte[] payload = new byte[2 * 1024 * 1024];
            Arrays.fill(payload, (byte) 0x6a);
            try (var access = DeploymentSchemaMigration12Test.scope(runtime, source, false);
                    var connection = access.openConnection();
                    var statement = connection.createStatement()) {
                for (var command : DeploymentSchema.resource("schema-v2.sql").split(";"))
                    if (!command.isBlank())
                        statement.executeUpdate(command);
                statement.executeUpdate("INSERT INTO preference VALUES('original','preserved')");
                statement.executeUpdate(
                        "INSERT INTO secret_revision VALUES('credential',3,'opaque',123,'DATABASE_AUTH')");
                statement.execute("ALTER TABLE deployment_agent_event ALTER COLUMN event_id RESTART WITH 2000");
                try (var insert = connection.prepareStatement(
                        "INSERT INTO deployment_execution(execution_id,platform_task_id,server_profile_id,mode,phase,reviewed_revision,request_payload,recovery_payload,created_at,updated_at) VALUES(?,?,'server','STATIC','PREPARED',?,?,?,1,1)")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, UUID.randomUUID());
                    insert.setString(3, "a".repeat(64));
                    insert.setBytes(4, payload);
                    insert.setBytes(5, new byte[]{1, 2, 3});
                    assertEquals(1, insert.executeUpdate());
                }
            }
            var before = SchemaSnapshot.digest(runtime.persistence().database(), new SchemaCoordinates(source));
            try (var reader = DeploymentSchemaMigration12Test.scope(runtime, source, true);
                    var writer = DeploymentSchemaMigration12Test.scope(runtime, target, false)) {
                new DeploymentSchemaMigration23().migrate(reader, writer);
            }
            assertEquals(before,
                    SchemaSnapshot.digest(runtime.persistence().database(), new SchemaCoordinates(source)));
            try (var access = DeploymentSchemaMigration12Test.scope(runtime, target, true);
                    var connection = access.openConnection();
                    var statement = connection.createStatement()) {
                assertEquals(19, DeploymentSchemaMigration.inventory(connection, "3").size());
                try (var rows = statement
                        .executeQuery("SELECT request_payload,recovery_payload FROM deployment_execution")) {
                    assertTrue(rows.next());
                    assertArrayEquals(payload, rows.getBytes(1));
                    assertArrayEquals(new byte[]{1, 2, 3}, rows.getBytes(2));
                }
                try (var rows = statement.executeQuery("SELECT purpose FROM secret_revision")) {
                    assertTrue(rows.next());
                    assertEquals("DATABASE_AUTH", rows.getString(1));
                }
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_management_operation")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
                try (var rows = statement.executeQuery(
                        "SELECT identity_base FROM information_schema.columns WHERE UPPER(table_schema)=UPPER(CURRENT_SCHEMA) AND UPPER(table_name)='DEPLOYMENT_AGENT_EVENT' AND UPPER(column_name)='EVENT_ID'")) {
                    assertTrue(rows.next());
                    assertEquals(2000, rows.getLong(1));
                }
            }
        }
    }

    /** Rejects a mutated original inventory before writing any target table. / 在写任一目标表前拒绝已变更来源清单。
     * @throws Exception if the real account fixture fails / 真实账户夹具失败时 */
    @Test
    void rejectsUnexpectedSourceColumnsWithoutCreatingTargetTables() throws Exception {
        try (var runtime = open()) {
            var source = register(runtime, "2");
            var target = register(runtime, "3");
            try (var access = DeploymentSchemaMigration12Test.scope(runtime, source, false);
                    var connection = access.openConnection();
                    var statement = connection.createStatement()) {
                for (var command : DeploymentSchema.resource("schema-v2.sql").split(";"))
                    if (!command.isBlank())
                        statement.executeUpdate(command);
                statement.execute("ALTER TABLE preference ADD unexpected INTEGER");
            }
            var before = SchemaSnapshot.digest(runtime.persistence().database(), new SchemaCoordinates(source));
            try (var reader = DeploymentSchemaMigration12Test.scope(runtime, source, true);
                    var writer = DeploymentSchemaMigration12Test.scope(runtime, target, false)) {
                assertThrows(SQLException.class, () -> new DeploymentSchemaMigration23().migrate(reader, writer));
            }
            assertEquals(before,
                    SchemaSnapshot.digest(runtime.persistence().database(), new SchemaCoordinates(source)));
            try (var access = DeploymentSchemaMigration12Test.scope(runtime, target, true);
                    var connection = access.openConnection();
                    var statement = connection.prepareStatement(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE'")) {
                statement.setString(1, connection.getSchema());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
        }
    }
}
