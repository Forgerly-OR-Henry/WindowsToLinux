package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.execution.schema.*;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;

/** Exercises the real restricted source reader and independent writer across the exact schema-1 to schema-2 edge. / 以真实受限来源读者和独立目标写者验证精确结构1至结构2迁移。 */
class DeploymentSchemaMigration12Test extends DeploymentRuntimeFixture {
    /** Does not invent purposes for legacy revisions or reuse deleted identity values. / 不臆造旧修订用途或重用已删除身份。
     * @throws Exception if the real H2 migration cannot finish / 真实H2迁移无法完成时 */
    @Test
    void preservesLegacyRowsSourceDigestAndDeletedEventSequence() throws Exception {
        try (var runtime = open()) {
            var source = register(runtime, "1");
            var target = register(runtime, "2");
            initializeLegacy(runtime, source);
            try (var access = scope(runtime, source, false);
                    var connection = access.openConnection();
                    var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO secret_revision VALUES('credential-name',7,'opaque-7',123)");
                statement.executeUpdate("INSERT INTO preference VALUES('legacy','preserved')");
                statement.executeUpdate(
                        "INSERT INTO deployment_agent_task VALUES('task','server','target','config','STATIC','MANUAL_REVIEW','skill','approval',1,'DONE',1,1)");
                statement.execute("ALTER TABLE deployment_agent_event ALTER COLUMN event_id RESTART WITH 1000");
                statement.executeUpdate(
                        "INSERT INTO deployment_agent_event(task_id,event_type,event_detail,created_at) VALUES('task','DONE','safe',1)");
                statement.executeUpdate("DELETE FROM deployment_agent_event WHERE event_id=1000");
            }
            var before = SchemaSnapshot.digest(runtime.persistence().database(), new SchemaCoordinates(source));
            migrate(runtime, source, target);
            assertEquals(before,
                    SchemaSnapshot.digest(runtime.persistence().database(), new SchemaCoordinates(source)));
            try (var access = scope(runtime, target, true);
                    var connection = access.openConnection();
                    var statement = connection.createStatement()) {
                assertFalse(DeploymentSchemaMigration.inventory(connection, "2").isEmpty());
                try (var row = statement.executeQuery(
                        "SELECT credential_id,purpose FROM secret_revision WHERE secret_id='credential-name'")) {
                    assertTrue(row.next());
                    assertEquals("opaque-7", row.getString(1));
                    assertNull(row.getString(2));
                }
                assertTrue(DeploymentSecretRepository.readRevision(connection,
                        new gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretReference(
                                "credential-name", 7))
                        .orElseThrow().purpose().isEmpty());
                try (var row = statement.executeQuery(
                        "SELECT identity_base FROM information_schema.columns WHERE UPPER(table_schema)=UPPER(CURRENT_SCHEMA) AND UPPER(table_name)='DEPLOYMENT_AGENT_EVENT' AND UPPER(column_name)='EVENT_ID'")) {
                    assertTrue(row.next());
                    assertTrue(row.getLong(1) >= 1001);
                }
                try (var row = statement
                        .executeQuery("SELECT preference_value FROM preference WHERE preference_key='legacy'")) {
                    assertTrue(row.next());
                    assertEquals("preserved", row.getString(1));
                }
            }
        }
    }

