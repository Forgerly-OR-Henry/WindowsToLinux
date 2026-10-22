package gold.debug.windowstolinux.app.db.init;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.h2.tools.RunScript;

/** Standalone targeted harness: only synthetic databases under a newly created temporary directory. */
public final class ServerOrderMaintenanceTest {
    private static final DatabaseProtection PROTECTION = new DatabaseProtection() {
        public byte[] protect(byte[] bytes) {
            var copy = bytes.clone();
            for (int index = 0; index < copy.length; index++)
                copy[index] ^= 0x51;
            return copy;
        }

        public byte[] unprotect(byte[] bytes) {
            return protect(bytes);
        }
    };

    private ServerOrderMaintenanceTest() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 4 && arguments[0].equals("--child")) {
            ServerOrderMaintenance.apply(Path.of(arguments[2]), Path.of(arguments[3]), PROTECTION, point -> {
                if (point.equals(arguments[1]))
                    Runtime.getRuntime().halt(73);
            });
            throw new AssertionError("The interruption hook was not reached");
        }
        var cases = new LinkedHashMap<String, Test>();
        cases.put("preservesRowsReferencesAndFormalSchema", ServerOrderMaintenanceTest::preservesRowsReferencesAndFormalSchema);
        cases.put("atomicPublicationKeepsBothFileObjectsGuarded", ServerOrderMaintenanceTest::atomicPublicationKeepsBothFileObjectsGuarded);
        cases.put("rejectsFingerprintAndStructureDrift", ServerOrderMaintenanceTest::rejectsFingerprintAndStructureDrift);
        cases.put("refusesActiveOwnersAndTamperedBackups", ServerOrderMaintenanceTest::refusesActiveOwnersAndTamperedBackups);
        cases.put("rejectsOriginalChangeBeforeAtomicPublication", ServerOrderMaintenanceTest::rejectsOriginalChangeBeforeAtomicPublication);
        cases.put("exceptionsRestoreOriginalBytes", ServerOrderMaintenanceTest::exceptionsRestoreOriginalBytes);
        cases.put("hardInterruptRecoveryBeforeAndAfterPublication", ServerOrderMaintenanceTest::hardInterruptRecoveryBeforeAndAfterPublication);
        var selected = arguments.length == 0 ? cases.keySet() : new LinkedHashSet<>(List.of(arguments[0].split(",")));
        if (!cases.keySet().containsAll(selected))
            throw new IllegalArgumentException("Unknown targeted harness case");
        var temporary = Files.createTempDirectory("server-order-fixture-").toRealPath();
        try {
            for (var name : selected) {
                cases.get(name).run(Files.createDirectory(temporary.resolve(name)));
                System.out.println("PASS " + name);
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static void atomicPublicationKeepsBothFileObjectsGuarded(Path temporary) throws Exception {
        var original = Files.writeString(temporary.resolve("original.bin"), "original", StandardOpenOption.CREATE_NEW);
        var staged = Files.writeString(temporary.resolve("staged.bin"), "validated", StandardOpenOption.CREATE_NEW);
        try (var previous = ServerOrderFiles.exclusive(original, true);
             var candidate = ServerOrderFiles.exclusive(staged, true)) {
            var before = ServerOrderFiles.hash(previous);
            var after = ServerOrderFiles.hash(candidate);
            rejects(() -> {
                try (var ignored = java.nio.channels.FileChannel.open(original, StandardOpenOption.WRITE)) {
                }
            });
            ServerOrderAtomicMove.replace(staged, original);
            check(before.equals(ServerOrderFiles.hash(previous)), "old guarded object was changed during rename");
            check(after.equals(ServerOrderFiles.hash(candidate)), "new guarded object was changed during rename");
            rejects(() -> {
                try (var ignored = java.nio.channels.FileChannel.open(original, StandardOpenOption.WRITE)) {
                }
            });
            check(!Files.exists(staged), "rename did not remove the staged name atomically");
        }
        check(Files.readString(original).equals("validated"), "the target does not contain the validated image");
    }

    private static void preservesRowsReferencesAndFormalSchema(Path temporary) throws Exception {
        var root = fixture(temporary.resolve("data"));
        var access = Files.readAllBytes(root.resolve(ServerOrderFiles.ACCESS));
        var originalAcl = Files.getFileAttributeView(root.resolve(ServerOrderFiles.DATABASE),
            java.nio.file.attribute.AclFileAttributeView.class).getAcl();
        var before = snapshot(root);
        var beforeHash = ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE));
        var backup = temporary.resolve("backup");
        ServerOrderMaintenance.apply(root, backup, PROTECTION, point -> {
        });
        check(Arrays.equals(access, Files.readAllBytes(root.resolve(ServerOrderFiles.ACCESS))), "bootstrap bytes changed");
        check(originalAcl.equals(Files.getFileAttributeView(root.resolve(ServerOrderFiles.DATABASE),
            java.nio.file.attribute.AclFileAttributeView.class).getAcl()), "original database ACL changed");
        var receipt = ServerOrderFiles.readReceipt(root, backup);
        check(receipt.getProperty("beforeHash").equals(beforeHash), "backup did not preserve the original file image");
        check(receipt.getProperty("state").equals("PUBLISHED"), "success receipt is missing");
        try (var material = DatabaseBootstrap.load(root, PROTECTION); var connection = ServerOrderData.open(root, material)) {
            var schema = new ServerOrderData();
            schema.validateNew(connection, List.of("a", "z"), before);
            ServerOrderData.history(connection, schema.newSha);
            try (var statement = connection.createStatement()) {
                rejects(() -> statement.executeUpdate("DELETE FROM app.server_profile WHERE server_profile_id='z'"));
                rejects(() -> statement.executeUpdate("UPDATE app.server_profile SET display_order=0 WHERE server_profile_id='z'"));
                rejects(() -> statement.executeUpdate("UPDATE app.server_profile SET display_order=-1 WHERE server_profile_id='z'"));
                rejects(() -> statement.executeUpdate("UPDATE app.server_profile SET display_order=NULL WHERE server_profile_id='z'"));
            }
        }
        // A completed result can be explicitly rolled back only while its bytes are still exact.
        // Do this on a second fresh fixture, because the extra SQL verification above itself opens H2.
        var rollbackRoot = fixture(temporary.resolve("rollback-data"));
        var rollbackHash = ServerOrderFiles.hash(rollbackRoot.resolve(ServerOrderFiles.DATABASE));
        var rollbackBackup = temporary.resolve("rollback-backup");
        ServerOrderMaintenance.apply(rollbackRoot, rollbackBackup, PROTECTION, point -> {
        });
        ServerOrderMaintenance.restore(rollbackRoot, rollbackBackup, PROTECTION);
        check(ServerOrderFiles.hash(rollbackRoot.resolve(ServerOrderFiles.DATABASE)).equals(rollbackHash), "restore lost original bytes");
        check(!Files.exists(rollbackBackup.resolve("work")), "work copy was not removed");
        var emptyRoot = fixture(temporary.resolve("empty-data"));
        try (var material = DatabaseBootstrap.load(emptyRoot, PROTECTION); var connection = ServerOrderData.open(emptyRoot, material);
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM fixture_mod.saved_record");
            statement.executeUpdate("DELETE FROM app.mod_server_reference");
            statement.executeUpdate("DELETE FROM app.server_profile");
        }
        ServerOrderMaintenance.apply(emptyRoot, temporary.resolve("empty-backup"), PROTECTION, point -> {
        });
        try (var material = DatabaseBootstrap.load(emptyRoot, PROTECTION); var connection = ServerOrderData.open(emptyRoot, material)) {
            check(ServerOrderData.ids(connection).isEmpty(), "empty server table was populated");
            PlatformSchemaList.validate(connection, new ServerOrderData().newDdl);
        }
    }

    private static void rejectsFingerprintAndStructureDrift(Path temporary) throws Exception {
        for (var variant : List.of("fingerprint", "extra-column", "unclean", "already-new")) {
            var root = fixture(temporary.resolve(variant));
            if (variant.equals("already-new")) {
                ServerOrderMaintenance.apply(root, temporary.resolve(variant + "-first-backup"), PROTECTION, point -> {
                });
            } else {
                try (var material = DatabaseBootstrap.load(root, PROTECTION); var connection = ServerOrderData.open(root, material);
                     var statement = connection.createStatement()) {
                    statement.execute(switch (variant) {
                        case "fingerprint" -> "UPDATE app.app_schema_history SET script_sha256=REPEAT('0',64)";
                        case "unclean" -> "UPDATE app.app_state SET shutdown_state='UNKNOWN'";
                        default -> "ALTER TABLE app.server_profile ADD extra_column INTEGER";
                    });
                }
            }
            var hash = ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE));
            rejects(() -> ServerOrderMaintenance.apply(root, temporary.resolve(variant + "-backup"), PROTECTION, point -> {
            }));
            check(hash.equals(ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE))), "rejected source was modified");
        }
    }

    private static void refusesActiveOwnersAndTamperedBackups(Path temporary) throws Exception {
        var root = fixture(temporary.resolve("data"));
        var missing = Files.createDirectory(temporary.resolve("missing-pair"));
        Files.write(missing.resolve(ServerOrderFiles.DATABASE), new byte[]{1});
        rejects(() -> ServerOrderMaintenance.apply(missing, temporary.resolve("missing-backup"), PROTECTION, point -> {
        }));
        check(!Files.exists(missing.resolve(ServerOrderFiles.ACCESS)), "missing bootstrap was silently recreated");
        try (var ownership = ServerOrderFiles.own(root)) {
            rejects(() -> ServerOrderMaintenance.apply(root, temporary.resolve("locked-backup"), PROTECTION, point -> {
            }));
            check(!Files.exists(temporary.resolve("locked-backup")), "backup created while another owner held the lock");
        }
        try (var material = DatabaseBootstrap.load(root, PROTECTION); var engine = ServerOrderData.open(root, material)) {
            rejects(() -> ServerOrderMaintenance.apply(root, temporary.resolve("engine-backup"), PROTECTION, point -> {
            }));
            check(!Files.exists(temporary.resolve("engine-backup").resolve(ServerOrderFiles.RECEIPT)),
                "an open non-platform engine was accepted");
        }
        var hash = ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE));
        var backup = temporary.resolve("tampered-backup");
        rejects(() -> ServerOrderMaintenance.apply(root, backup, PROTECTION, point -> {
            if (point.equals("after-backup")) {
                Files.write(backup.resolve(ServerOrderFiles.ACCESS), new byte[]{1}, StandardOpenOption.APPEND);
                throw new IOException("fixture injected failure");
            }
        }));
        rejects(() -> ServerOrderMaintenance.restore(root, backup, PROTECTION));
        check(hash.equals(ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE))), "tampered backup changed original");
    }

    private static void rejectsOriginalChangeBeforeAtomicPublication(Path temporary) throws Exception {
        var root = fixture(temporary.resolve("data"));
        var backup = temporary.resolve("backup");
        rejects(() -> ServerOrderMaintenance.apply(root, backup, PROTECTION, point -> {
            if (point.equals("before-publish")) {
                // Simulates a non-cooperating tool ignoring the platform instance lock.
                try (var material = DatabaseBootstrap.load(root, PROTECTION); var connection = ServerOrderData.open(root, material);
                     var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO app.desktop_preference(preference_key,preference_value) VALUES('external','retained')");
                }
            }
        }));
        try (var material = DatabaseBootstrap.load(root, PROTECTION); var connection = ServerOrderData.open(root, material);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT preference_value FROM app.desktop_preference WHERE preference_key='external'")) {
            check(rows.next() && rows.getString(1).equals("retained"), "unrecognized external change was overwritten");
            ServerOrderData.history(connection, ServerOrderData.OLD_SHA);
        }
        rejects(() -> ServerOrderMaintenance.restore(root, backup, PROTECTION));
    }

    private static void exceptionsRestoreOriginalBytes(Path temporary) throws Exception {
        for (var point : List.of("after-alter", "after-publish")) {
            var root = fixture(temporary.resolve(point));
            var hash = ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE));
            var access = Files.readAllBytes(root.resolve(ServerOrderFiles.ACCESS));
            var backup = temporary.resolve(point + "-backup");
            rejects(() -> ServerOrderMaintenance.apply(root, backup, PROTECTION, reached -> {
                if (point.equals(reached))
                    throw new IOException("fixture injected failure");
            }));
            check(hash.equals(ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE))), "exception retained a changed image");
            check(Arrays.equals(access, Files.readAllBytes(root.resolve(ServerOrderFiles.ACCESS))), "exception changed bootstrap");
            check(ServerOrderFiles.readReceipt(root, backup).getProperty("state").equals("FAILED"), "failure receipt missing");
        }
    }

    private static void hardInterruptRecoveryBeforeAndAfterPublication(Path temporary) throws Exception {
        for (var point : List.of("after-alter", "after-publish")) {
            var root = fixture(temporary.resolve(point));
            var before = ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE));
            var backup = temporary.resolve(point + "-backup");
            var java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
            var process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ServerOrderMaintenanceTest.class.getName(), "--child", point, root.toString(), backup.toString())
                .redirectErrorStream(true).start();
            try {
                check(process.waitFor(30, TimeUnit.SECONDS), "interruption fixture timed out");
                check(process.exitValue() == 73, "interruption fixture did not reach the expected maintenance stage");
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    check(process.waitFor(10, TimeUnit.SECONDS), "owned child process did not exit");
                }
            }
            var receipt = ServerOrderFiles.readReceipt(root, backup);
            if (point.equals("after-alter"))
                check(before.equals(ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE))), "staging crash modified source");
            else
                check(receipt.getProperty("afterHash").equals(ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE))),
                    "atomic publication left an intermediate image");
            ServerOrderMaintenance.restore(root, backup, PROTECTION);
            check(before.equals(ServerOrderFiles.hash(root.resolve(ServerOrderFiles.DATABASE))), "hard interruption restore was not byte exact");
            check(ServerOrderFiles.readReceipt(root, backup).getProperty("state").equals("RESTORED"), "recovery receipt missing");
        }
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
                ServerOrderData.resource("/server-order-before.sql"), StandardCharsets.UTF_8)));
            try (var insert = connection.prepareStatement("INSERT INTO app.app_state(singleton_id,database_generation,boot_id,shutdown_state,intent_revision,updated_at) VALUES(1,?,?,'CLEAN',0,17)")) {
                insert.setObject(1, material.generation());
                insert.setObject(2, UUID.randomUUID());
                insert.executeUpdate();
            }
            statement.executeUpdate("INSERT INTO app.app_schema_history VALUES(1,'" + ServerOrderData.OLD_SHA + "','READY',NULL,11,12)");
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
            statement.executeUpdate("INSERT INTO app.server_profile VALUES('z','shared','Production','z.test',22,'alice',"
                + "'z.test',22,'SSH_WIRE','SHA256:fixture','Ubuntu',1,17)");
            statement.executeUpdate("INSERT INTO app.server_profile VALUES('a','shared','Secondary','a.test',2222,'bob',"
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

    private static ServerOrderData.Snapshot snapshot(Path root) throws Exception {
        try (var material = DatabaseBootstrap.load(root, PROTECTION); var connection = ServerOrderData.open(root, material)) {
            return ServerOrderData.snapshot(connection);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    private static void rejects(Test operation) throws Exception {
        try {
            operation.run(null);
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("The unsafe operation was accepted");
    }

    private static void rejects(Operation operation) throws Exception {
        rejects(ignored -> operation.run());
    }

    @FunctionalInterface
    private interface Test {
        void run(Path temporary) throws Exception;
    }

    @FunctionalInterface
    private interface Operation {
        void run() throws Exception;
    }
}
