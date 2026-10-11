package gold.debug.windowstolinux.mod.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.db.schema.SchemaChecker;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.schema.SchemaProvisioner;
import gold.debug.windowstolinux.appapi.db.schema.SchemaAccess;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModTest extends DeploymentRuntimeFixture {
    @Test
    void fixedSchemaCanBeCreatedAndValidatedWithRestrictedAuthorConnections() throws Exception {
        try (var runtime = open()) {
            var database = runtime.persistence().database();
            var identity = new SchemaIdentity(OWNER, DeploymentSchema.VERSION, UUID.randomUUID());
            var coordinates = new SchemaCoordinates(identity);
            try (var connection = database.openConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO mod_registry(mod_id,updated_at) VALUES('" + OWNER + "',1)");
                try (var insert = connection.prepareStatement(
                    "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES(?,?,?,?,?,'PREPARING',1)")) {
                    insert.setString(1, OWNER);
                    insert.setString(2, DeploymentSchema.VERSION);
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
                assertNotNull(SchemaChecker.validate(access));
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
            assertEquals(DeploymentSchema.VERSION, original.schemaVersion());
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
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of("saved"), Optional.empty());
                });
            assertEquals(TaskResult.SUCCEEDED,
                handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            try (var access = scope(runtime, true);
                 var connection = access.openConnection();
                 var statement = connection.createStatement()) {
                assertTrue(new DeploymentSchema().validate(access).valid());
                assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM preference"));
                try (var rows = statement.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema=CURRENT_SCHEMA AND table_type='BASE TABLE'")) {
                    var actual = new TreeSet<String>();
                    while (rows.next())
                        actual.add(rows.getString(1));
                    assertEquals(Set.of("deployment_unit", "managed_application", "managed_application_component",
                        "managed_application_dependency", "external_application",
                        "deployment_unit_configuration_snapshot", "deployment_unit_configuration_entry",
                        "deployment_unit_release_binding", "secret_revision",
                        "deployment_unit_release_secret_reference", "deployment_agent_task",
                        "deployment_agent_event", "deployment_project", "deployment_execution",
                        "deployment_release", "deployment_handoff", "preference", "deployment_log",
                        "deployment_management_operation", "deployment_operation_receipt"), actual);
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
                String digest = gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot
                    .create("unit", 1, "1", java.time.Instant.ofEpochMilli(1), List.of()).sha256();
                statement.executeUpdate(
                    "INSERT INTO deployment_unit_configuration_snapshot VALUES('unit',1,'" + digest + "','1',1)");
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
        String unsupportedVersion = Integer.toString(Integer.parseInt(DeploymentSchema.VERSION) + 1);
        for (var identity : List.of(new SchemaIdentity("example.other", DeploymentSchema.VERSION, UUID.randomUUID()),
            new SchemaIdentity(OWNER, unsupportedVersion, UUID.randomUUID()))) {
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