    /** Streams a larger original BLOB without using its bytes as a migration schema decision. / 流式复制较大原BLOB，不将其字节当作迁移结构决策。
     * @throws Exception if the restricted copy fails / 受限复制失败时 */
    @Test
    void copiesOriginalBinaryPayloadThroughRestrictedAccounts() throws Exception {
        try (var runtime = open()) {
            var source = register(runtime, "1");
            var target = register(runtime, "2");
            initializeLegacy(runtime, source);
            byte[] payload = new byte[2 * 1024 * 1024];
            Arrays.fill(payload, (byte) 0x4b);
            try (var access = scope(runtime, source, false);
                    var connection = access.openConnection();
                    var insert = connection.prepareStatement(
                            "INSERT INTO deployment_execution(execution_id,platform_task_id,server_profile_id,mode,phase,reviewed_revision,request_payload,recovery_payload,created_at,updated_at) VALUES(?,?,'server','STATIC','PREPARED',?,?,?,1,1)")) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, UUID.randomUUID());
                insert.setString(3, "a".repeat(64));
                insert.setBytes(4, payload);
                insert.setBytes(5, new byte[]{1, 2, 3});
                assertEquals(1, insert.executeUpdate());
            }
            migrate(runtime, source, target);
            try (var access = scope(runtime, target, true);
                    var connection = access.openConnection();
                    var statement = connection.createStatement();
                    var row = statement.executeQuery("SELECT request_payload FROM deployment_execution")) {
                assertTrue(row.next());
                assertArrayEquals(payload, row.getBytes(1));
            }
        }
    }

    /** Registers a real generation under the platform's restricted schema scope. / 在平台受限结构作用域下登记真实代次。
     * @param runtime real platform / 真实平台
     * @param version exact generation version / 精确代次版本
     * @return registered identity / 已登记身份
     * @throws Exception if platform registration fails / 平台登记失败时 */
    static SchemaIdentity register(PlatformRuntime runtime, String version) throws Exception {
        var identity = new SchemaIdentity(OWNER, version, UUID.randomUUID());
        var coordinates = new SchemaCoordinates(identity);
        try (var connection = runtime.persistence().database().openConnection();
                var registry = connection
                        .prepareStatement("MERGE INTO mod_registry(mod_id,updated_at) KEY(mod_id) VALUES(?,1)");
                var schema = connection.prepareStatement(
                        "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES(?,?,?,?,?,'PREPARING',1)")) {
            registry.setString(1, OWNER);
            registry.executeUpdate();
            schema.setString(1, OWNER);
            schema.setString(2, version);
            schema.setObject(3, identity.generation());
            schema.setString(4, coordinates.physicalName());
            schema.setString(5, coordinates.databaseUser());
            schema.executeUpdate();
        }
        new SchemaProvisioner(runtime.persistence().database()).create(identity);
        return identity;
    }

    /** Creates the unchanged original schema resource using the original writer. / 用原写者建立未修改的旧结构资源。
     * @param runtime real platform / 真实平台
     * @param source original source identity / 原来源身份
     * @throws Exception if original DDL fails / 原DDL失败时 */
    private static void initializeLegacy(PlatformRuntime runtime, SchemaIdentity source) throws Exception {
        try (var access = scope(runtime, source, false);
                var connection = access.openConnection();
                var statement = connection.createStatement()) {
            for (var command : DeploymentSchema.resource("schema-v1.sql").split(";"))
                if (!command.isBlank())
                    statement.executeUpdate(command);
        }
    }

    /** Invokes the real migration against independently restricted source and target generations. / 针对独立受限的来源与目标代次运行真实迁移。
     * @param runtime real platform / 真实平台
     * @param source original version 1 / 原版本1
     * @param target independent version 2 / 独立版本2
     * @throws Exception if migration fails / 迁移失败时 */
    private static void migrate(PlatformRuntime runtime, SchemaIdentity source, SchemaIdentity target)
            throws Exception {
        try (var reader = scope(runtime, source, true); var writer = scope(runtime, target, false)) {
            new DeploymentSchemaMigration12().migrate(reader, writer);
        }
    }

    /** Creates an actual platform scope rather than an unrestricted JDBC stand-in. / 创建真实平台作用域而非无限制JDBC替身。
     * @param runtime actual runtime / 实际运行时
     * @param identity registered generation / 已登记代次
     * @param readOnly SELECT-only source mode / 来源仅SELECT模式
     * @return real restricted access / 真实受限访问
     * @throws Exception if the scope cannot be opened / 作用域无法打开时 */
    static SchemaAccessScope scope(PlatformRuntime runtime, SchemaIdentity identity, boolean readOnly)
            throws Exception {
        return new SchemaAccessScope(runtime.persistence().database(), identity, UUID.randomUUID(), readOnly);
    }
}
