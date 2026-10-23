package gold.debug.windowstolinux.app.db.init;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.h2.tools.RunScript;

/** Synthetic fixture validation of the pinned AI transformation and shared atomic lifecycle. */
public final class AiModelMaintenanceTest {
    private static final DatabaseProtection PROTECTION = new DatabaseProtection() {
        public byte[] protect(byte[] bytes) {
            var copy = bytes.clone();
            for (int i = 0; i < copy.length; i++) copy[i] ^= 0x51;
            return copy;
        }
        public byte[] unprotect(byte[] bytes) { return protect(bytes); }
    };

    public static void main(String[] arguments) throws Exception {
        var root = Files.createTempDirectory("ai-model-maintenance-test-").toRealPath();
        try {
            String selected = arguments.length == 0 ? "all" : arguments[0];
            if (Set.of("all", "split").contains(selected)) split(root);
            if (Set.of("all", "failure").contains(selected)) failure(root);
            if (Set.of("all", "guards").contains(selected)) guards(root);
            System.out.println("PASSED AI maintenance " + selected);
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void split(Path temporary) throws Exception {
        var root = fixture(temporary.resolve("split-data"));
        try (var material = DatabaseBootstrap.load(root, PROTECTION); var connection = ServerOrderData.open(root, material);
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO ai_model(model_id,credential_id,model_name,display_name,display_order,configuration_revision,"
                + "endpoint_url,file_endpoint_url,realtime_endpoint_url,is_language_enabled,language_call_order,is_approval_enabled,approval_call_order,"
                + "is_vision_enabled,vision_call_order,is_voice_enabled,voice_call_order,text_verified_revision,text_state,text_verified_at,"
                + "voice_file_verified_revision,voice_file_state,voice_file_verified_at,voice_realtime_verified_revision,voice_realtime_state,voice_realtime_verified_at)"
                + " VALUES('alpha','shared','model','Same name',0,4,'https://example.test/chat','https://example.test/file','wss://example.test/live',"
                + "1,0,1,0,1,0,1,0,4,'AVAILABLE',31,4,'FAILED',32,3,'AVAILABLE',33)");
            statement.executeUpdate("INSERT INTO ai_model(model_id,credential_id,model_name,display_name,display_order,endpoint_url,file_endpoint_url,"
                + "is_language_enabled,language_call_order,is_voice_enabled,voice_call_order) VALUES('beta','shared','model','Same name',1,"
                + "'https://example.test/same','https://example.test/same',0,1,1,1)");
            statement.executeUpdate("INSERT INTO ai_model(model_id,credential_id,model_name,display_name,display_order,realtime_endpoint_url,"
                + "is_language_enabled,language_call_order,is_voice_enabled,voice_call_order) VALUES('gamma','shared','model','Same name',2,"
                + "'wss://example.test/gamma?intent=transcription',1,2,1,2)");
            statement.executeUpdate("INSERT INTO desktop_preference VALUES('ai_model_protocol_alpha','OPENAI_RESPONSES')");
            statement.executeUpdate("INSERT INTO desktop_preference VALUES('ai_model_tool_check_alpha','4,AVAILABLE,31,')");
        }
        var backup = temporary.resolve("split-backup");
        AiModelMaintenance.apply(root, backup, PROTECTION, point -> {});
        check(ServerOrderFiles.readReceipt(root, backup).getProperty("state").equals("PUBLISHED"), "missing published receipt");
        try (var material = DatabaseBootstrap.load(root, PROTECTION); var connection = ServerOrderData.open(root, material)) {
            var rows = AiModelData.records(connection);
            check(rows.stream().map(row -> row.get("model_id")).toList().equals(List.of("alpha", "alpha-endpoint-2", "alpha-endpoint-3", "beta", "gamma")), "split order mismatch");
            check(rows.get(2).get("endpoint_url").equals("wss://example.test/live?intent=transcription"), "legacy actual URL not retained");
            check(rows.get(0).get("text_state").equals("AVAILABLE"), "matching text evidence lost");
            check(rows.get(1).get("text_state").equals("UNVERIFIED"), "unrelated evidence copied");
            check(rows.get(1).get("voice_file_state").equals("FAILED"), "failed evidence changed");
            check(rows.get(2).get("voice_realtime_verified_revision").equals(3L), "stale evidence became current");
            check(rows.get(3).get("language_call_order") == null, "old disabled member retained");
            check(rows.get(4).get("language_call_order").equals(1), "invalid missing endpoint member was lost");
            check(rows.get(0).get("voice_call_order") == null && rows.get(1).get("voice_call_order").equals(0)
                && rows.get(2).get("voice_call_order").equals(1) && rows.get(3).get("voice_call_order").equals(2), "voice expansion mismatch");
            check(rows.stream().allMatch(row -> row.get("credential_id").equals("shared")), "shared opaque credential changed");
            try (var statement = connection.createStatement(); var preferences = statement.executeQuery("SELECT COUNT(*) FROM desktop_preference WHERE preference_key LIKE 'ai_model_protocol_%'")) {
                preferences.next();
                check(preferences.getInt(1) == 3, "protocol settings not copied to split records");
            }
            PlatformSchemaList.validate(connection, new AiModelData().newDdl);
            ServerOrderData.history(connection, new AiModelData().newSha);
        }
        var rollback = fixture(temporary.resolve("restore-data"));
        var hash = ServerOrderFiles.hash(rollback.resolve(ServerOrderFiles.DATABASE));
        var restoreBackup = temporary.resolve("restore-backup");
        AiModelMaintenance.apply(rollback, restoreBackup, PROTECTION, point -> {});
        AiModelMaintenance.restore(rollback, restoreBackup, PROTECTION);
        check(hash.equals(ServerOrderFiles.hash(rollback.resolve(ServerOrderFiles.DATABASE))), "restore is not byte exact");
    }

    private static void failure(Path temporary) throws Exception {
        for (var point : List.of("after-backup", "after-alter", "before-publish", "after-publish")) {
            var root = fixture(temporary.resolve(point));
            var hash = ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE));
            var access = ServerOrderFiles.hash(root.resolve(ServerOrderFiles.ACCESS));
            rejects(() -> AiModelMaintenance.apply(root, temporary.resolve(point + "-backup"), PROTECTION, at -> {
                if (at.equals(point)) throw new java.io.IOException("injected fixture interruption");
            }));
            check(hash.equals(ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE))), "failed operation changed original image");
            check(access.equals(ServerOrderFiles.hash(root.resolve(ServerOrderFiles.ACCESS))), "failed operation changed bootstrap");
        }
    }

    private static void guards(Path temporary) throws Exception {
        var root = fixture(temporary.resolve("locked"));
        try (var ownership = ServerOrderFiles.own(root)) {
            rejects(() -> AiModelMaintenance.apply(root, temporary.resolve("locked-backup"), PROTECTION, point -> {}));
        }
        var drift = fixture(temporary.resolve("drift"));
        try (var material = DatabaseBootstrap.load(drift, PROTECTION); var connection = ServerOrderData.open(drift, material);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE app_schema_history SET script_sha256=REPEAT('0',64)");
        }
        var hash = ServerOrderFiles.hash(drift.resolve(ServerOrderFiles.DATABASE));
        rejects(() -> AiModelMaintenance.apply(drift, temporary.resolve("drift-backup"), PROTECTION, point -> {}));
        check(hash.equals(ServerOrderFiles.hash(drift.resolve(ServerOrderFiles.DATABASE))), "drift refusal changed original image");
    }

    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private interface Operation { void run() throws Exception; }
    private static void rejects(Operation operation) throws Exception {
        try { operation.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Expected refusal");
    }
    private static Path fixture(Path requested) throws Exception {
        var root = Files.createDirectory(requested).toRealPath();
        try (var material = DatabaseBootstrap.load(root, PROTECTION);
             var connection = DriverManager.getConnection("jdbc:h2:" + root.resolve("windowstolinux")
                     + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE;WRITE_DELAY=0;TRACE_LEVEL_FILE=0",
                 "app_internal", material.password()); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA app AUTHORIZATION app_internal");
            connection.setSchema("app");
            RunScript.execute(connection, new java.io.StringReader(new String(
                ServerOrderData.resource("/server-order-after.sql"), StandardCharsets.UTF_8)));
            try (var insert = connection.prepareStatement("INSERT INTO app.app_state(singleton_id,database_generation,boot_id,shutdown_state,intent_revision,updated_at) VALUES(1,?,?,'CLEAN',0,17)")) {
                insert.setObject(1, material.generation());
                insert.setObject(2, UUID.randomUUID());
                insert.executeUpdate();
            }
            statement.executeUpdate("INSERT INTO app.app_schema_history VALUES(1,'" + AiModelData.OLD_SHA + "','READY',NULL,11,12)");
            try (var insert = connection.prepareStatement("INSERT INTO app.security_profile VALUES(1,0,1,'ARGON2ID_V1',?,'fixture','fixture',"
                + "'AES256_GCM_V1',?,?)")) {
                var random = new java.security.SecureRandom();
                var values = List.of(new byte[16], new byte[12], new byte[48]);
                try {
                    for (int index = 0; index < values.size(); index++) {
                        random.nextBytes(values.get(index));
                        insert.setBytes(index + 1, values.get(index));
                    }
                    insert.executeUpdate();
                } finally {
                    values.forEach(bytes -> Arrays.fill(bytes, (byte) 0));
                }
            }
            statement.executeUpdate("INSERT INTO app.credential VALUES('shared',1,'PLAIN_V1',NULL,X'CAFE')");
            statement.executeUpdate("INSERT INTO app.server_profile VALUES('z','shared','Production',0,'z.test',22,'alice',"
                + "'z.test',22,'SSH_WIRE','SHA256:fixture','Ubuntu',1,17)");
            statement.executeUpdate("INSERT INTO app.server_profile VALUES('a','shared','Secondary',1,'a.test',2222,'bob',"
                + "NULL,NULL,NULL,NULL,NULL,NULL,NULL)");
            statement.executeUpdate("INSERT INTO app.mod_registry(mod_id,updated_at) VALUES('fixture.mod',17)");
            var generation = UUID.randomUUID();
            statement.executeUpdate("INSERT INTO app.mod_schema(mod_id,schema_version,generation,physical_name,database_user,"
                + "state,validated_at,created_at) VALUES('fixture.mod','1','" + generation
                + "','fixture_mod','fixture_user','READY',16,17)");
            statement.executeUpdate("INSERT INTO app.mod_server_reference VALUES(RANDOM_UUID(),'fixture.mod','1','" + generation
                + "','original','z','UNKNOWN',17)");
            statement.executeUpdate("INSERT INTO app.mod_credential_reference VALUES(RANDOM_UUID(),'fixture.mod','1','" + generation
                + "','original','shared','fixture','UNKNOWN',17)");
            statement.execute("CREATE SCHEMA fixture_mod");
            statement.execute("CREATE TABLE fixture_mod.saved_record(id INTEGER PRIMARY KEY,server_id VARCHAR NOT NULL,"
                + "body CLOB,payload BLOB,CONSTRAINT original_server_fk FOREIGN KEY(server_id) REFERENCES app.server_profile(server_profile_id))");
            statement.execute("CREATE INDEX fixture_mod.original_server_index ON fixture_mod.saved_record(server_id)");
            statement.executeUpdate("INSERT INTO fixture_mod.saved_record VALUES(7,'z','retained synthetic record',X'0001FF')");
            statement.execute("CREATE USER fixture_user PASSWORD 'synthetic-fixture-only'");
            statement.execute("GRANT SELECT ON fixture_mod.saved_record TO fixture_user");
            statement.executeUpdate("INSERT INTO app.desktop_preference VALUES('fixture','retained')");
            statement.executeUpdate("INSERT INTO app.platform_task VALUES(RANDOM_UUID(),'fixture.mod','1.0.0',RANDOM_UUID(),"
                + "CAST(RANDOM_UUID() AS CHAR(36)),'fixture',TRUE,'{}','{}','retained','UNKNOWN',NULL,NULL,17,NULL)");
        }
        return root;
    }

}
