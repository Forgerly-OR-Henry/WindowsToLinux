package gold.debug.windowstolinux.app.db.init;

import java.sql.*;
import java.util.*;

import org.h2.tools.RunScript;

/** Initializes the current platform schema through a borrowed connection. / 通过借用连接初始化当前平台结构。 */
public final class PlatformDatabaseInitializer {
    /** Prevents construction. / 禁止构造。 */
    private PlatformDatabaseInitializer() {
    }

    /**
     * Initializes once and validates existing identity before updating the boot marker. / 仅首次初始化，并在更新启动标记前校验已有身份。
     * @throws Exception if schema or protected identity does not match / 数据结构或受保护身份不匹配时
     * @param connection borrowed engine connection / 借用引擎连接
     * @param fresh whether this is a new protected database / 是否为新受保护数据库
     * @param generation protected database identity / 受保护数据库身份
     * @param bootId original boot identity / 原启动身份
     * @param initialPreferences initial values for absent keys / 缺失键初始值
     * @param modLoading whether startup validates Mod records / 启动是否校验 Mod 记录
     */
    public static void initialize(Connection connection, boolean fresh, UUID generation, UUID bootId,
                                  boolean modLoading, Map<String, String> initialPreferences) throws Exception {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(generation);
        Objects.requireNonNull(bootId);
        Objects.requireNonNull(initialPreferences);
        byte[] ddl;
        try (var stream = Objects.requireNonNull(
            PlatformDatabaseInitializer.class.getResourceAsStream("/gold/debug/windowstolinux/app/db/schema/platform-v1.sql"))) {
            ddl = stream.readAllBytes();
        }
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(ddl));
        if (fresh) {
            try (var s = connection.createStatement()) {
                s.execute("CREATE SCHEMA app AUTHORIZATION app_internal");
            }
            connection.setSchema("app");
            RunScript.execute(connection,
                new java.io.StringReader(new String(ddl, java.nio.charset.StandardCharsets.UTF_8)));
            try (var p = connection.prepareStatement(
                "INSERT INTO app_state(singleton_id,database_generation,boot_id,shutdown_state,intent_revision,updated_at) VALUES(1,?,?,'STARTING',0,?)")) {
                p.setObject(1, generation);
                p.setObject(2, bootId);
                p.setLong(3, System.currentTimeMillis());
                p.executeUpdate();
            }
            try (var p = connection.prepareStatement(
                "INSERT INTO app_schema_history(schema_version,script_sha256,state,started_at,ended_at) VALUES(1,?,'READY',?,?)")) {
                p.setString(1, digest);
                p.setLong(2, System.currentTimeMillis());
                p.setLong(3, System.currentTimeMillis());
                p.executeUpdate();
            }
        } else {
            connection.setSchema("app");
            try (var s = connection.createStatement();
                 var r = s.executeQuery("SELECT database_generation FROM app_state WHERE singleton_id=1")) {
                if (!r.next() || !generation.equals(r.getObject(1, UUID.class)))
                    throw new SQLException("Protected database identity mismatch");
            }
            try (var s = connection.createStatement();
                 var r = s.executeQuery(
                     "SELECT schema_version,script_sha256,state FROM app_schema_history ORDER BY schema_version DESC")) {
                if (!r.next() || r.getInt(1) != 1 || !r.getString(2).equals(digest) || !r.getString(3).equals("READY"))
                    throw gold.debug.windowstolinux.app.db.error.DesktopStorageException.create(
                        gold.debug.windowstolinux.app.db.error.DesktopStorageFailureType.SCHEMA_INCOMPATIBLE,
                        "Unsupported or incomplete platform schema", null);
            }
            PlatformSchemaList.validate(connection, ddl);
            connection.setAutoCommit(false);
            try {
                try (var p = connection.prepareStatement(
                    "UPDATE app_state SET boot_id=?,shutdown_state='STARTING',updated_at=? WHERE singleton_id=1")) {
                    p.setObject(1, bootId);
                    p.setLong(2, System.currentTimeMillis());
                    p.executeUpdate();
                }
                try (var s = connection.createStatement()) {
                    retainOriginalCancellation(connection);
                    s.executeUpdate(
                        "UPDATE platform_task SET state='UNKNOWN',outcome=NULL,ended_at=NULL WHERE state<>'ENDED' AND NOT(state='FINISHING' AND outcome IS NOT NULL AND ended_at IS NOT NULL)");
                    if (modLoading) {
                        s.executeUpdate("UPDATE mod_operation SET state='UNKNOWN',updated_at=" + System.currentTimeMillis()
                            + " WHERE operation_type IN ('ENABLE','DISABLE','SWITCH') AND state='RUNNING' AND phase='APPLYING' AND outcome IS NULL");
                        s.executeUpdate(
                            "UPDATE mod_schema SET state='UNKNOWN' WHERE state='PREPARING' AND generation IN (SELECT target_generation FROM mod_operation WHERE operation_type='MIGRATE' AND outcome IS NULL)");
                        s.executeUpdate("UPDATE mod_operation SET state='UNKNOWN',updated_at=" + System.currentTimeMillis()
                            + " WHERE operation_type='MIGRATE' AND state='RUNNING' AND outcome IS NULL");
                        s.executeUpdate("UPDATE mod_migration_step SET state='UNKNOWN' WHERE state='RUNNING'");
                        s.executeUpdate(
                            "UPDATE mod_operation SET state='UNKNOWN' WHERE operation_type='RECONCILE' AND state='RUNNING' AND outcome IS NULL");
                        s.executeUpdate(
                            "UPDATE mod_operation SET state='UNKNOWN' WHERE operation_type IN ('INSTALL','REINSTALL') AND state='RUNNING' AND outcome IS NULL");
                        s.executeUpdate(
                            "UPDATE mod_operation SET state='UNKNOWN' WHERE operation_type IN ('DELETE_VERSION','DELETE_MOD') AND state='RUNNING' AND outcome IS NULL");
                    }
                }
                connection.commit();
            } catch (SQLException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        if (fresh) {
            try (var p = connection.prepareStatement("MERGE INTO desktop_preference(preference_key,preference_value) KEY(preference_key) VALUES(?,?)")) {
                for (var preference : initialPreferences.entrySet()) {
                    p.setString(1, preference.getKey());
                    p.setString(2, preference.getValue());
                    p.addBatch();
                }
                p.executeBatch();
            }
        }
    }

    /** Preserves known legacy cancellation before nonterminal work becomes UNKNOWN on startup. / 启动将非终态工作改为UNKNOWN前保留已知旧取消。
     * @param connection original startup transaction / 原启动事务
     * @throws SQLException if original cancellation metadata cannot be retained / 原取消元数据无法保留时 */
    private static void retainOriginalCancellation(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("SELECT task_id,recovery_json FROM platform_task WHERE state='CANCEL_REQUESTED'");
             var rows = query.executeQuery();
             var update = connection.prepareStatement("UPDATE platform_task SET recovery_json=? WHERE task_id=? AND state='CANCEL_REQUESTED'")) {
            while (rows.next()) {
                update.setString(1, gold.debug.windowstolinux.app.db.task.metadata.TaskCancellationDocument.request(rows.getString(2)));
                update.setObject(2, rows.getObject(1, UUID.class));
                if (update.executeUpdate() != 1) throw new SQLException("Original cancellation changed during startup");
            }
        }
    }
}
