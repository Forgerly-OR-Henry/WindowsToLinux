package gold.debug.windowstolinux.mod.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.swing.JComponent;

import gold.debug.windowstolinux.app.db.bootstrap.DatabaseProtection;
import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.schema.SchemaProvisioner;
import gold.debug.windowstolinux.app.db.schema.SchemaValidation;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.db.*;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModTest {
    static final String OWNER = DeploymentSchema.OWNER;

    static final String VERSION = "1.1.0-dev.1";

    static final String PASSWORD = "deployment-isolated-password";

    static final DatabaseProtection PROTECTION = new DatabaseProtection() {
        public byte[] protect(byte[] input) {
            byte[] output = input.clone();
            for (int i = 0; i < output.length; i++)
                output[i] ^= 0x67;
            return output;
        }

        public byte[] unprotect(byte[] input) {
            return protect(input);
        }
    };

    @TempDir
    Path root;

    Path workspace() throws Exception {
        Path path = Path.of("").toRealPath();
        while (path != null && !Files.isRegularFile(path.resolve("src/mod/deployment/mod.json")))
            path = path.getParent();
        return Objects.requireNonNull(path, "Actual reactor workspace is required");
    }

    PlatformRuntime open() throws Exception {
        var runtime = PlatformRuntime.open(root.resolve("data"), PROTECTION);
        if (runtime.security().securityState().configured())
            runtime.security().unlock(PASSWORD.toCharArray());
        else
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.setInteractionPresenter(new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerRef server,
                    TaskSnapshot task, CancellationToken cancellation) {
                throw new AssertionError("No remote interaction expected");
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskSnapshot task,
                    CancellationToken cancellation) {
                throw new AssertionError("No remote interaction expected");
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError("No remote interaction expected");
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancellationToken cancellation) {
                throw new AssertionError("No external action expected");
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                    CancellationToken cancellation) {
                throw new AssertionError("No input expected");
            }
        });
        runtime.setSourcePicker((title, task, cancel) -> {
            throw new AssertionError("No source selection expected");
        });
        runtime.started();
        return runtime;
    }

    void start(PlatformRuntime runtime, boolean enabled) throws Exception {
        var pages = new ModPageRegistry(new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {
                throw new AssertionError("No deployment page yet");
            }

            public void focus(ModPageRegistry.PageKey key) {
            }

            public void remove(ModPageRegistry.PageKey key) {
            }

            public void environment(UiEnvironment environment) {
            }
        }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
        var report = runtime.startDevelopmentMods(workspace(), pages);
        assertTrue(report.problems().isEmpty(), report.problems().toString());
        assertEquals(enabled ? Set.of(OWNER) : Set.of(), report.running());
    }

    void enable() throws Exception {
        try (var runtime = open()) {
            start(runtime, false);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM mod_schema"));
            assertEquals(1, scalar(runtime,
                    "SELECT COUNT(*) FROM mod_version WHERE mod_id='" + OWNER + "' AND origin_kind='CLASS'"));
            var intents = runtime.modIntents();
            intents.apply(
                    intents.review(OWNER, new PlatformModIntents.Choice(true, Optional.of(VERSION)), Optional.empty()),
                    false);
        }
    }

    SchemaAccessScope scope(PlatformRuntime runtime, boolean readOnly) throws Exception {
        return new SchemaAccessScope(runtime.persistence().database(),
                runtime.modContexts().get(OWNER).context().db().identity(), UUID.randomUUID(), readOnly);
    }

    long scalar(PlatformRuntime runtime, String query) throws Exception {
        try (var connection = runtime.persistence().database().openConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    @Test
    void fixedSchemaCanBeCreatedAndValidatedWithRestrictedAuthorConnections() throws Exception {
        try (var runtime = open()) {
            var database = runtime.persistence().database();
            var identity = new SchemaIdentity(OWNER, "1", UUID.randomUUID());
            var coordinates = new SchemaCoordinates(identity);
            try (var connection = database.openConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO mod_registry(mod_id,updated_at) VALUES('" + OWNER + "',1)");
                try (var insert = connection.prepareStatement(
                        "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES(?,?,?,?,?,'PREPARING',1)")) {
                    insert.setString(1, OWNER);
                    insert.setString(2, "1");
                    insert.setObject(3, identity.generation());
                    insert.setString(4, coordinates.physicalName());
                    insert.setString(5, coordinates.databaseUser());
                    insert.executeUpdate();
                }
            }
            new SchemaProvisioner(database).create(identity);
            try (var access = new SchemaAccessScope(database, identity, UUID.randomUUID(), false)) {
                new DeploymentSchema().initialize(access);
            }
            try (var access = new SchemaAccessScope(database, identity, UUID.randomUUID(), true)) {
                var report = new DeploymentSchema().validate(access);
                assertTrue(report.valid(), report.toString());
                assertNotNull(SchemaValidation.validate(access));
            }
        }
    }

    @Test
    void realClassModuleInitializesOnlyOwnedSchemaAndReusesDataAcrossBoots() throws Exception {
        enable();
        SchemaIdentity original;
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            original = context.db().identity();
            assertEquals(OWNER, original.ownerId());
            assertEquals("1", original.schemaVersion());
            assertEquals(1, scalar(runtime, "SELECT COUNT(*) FROM mod_schema WHERE state='READY' AND ever_used"));
            assertEquals(1, scalar(runtime, "SELECT COUNT(*) FROM log_category WHERE owner_id='" + OWNER
                    + "' AND target_table='deployment_log'"));
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='app' AND table_name='deployment_unit'"));
            var handle = context.tasks()
                    .submit(new TaskRequest("schema_acceptance", "Schema acceptance", List.of(), true), task -> {
                        try (var connection = context.db().openConnection(task);
                                var statement = connection.createStatement()) {
                            assertThrows(SQLException.class,
                                    () -> statement.executeQuery("SELECT * FROM app.mod_registry"));
                            assertEquals(1,
                                    statement.executeUpdate("INSERT INTO preference VALUES('acceptance','retained')"));
                        }
                        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of("saved"), Optional.empty());
                    });
            assertEquals(Outcome.SUCCEEDED,
                    handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            try (var access = scope(runtime, true);
                    var connection = access.openConnection();
                    var statement = connection.createStatement()) {
                assertTrue(new DeploymentSchema().validate(access).valid());
                assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM preference"));
                try (var rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=CURRENT_SCHEMA AND table_type='BASE TABLE'")) {
                    assertTrue(rows.next());
                    assertEquals(18, rows.getInt(1));
                }
            }
        }
        try (var runtime = open()) {
            start(runtime, true);
            assertEquals(original, runtime.modContexts().get(OWNER).context().db().identity());
            try (var access = scope(runtime, true);
                    var connection = access.openConnection();
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT preference_value FROM preference WHERE preference_key='acceptance'")) {
                assertTrue(rows.next());
                assertEquals("retained", rows.getString(1));
            }
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM mod_operation"));
        }
    }

    @Test
    void existingTargetIsNeverReinitializedOrAdopted() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            try (var access = scope(runtime, false);
                    var connection = access.openConnection();
                    var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO preference VALUES('retained','original')");
                assertThrows(SQLException.class, () -> new DeploymentSchema().initialize(access));
                try (var rows = statement.executeQuery("SELECT preference_value FROM preference")) {
                    assertTrue(rows.next());
                    assertEquals("original", rows.getString(1));
                }
                assertTrue(new DeploymentSchema().validate(access).valid());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DROP TABLE preference", "ALTER TABLE preference DROP COLUMN preference_value",
            "ALTER TABLE preference ADD extra_column INTEGER", "CREATE TABLE unexpected(id INTEGER)"})
    void damagedInventoryIsReportedWithoutRepair(String damage) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            try (var access = scope(runtime, false);
                    var connection = access.openConnection();
                    var statement = connection.createStatement()) {
                statement.execute(damage);
            }
            try (var access = scope(runtime, true)) {
                var report = new DeploymentSchema().validate(access);
                assertFalse(report.valid());
                assertFalse(report.violations().isEmpty());
                assertTrue(report.businessDigest().isEmpty());
                assertEquals(report, new DeploymentSchema().validate(access));
            }
        }
    }

    @Test
    void databaseConstraintsAndBusinessDigestBindingBothRejectInvalidRecords() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            try (var access = scope(runtime, false);
                    var connection = access.openConnection();
                    var statement = connection.createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "INSERT INTO deployment_unit_configuration_snapshot VALUES('unit',0,'a','1',1)"));
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "INSERT INTO deployment_unit_configuration_entry VALUES('absent',1,'port','NUMBER','80','SERVER')"));
                statement.executeUpdate("INSERT INTO deployment_unit_configuration_snapshot VALUES('unit',1,'"
                        + "a".repeat(64) + "','1',1)");
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "INSERT INTO deployment_unit_release_binding VALUES('unit','release',1,NULL,0)"));
                statement.executeUpdate("INSERT INTO deployment_unit_release_binding VALUES('unit','release',1,'"
                        + "b".repeat(64) + "',0)");
            }
            try (var access = scope(runtime, true)) {
                var report = new DeploymentSchema().validate(access);
                assertFalse(report.valid());
                assertEquals(List.of("Release configuration digests differ from their immutable snapshots"),
                        report.violations());
                assertTrue(report.businessDigest().isEmpty());
            }
        }
    }

    @Test
    void foreignOwnerAndUnsupportedVersionAreRejectedBeforeJdbcAccess() {
        for (var identity : List.of(new SchemaIdentity("example.other", "1", UUID.randomUUID()),
                new SchemaIdentity(OWNER, "2", UUID.randomUUID()))) {
            SchemaAccess access = new SchemaAccess() {
                public SchemaIdentity identity() {
                    return identity;
                }

                public Connection openConnection() {
                    throw new AssertionError("Must reject identity before opening JDBC");
                }
            };
            assertThrows(IllegalArgumentException.class, () -> new DeploymentSchema().initialize(access));
            assertThrows(IllegalArgumentException.class, () -> new DeploymentSchema().validate(access));
        }
    }
}
