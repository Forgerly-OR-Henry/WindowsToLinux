package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.config.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

abstract class DeploymentGraphFixture extends DeploymentRuntimeFixture {
    static final Instant OBSERVED = Instant.parse("2026-09-27T00:00:00Z");

    DeploymentApplicationGraph.Part component(String id, String server, List<String> dependencies) {
        var health = new DeploymentHealthCheck.Tcp(8080, 20, 2);
        var definition = new DeploymentRuntimeDefinition.NodeService(22, health);
        var unit = DeploymentUnitIdentity.forManaged(id, new ServerId(server), "a".repeat(64));
        var runtime = new DeploymentUnitRuntimeConfig(health, Optional.empty(), definition.identityPolicy(),
            definition.workload());
        var path = new DeploymentStoragePath("uploads", DeploymentStoragePath.AccessMode.READ_WRITE, "uploads-v1",
            false);
        var binding = new DeploymentFileBinding("uploads", path, DeploymentStorageLocation.defaults(),
            DeploymentStorageLocation.StorageResourceType.FILE);
        return new DeploymentApplicationGraph.Part(id, unit, runtime, dependencies, definition, List.of(path),
            new DeploymentResourceBindings(List.of(binding), Optional.empty()));
    }

    DeploymentApplicationGraph graph(String id) {
        var web = component("web", "server", List.of("worker"));
        var worker = component("worker", "server", List.of());
        return new DeploymentApplicationGraph(id, "web", new DeploymentHealthCheck.Process(20, 2),
            List.of(worker, web));
    }

    void run(PlatformRuntime runtime, TaskWork<Void> work) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var observed = new AtomicReference<Throwable>();
        var handle = context.tasks()
            .submit(new TaskRequest("graph_acceptance", "Graph acceptance", List.of("server"), true), task -> {
                try {
                    return work.run(task);
                } catch (Exception | AssertionError failure) {
                    observed.set(failure);
                    throw failure;
                }
            });
        OperationResult<Void> result;
        try {
            result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new AssertionError("Original fixture work did not conclude: " + handle.snapshot(),
                observed.get() == null ? timeout : observed.get());
        }
        if (observed.get() != null)
            throw new AssertionError("Original fixture work failed", observed.get());
        assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
    }

    void server(PlatformRuntime runtime) throws Exception {
        runtime.servers().saveServerProfile(
            gold.debug.windowstolinux.app.service.server.ServerProfile
                .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "tester", "fixture-password")),
            "fixture-secret".toCharArray());
    }

    OperationResult<Void> success() {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    void seed(PlatformRuntime runtime, TaskContext task, DeploymentApplicationGraph graph) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        for (var component : graph.components()) {
            var reference = context.servers().reserve(component.unit().server(), "unit:" + component.unit().id(), task);
            var unit = component.unit();
            try (var connection = context.db().openConnection(task);
                 var statement = connection.prepareStatement(
                     "INSERT INTO deployment_unit(deployment_unit_id,server_profile_id,systemd_unit_name,release_root_path,ownership_manifest_sha256,runtime_payload,current_release_sha256,current_release_published_at,has_display_override,display_name,application_category) VALUES(?,?,?,?,?,?,?,1,1,'Retained name','APP')")) {
                statement.setString(1, unit.id());
                statement.setString(2, unit.server().id());
                statement.setString(3, unit.systemdUnit());
                statement.setString(4, unit.releaseRoot());
                statement.setString(5, unit.ownershipManifestSha256());
                statement.setBytes(6, new DeploymentUnitRuntimeCodec().write(component.runtime()));
                statement.setString(7, "b".repeat(64));
                statement.executeUpdate();
            }
            new DeploymentConfigStore(context.db()).saveAndBindRelease(task,
                gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot
                    .create(unit.id(), 1, "1", Instant.ofEpochMilli(1), List.of()),
                "b".repeat(64));
            new DeploymentSecretStore(context.db()).bindRelease(task, unit.id(), "b".repeat(64), List.of());
            context.servers().resolve(reference.id(), ResourceRefResult.REFERENCED, task);
        }
    }

    void sql(PlatformRuntime runtime, TaskContext task, String sql) throws Exception {
        try (var connection = runtime.modContexts().get(OWNER).context().db().openConnection(task);
             var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    void valid(PlatformRuntime runtime) throws Exception {
        try (var scope = scope(runtime, true)) {
            var report = new DeploymentSchema().validate(scope);
            assertTrue(report.valid(), report.toString());
        }
    }

    ModDatabase commitFault(ModDatabase delegate) {
        return new ModDatabase() {
            public SchemaIdentity identity() throws ApiException {
                return delegate.identity();
            }

            public Connection openConnection(TaskContext task) throws ApiException, SQLException {
                var connection = delegate.openConnection(task);
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        try {
                            var result = method.invoke(connection, args);
                            if (method.getName().equals("commit"))
                                throw new SQLException("Commit acknowledgement fixture");
                            return result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            }
        };
    }

    List<DeploymentUnitCommit> publications(DeploymentApplicationGraph graph, int revision,
                                            List<DeploymentSecretRef> secrets) {
        return graph.components().stream()
            .map(component -> new DeploymentUnitCommit(component.unit(), component.runtime(),
                new DeploymentCurrentRelease(component.unit().id(),
                    String.valueOf((char) ('a' + revision - 1)).repeat(64), OBSERVED.plusSeconds(revision)),
                DeploymentConfigSnapshot.create(component.unit().id(), revision, "1",
                    OBSERVED.plusSeconds(revision),
                    DeploymentConfigInput.parse("PORT=" + (8080 + revision))),
                secrets))
            .toList();
    }

    long count(PlatformRuntime runtime, TaskContext task, String sql) throws Exception {
        try (var connection = runtime.modContexts().get(OWNER).context().db().openConnection(task);
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    void verify(PlatformRuntime runtime, TaskContext task, DeploymentApplicationGraph graph,
                List<DeploymentUnitCommit> records) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var publication = new DeploymentPublishStore(context.db());
        var configuration = new DeploymentConfigStore(context.db());
        var secrets = new DeploymentSecretStore(context.db());
        assertEquals(graph,
            new DeploymentGraphStore(context.db()).find(task, graph.applicationId()).orElseThrow());
        for (var record : records) {
            assertEquals(record.unit(), publication.findUnit(task, record.unit().id()).orElseThrow());
            assertEquals(record.runtime(), publication.findRuntime(task, record.unit().id()).orElseThrow());
            assertEquals(record.release(), publication.findRelease(task, record.unit().id()).orElseThrow());
            assertEquals(record.configuration(),
                configuration.findRelease(task, record.unit().id(), record.release().sha256()).orElseThrow());
            assertEquals(Set.copyOf(record.secrets()),
                Set.copyOf(secrets.findRelease(task, record.unit().id(), record.release().sha256()).orElseThrow()));
        }
    }

}
