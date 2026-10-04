package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentPayloadCheckerTest extends DeploymentRuntimeFixture {
    @FunctionalInterface
    interface SqlWork {
        void run(Connection connection) throws Exception;
    }

    void write(PlatformRuntime runtime, SqlWork work) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var failure = new AtomicReference<Throwable>();
        var handle = context.tasks()
            .submit(new TaskRequest("payload_acceptance", "Payload acceptance", List.of(), true), task -> {
                try (var connection = context.db().openConnection(task)) {
                    work.run(connection);
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError error) {
                    failure.set(error);
                    throw error;
                }
            });
        var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
        if (failure.get() != null)
            throw new AssertionError("Fixture SQL failed", failure.get());
        assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
    }

    void seed(PlatformRuntime runtime) throws Exception {
        write(runtime, connection -> {
            var health = new DeploymentHealthCheck.Tcp(8080, 20, 2);
            var definition = new DeploymentRuntimeDefinition.NodeService(22, health);
            var unit = new DeploymentUnitRuntimeConfig(health, Optional.empty(), definition.identityPolicy(),
                definition.workload());
            try (var statement = connection.prepareStatement(
                """
                    INSERT INTO deployment_unit(deployment_unit_id,server_profile_id,systemd_unit_name,release_root_path,ownership_manifest_sha256,runtime_payload)
                    VALUES('unit','server','windowstolinux-unit.service','/opt/windowstolinux/apps/unit',?,?)
                    """)) {
                statement.setString(1, "a".repeat(64));
                statement.setBytes(2, DeploymentCodecMatch.write(unit));
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                "INSERT INTO managed_application(application_id,server_profile_id,health_check_payload,updated_at) VALUES('application','server',?,1)")) {
                statement.setBytes(1, DeploymentCodecMatch.write(health));
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                """
                    INSERT INTO managed_application_component(application_id,component_id,deployment_unit_id,reviewed_runtime_payload,reviewed_data_paths_payload,reviewed_resource_bindings_payload)
                    VALUES('application','component','unit',?,?,?)
                    """)) {
                statement.setBytes(1, DeploymentCodecMatch.write(definition));
                statement.setBytes(2, DeploymentCodecMatch.write(List.<DeploymentStoragePath>of()));
                statement.setBytes(3, DeploymentCodecMatch
                    .write(new DeploymentResourceBindings(List.of(), Optional.empty())));
                statement.executeUpdate();
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(
                    "UPDATE managed_application SET health_component_id='component',graph_ready=TRUE");
            }
        });
    }

    void accepted(PlatformRuntime runtime) throws Exception {
        try (var access = scope(runtime, true)) {
            var report = new DeploymentSchema().validate(access);
            assertTrue(report.valid(), report.toString());
        }
    }

    void rejected(PlatformRuntime runtime) throws Exception {
        try (var access = scope(runtime, true)) {
            var report = new DeploymentSchema().validate(access);
            assertFalse(report.valid());
            assertEquals(List.of("Stored deployment runtime payloads are invalid"), report.violations());
            assertTrue(report.businessDigest().isEmpty());
        }
    }

    @Test
    void exactVersionedPayloadsRemainValidAcrossEngineRestart() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            seed(runtime);
            accepted(runtime);
        }
        try (var runtime = open()) {
            start(runtime, true);
            accepted(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deployment_unit.runtime_payload", "managed_application.health_check_payload",
        "managed_application_component.reviewed_runtime_payload",
        "managed_application_component.reviewed_data_paths_payload",
        "managed_application_component.reviewed_resource_bindings_payload"})
    void invalidStoredFormatIsRejectedWithoutChangingTheOriginalBytes(String field) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            seed(runtime);
            var parts = field.split("\\.");
            byte[] original = {1, 2, 3, 4};
            write(runtime, connection -> {
                try (var statement = connection.prepareStatement("UPDATE " + parts[0] + " SET " + parts[1] + "=?")) {
                    statement.setBytes(1, original);
                    statement.executeUpdate();
                }
            });
            rejected(runtime);
            try (var access = scope(runtime, true);
                 var connection = access.openConnection();
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT " + parts[1] + " FROM " + parts[0])) {
                assertTrue(rows.next());
                assertArrayEquals(original, rows.getBytes(1));
            }
        }
    }

    @Test
    void missingUnitHealthIsRejectedButUnreviewedNullableRuntimeRemainsADraft() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            seed(runtime);
            write(runtime, connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE deployment_unit SET runtime_payload=NULL");
                }
            });
            rejected(runtime);
            write(runtime, connection -> {
                try (var statement = connection.createStatement()) {
                    statement
                        .executeUpdate("UPDATE managed_application SET graph_ready=FALSE,health_component_id=NULL");
                    statement.executeUpdate("UPDATE managed_application_component SET reviewed_runtime_payload=NULL");
                }
            });
            accepted(runtime);
        }
    }

    @Test
    void oversizedBlobIsBoundedBeforeDeserialization() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            seed(runtime);
            write(runtime, connection -> {
                try (var statement = connection.prepareStatement("UPDATE deployment_unit SET runtime_payload=?")) {
                    statement.setBytes(1, new byte[1_048_577]);
                    statement.executeUpdate();
                }
            });
            rejected(runtime);
        }
    }
}
