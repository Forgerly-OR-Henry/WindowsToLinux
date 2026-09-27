package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.appapi.db.*;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

abstract class DeploymentGraphFixture extends DeploymentRuntimeFixture {
    static final Instant OBSERVED = Instant.parse("2026-09-27T00:00:00Z");

    DeploymentApplicationGraph.Component component(String id, String server, List<String> dependencies) {
        var health = new DeploymentHealthCheck.Tcp(8080, 20, 2);
        var definition = new DeploymentRuntimeDefinition.NodeService(22, health);
        var unit = DeploymentUnitIdentity.forManaged(id, new ServerRef(server), "a".repeat(64));
        var runtime = new DeploymentUnitRuntimeConfiguration(health, Optional.empty(), definition.identityPolicy(),
                definition.workload());
        var path = new DeploymentDataPath("uploads", DeploymentDataPath.AccessMode.READ_WRITE, "uploads-v1", false);
        var binding = new DeploymentFileBinding("uploads", path, DeploymentStorageLocation.defaults(),
                DeploymentStorageLocation.StorageResourceType.FILE);
        return new DeploymentApplicationGraph.Component(id, unit, runtime, dependencies, definition, List.of(path),
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
        var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
        if (observed.get() != null)
            throw new AssertionError("Original fixture work failed", observed.get());
        assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
    }

    void server(PlatformRuntime runtime) throws Exception {
        runtime.servers().saveServerProfile(
                gold.debug.windowstolinux.app.service.contract.profile.ServerProfile
                        .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "tester", "fixture-password")),
                "fixture-secret".toCharArray());
    }

    OperationResult<Void> success() {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
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
            new DeploymentConfigurationRepository(context.db()).saveAndBindRelease(task,
                    gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationSnapshot
                            .create(unit.id(), 1, "1", Instant.ofEpochMilli(1), List.of()),
                    "b".repeat(64));
            new DeploymentSecretRepository(context.db()).bindRelease(task, unit.id(), "b".repeat(64), List.of());
            context.servers().resolve(reference.id(), ReferenceResolution.REFERENCED, task);
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

}
