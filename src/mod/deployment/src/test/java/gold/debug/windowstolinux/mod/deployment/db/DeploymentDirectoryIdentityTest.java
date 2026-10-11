package gold.debug.windowstolinux.mod.deployment.db;

import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity.DeploymentExecutionMode;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentGraphStore;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishStore;
import gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefIntent;
import gold.debug.windowstolinux.mod.deployment.db.release.*;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseRecord.DeploymentReleaseState;
import gold.debug.windowstolinux.mod.deployment.db.resource.*;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Only local H2 persistence and canonical bytes; no server or provider. */
class DeploymentDirectoryIdentityTest {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    private static final ServerId SERVER = new ServerId("original-server");

    @Test
    void defaultLayoutKeepsLegacyRuntimeBytesAndWholeReleaseDigest() throws Exception {
        var graph = graph(DeploymentRemoteLayout.original());
        var snapshot = DeploymentReleaseSnapshot.capture(graph, publications(graph, 1));
        String originalRuntime = legacyRuntime(snapshot);
        String originalHealth = legacyHealth(snapshot);
        assertArrayEquals(originalRuntime.getBytes(StandardCharsets.UTF_8),
            DeploymentReleaseCodec.runtime(snapshot).getBytes(StandardCharsets.UTF_8));
        assertEquals(originalHealth, DeploymentReleaseCodec.health(snapshot));
        assertEquals(legacyDigest(originalRuntime, originalHealth),
            DeploymentReleaseCodec.digest(originalRuntime, originalHealth));
        assertFalse(originalRuntime.contains("remoteLayout"));
        assertEquals(snapshot, DeploymentReleaseCodec.snapshot(record(originalRuntime, originalHealth)));
        var unit = graph.components().getFirst().unit();
        assertEquals(unit, new DeploymentUnitIdentity(unit.id(), unit.server(), unit.systemdUnit(),
            unit.releaseRoot(), unit.ownershipManifestSha256()));
        assertEquals(DeploymentRemoteLayout.original(),
            DeploymentUnitIdentity.forManaged("other-unit", SERVER, "f".repeat(64)).layout());
    }

    @Test
    void firstNonDefaultPublicationPersistsAllRootsAndManagementReadsOriginalLayout() throws Exception {
        try (var frame = new Frame()) {
            var original = graph(customLayout());
            var release = frame.publish(original, 1);
            assertEquals(original, new DeploymentGraphStore(frame.database).find(frame.task, "application").orElseThrow());
            for (var component : original.components()) {
                var stored = new DeploymentPublishStore(frame.database).findUnit(frame.task, component.unit().id()).orElseThrow();
                assertEquals(component.unit(), stored);
                assertEquals(customLayout().canonical(), stored.layout().canonical());
                assertNotEquals(release.identity().sha256(),
                    new DeploymentPublishStore(frame.database).findRelease(frame.task, stored.id()).orElseThrow().sha256());
            }
            var runtime = parse(release.runtimeJson(), DeploymentReleaseCodec.LIMIT);
            assertEquals(2, runtime.path("formatVersion").intValue());
            assertEquals(customLayout().canonical(), runtime.path("remoteLayout").toString());
            assertEquals(original, new DeploymentReleaseStore(frame.database).snapshot(release).graph());
            // A separately constructed later layout does not replace any retained original root.
            assertNotEquals(customLayout(), changedLayout("dataRoot"));
            assertEquals(customLayout(), new DeploymentPublishStore(frame.database)
                .findUnit(frame.task, "frontend-unit").orElseThrow().layout());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"helperDirectory", "applicationsRoot", "stateRoot", "configurationRoot", "dataRoot"})
    void changingAnyRootOnAnExistingUnitFailsBeforeAnyPublicationWrite(String root) throws Exception {
        try (var frame = new Frame()) {
            var original = graph(customLayout());
            frame.publish(original, 1);
            var changed = graph(changedLayout(root));
            var writes = new AtomicInteger();
            try (var connection = frame.connection()) {
                connection.setAutoCommit(false);
                assertThrows(SQLException.class, () -> DeploymentPublishStore.save(
                    countWrites(connection, writes), changed, publications(changed, 2), NOW.plusSeconds(2)));
                assertEquals(0, writes.get());
                connection.rollback();
            }
            assertEquals(original, new DeploymentGraphStore(frame.database).find(frame.task, "application").orElseThrow());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "duplicate", "digest", "noncanonical", "unknown", "unit", "application", "component", "componentRelease", "configuration"})
    void brokenActiveReleaseOrComponentBindingIsNeverDefaultedAndPreflightWritesNothing(String fault) throws Exception {
        try (var frame = new Frame()) {
            var original = graph(customLayout());
            var active = frame.publish(original, 1);
            frame.corrupt(fault, active);
            assertThrows(SQLException.class, () -> new DeploymentPublishStore(frame.database)
                .findUnit(frame.task, "frontend-unit"));
            assertThrows(SQLException.class, () -> new DeploymentGraphStore(frame.database).find(frame.task, "application"));
            var writes = new AtomicInteger();
            try (var connection = frame.connection()) {
                connection.setAutoCommit(false);
                assertThrows(SQLException.class, () -> DeploymentPublishStore.save(
                    countWrites(connection, writes), original, publications(original, 2), NOW.plusSeconds(2)));
                assertEquals(0, writes.get());
                connection.rollback();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingLayout", "missingRoot", "extraRoot", "unknownLayoutVersion", "extraTop", "defaultV2", "v1CustomRoot", "duplicateField"})
    void versionedRuntimeRequiresItsCompleteCanonicalLayout(String fault) throws Exception {
        var graph = graph(customLayout());
        var snapshot = DeploymentReleaseSnapshot.capture(graph, publications(graph, 1));
        var runtime = parse(DeploymentReleaseCodec.runtime(snapshot), DeploymentReleaseCodec.LIMIT);
        switch (fault) {
            case "missingLayout" -> runtime.remove("remoteLayout");
            case "missingRoot" -> ((ObjectNode) runtime.path("remoteLayout").path("remote")).remove("dataRoot");
            case "extraRoot" ->
                ((ObjectNode) runtime.path("remoteLayout").path("remote")).put("otherRoot", "/srv/extra");
            case "unknownLayoutVersion" -> ((ObjectNode) runtime.path("remoteLayout")).put("formatVersion", 2);
            case "extraTop" -> runtime.put("extra", true);
            case "defaultV2" -> {
                var defaultGraph = graph(DeploymentRemoteLayout.original());
                runtime = parse(DeploymentReleaseCodec.runtime(DeploymentReleaseSnapshot.capture(defaultGraph, publications(defaultGraph, 1))));
                runtime.put("formatVersion", 2);
                runtime.set("remoteLayout", parse(DeploymentRemoteLayout.original().canonical()));
            }
            case "v1CustomRoot" -> {
                runtime.put("formatVersion", 1);
                runtime.remove("remoteLayout");
            }
            case "duplicateField" -> {
            }
            default -> throw new AssertionError(fault);
        }
        String encoded = runtime.toString();
        if (fault.equals("duplicateField"))
            encoded = encoded.replace("\"remoteLayout\":{", "\"remoteLayout\":{},\"remoteLayout\":{");
        var invalid = record(encoded, DeploymentReleaseCodec.health(snapshot));
        assertThrows(SQLException.class, () -> DeploymentReleaseCodec.snapshot(invalid));
    }

    @Test
    void mixedGraphLayoutsAreRejectedBeforeAnyWriteAndCannotBecomeARelease() throws Exception {
        var backend = component("backend", DeploymentRemoteLayout.original(), List.of());
        var frontend = component("frontend", customLayout(), List.of("backend"));
        var graph = new DeploymentApplicationGraph("application", "frontend", new DeploymentHealthCheck.Process(20, 2), List.of(backend, frontend));
        var snapshot = DeploymentReleaseSnapshot.capture(graph, publications(graph, 1));
        assertThrows(SQLException.class, () -> DeploymentReleaseCodec.runtime(snapshot));
        try (var frame = new Frame(); var connection = frame.connection()) {
            connection.setAutoCommit(false);
            var writes = new AtomicInteger();
            assertThrows(IllegalArgumentException.class, () -> DeploymentPublishStore.save(
                countWrites(connection, writes), graph, publications(graph, 1), NOW));
            assertEquals(0, writes.get());
            connection.rollback();
        }
    }

    @Test
    void bareExistingUnitWithoutReleaseFailsAndCannotBorrowProposedLayout() throws Exception {
        try (var frame = new Frame()) {
            var graph = graph(customLayout());
            var unit = graph.components().getFirst().unit();
            try (var statement = frame.retained.prepareStatement(
                "INSERT INTO deployment_unit(deployment_unit_id,server_profile_id,systemd_unit_name,release_root_path,ownership_manifest_sha256) VALUES(?,?,?,?,?)")) {
                statement.setString(1, unit.id());
                statement.setString(2, unit.server().id());
                statement.setString(3, unit.systemdUnit());
                statement.setString(4, unit.releaseRoot());
                statement.setString(5, unit.ownershipManifestSha256());
                statement.executeUpdate();
            }
            assertThrows(SQLException.class, () -> new DeploymentPublishStore(frame.database).findUnit(frame.task, unit.id()));
            assertTrue(new DeploymentPublishStore(frame.database).findUnit(frame.task, "absent-unit").isEmpty());
            try (var connection = frame.connection()) {
                connection.setAutoCommit(false);
                var writes = new AtomicInteger();
                assertThrows(SQLException.class, () -> DeploymentPublishStore.save(
                    countWrites(connection, writes), graph, publications(graph, 1), NOW));
                assertEquals(0, writes.get());
                connection.rollback();
            }
        }
    }

    @Test
    void republishingSameLayoutRetainsOriginalFullIdentityAndSwitchesWholeApplicationRelease() throws Exception {
        try (var frame = new Frame()) {
            var graph = graph(customLayout());
            var first = frame.publish(graph, 1);
            var second = frame.publish(graph, 2);
            assertNotEquals(first.identity().sha256(), second.identity().sha256());
            assertEquals(1, frame.scalar("SELECT COUNT(*) FROM deployment_release WHERE state='ACTIVE'"));
            assertEquals(1, frame.scalar("SELECT COUNT(*) FROM deployment_release WHERE state='RETIRED'"));
            assertEquals(graph, new DeploymentGraphStore(frame.database).find(frame.task, "application").orElseThrow());
            assertEquals(customLayout(), new DeploymentPublishStore(frame.database).findUnit(frame.task, "backend-unit").orElseThrow().layout());
            assertEquals(customLayout(), new DeploymentReleaseStore(frame.database).snapshot(
                    new DeploymentReleaseStore(frame.database).find(frame.task, "application", first.identity().id()).orElseThrow())
                .graph().components().getFirst().unit().layout());
        }
    }

    @Test
    void replacingAllUnitsCannotChangeTheOriginalApplicationLayout() throws Exception {
        try (var frame = new Frame()) {
            frame.publish(graph(customLayout()), 1);
            var parts = graph(changedLayout("stateRoot")).components().stream().map(c ->
                new DeploymentApplicationGraph.Part(c.componentId(), DeploymentUnitIdentity.forManaged(c.unit().id() + "-next", SERVER,
                    c.unit().ownershipManifestSha256(), c.unit().layout()), c.runtime(), c.dependencies(), c.reviewedRuntime(), c.dataPaths(), c.resources())).toList();
            var replacement = new DeploymentApplicationGraph("application", "frontend", new DeploymentHealthCheck.Process(20, 2), parts);
            try (var connection = frame.connection()) {
                connection.setAutoCommit(false);
                var writes = new AtomicInteger();
                assertThrows(SQLException.class, () -> DeploymentPublishStore.save(
                    countWrites(connection, writes), replacement, publications(replacement, 2), NOW.plusSeconds(2)));
                assertEquals(0, writes.get());
                connection.rollback();
            }
        }
    }

    private static DeploymentRemoteLayout customLayout() {
        return new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths("/srv/wtl/helper", "/srv/wtl/apps", "/srv/wtl/state", "/srv/wtl/config", "/srv/wtl/data"));
    }

    private static DeploymentRemoteLayout changedLayout(String root) {
        var p = customLayout().paths();
        return new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
            root.equals("helperDirectory") ? "/srv/next-helper" : p.helperDirectory(),
            root.equals("applicationsRoot") ? "/srv/next-apps" : p.applicationsRoot(),
            root.equals("stateRoot") ? "/srv/next-state" : p.stateRoot(),
            root.equals("configurationRoot") ? "/srv/next-config" : p.configurationRoot(),
            root.equals("dataRoot") ? "/srv/next-data" : p.dataRoot()));
    }

    private static DeploymentApplicationGraph graph(DeploymentRemoteLayout layout) {
        return new DeploymentApplicationGraph("application", "frontend", new DeploymentHealthCheck.Process(20, 2),
            List.of(component("backend", layout, List.of()), component("frontend", layout, List.of("backend"))));
    }

    private static DeploymentApplicationGraph.Part component(String id, DeploymentRemoteLayout layout, List<String> dependencies) {
        var health = new DeploymentHealthCheck.Tcp(8080, 20, 2);
        var definition = new DeploymentRuntimeDefinition.NodeService(22, health);
        var unit = DeploymentUnitIdentity.forManaged(id + "-unit", SERVER, (id.equals("backend") ? "b" : "f").repeat(64), layout);
        var runtime = new DeploymentUnitRuntimeConfig(health, Optional.empty(), definition.identityPolicy(), definition.workload());
        return new DeploymentApplicationGraph.Part(id, unit, runtime, dependencies, definition, List.of(), new DeploymentResourceBindings(List.of(), Optional.empty()));
    }

    private static List<DeploymentUnitCommit> publications(DeploymentApplicationGraph graph, int revision) {
        return graph.components().stream().map(c -> new DeploymentUnitCommit(c.unit(), c.runtime(),
            new DeploymentCurrentRelease(c.unit().id(), (c.componentId().equals("backend") ? "a" : "c").repeat(63) + revision,
                NOW.plusSeconds(revision)), DeploymentConfigSnapshot.create(c.unit().id(), revision, "schema-v1", NOW, List.of()), List.of())).toList();
    }

    private static DeploymentReleaseRecord record(String runtime, String health) {
        return new DeploymentReleaseRecord("application", new DeploymentReleaseIdentity(UUID.randomUUID(), DeploymentReleaseCodec.digest(runtime, health)),
            UUID.randomUUID(), DeploymentReleaseState.ACTIVE, runtime, health, NOW);
    }

    // Independent pre-layout JSON order and domain. Binary runtime/storage codecs remain unchanged.
    private static String legacyRuntime(DeploymentReleaseSnapshot snapshot) throws Exception {
        var node = object().put("formatVersion", 1).put("applicationId", snapshot.graph().applicationId());
        var components = node.putArray("components");
        for (var c : snapshot.graph().components()) {
            var u = c.unit();
            var b = snapshot.bindings().stream().filter(binding -> binding.unitId().equals(u.id())).findFirst().orElseThrow();
            var entry = components.addObject().put("componentId", c.componentId()).put("unitId", u.id()).put("serverId", u.server().id())
                .put("systemdUnit", u.systemdUnit()).put("releaseRoot", u.releaseRoot()).put("ownershipManifestSha256", u.ownershipManifestSha256())
                .put("runtime", base64(new DeploymentUnitRuntimeCodec().write(c.runtime())))
                .put("reviewedRuntime", base64(new DeploymentRuntimeCodec().write(c.reviewedRuntime())))
                .put("dataPaths", base64(new DeploymentStoragePathCodec().write(c.dataPaths())))
                .put("resources", base64(new DeploymentResourceCodec().write(c.resources())))
                .put("releaseSha256", b.releaseSha256()).put("configurationRevision", b.configurationRevision()).put("configurationSha256", b.configurationSha256());
            c.dependencies().forEach(entry.putArray("dependencies")::add);
            var secrets = entry.putArray("secrets");
            b.secrets().forEach(s -> secrets.addObject().put("identifier", s.identifier()).put("revision", s.revision()));
        }
        return encode(node, DeploymentReleaseCodec.LIMIT);
    }

    private static String legacyHealth(DeploymentReleaseSnapshot snapshot) throws Exception {
        return encode(object().put("formatVersion", 1).put("applicationId", snapshot.graph().applicationId())
            .put("healthComponentId", snapshot.graph().healthComponentId()).put("health", base64(new DeploymentHealthCodec().write(snapshot.graph().healthCheck()))));
    }

    private static String legacyDigest(String runtime, String health) throws Exception {
        var sha = MessageDigest.getInstance("SHA-256");
        for (var part : List.of("windowstolinux-application-release-v1", runtime, health)) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            sha.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            sha.update(bytes);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static Connection countWrites(Connection connection, AtomicInteger writes) {
        return (Connection) Proxy.newProxyInstance(DeploymentDirectoryIdentityTest.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            Object result = invoke(method, connection, args);
            if (result instanceof PreparedStatement statement && method.getName().equals("prepareStatement"))
                return Proxy.newProxyInstance(DeploymentDirectoryIdentityTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, operation, values) -> {
                    if (Set.of("executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch").contains(operation.getName()))
                        writes.incrementAndGet();
                    return invoke(operation, statement, values);
                });
            return result;
        });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static final class Frame implements AutoCloseable {
        private final UUID generation = UUID.randomUUID();

        private final String jdbc = "jdbc:h2:mem:directory-identity-" + generation;

        private final Connection retained = DriverManager.getConnection(jdbc);

        private final TaskContext task = task(UUID.randomUUID());

        private final ModDatabase database = (ModDatabase) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModDatabase.class}, (proxy, method, args) -> switch (method.getName()) {
            case "identity" ->
                new SchemaIdentity(DeploymentSchemaIdentity.OWNER, DeploymentSchemaIdentity.VERSION, generation);
            case "openConnection" -> connection();
            default -> throw new AssertionError("Unexpected public schema access: " + method.getName());
        });

        Frame() throws Exception {
            try (var source = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/gold/debug/windowstolinux/mod/deployment/db/schema-v1.sql")), StandardCharsets.UTF_8)) {
                org.h2.tools.RunScript.execute(retained, source);
            }
        }

        private Connection connection() throws SQLException {
            return DriverManager.getConnection(jdbc);
        }

        private static TaskContext task(UUID id) {
            return (TaskContext) Proxy.newProxyInstance(DeploymentDirectoryIdentityTest.class.getClassLoader(), new Class<?>[]{TaskContext.class}, (proxy, method, args) -> {
                if (!method.getName().equals("id"))
                    throw new AssertionError("Unexpected task capability: " + method.getName());
                return id;
            });
        }

        private DeploymentReleaseRecord publish(DeploymentApplicationGraph graph, int revision) throws Exception {
            var currentTask = task(UUID.randomUUID());
            var executionId = UUID.randomUUID();
            var journal = new DeploymentExecutionStore(database);
            var intent = DeploymentRefIntent.server(SERVER, "application:application");
            journal.begin(currentTask, new DeploymentExecutionIdentity(executionId, currentTask.id(), Optional.empty(), "application", SERVER,
                DeploymentExecutionMode.STATIC, "d".repeat(64)), "deploy", List.of(intent), Optional.empty(), NOW);
            // The persistence fixture supplies an association UUID; it does not acquire a server capability.
            journal.recordReference(currentTask, executionId, intent, UUID.randomUUID(), NOW);
            journal.startEffects(currentTask, executionId, NOW.plusSeconds(1));
            journal.commitApplication(currentTask, executionId, graph, publications(graph, revision), NOW.plusSeconds(revision));
            return new DeploymentReleaseStore(database).list(task, "application").stream().filter(r -> r.state() == DeploymentReleaseState.ACTIVE).findFirst().orElseThrow();
        }

        private int scalar(String sql) throws SQLException {
            try (var statement = retained.createStatement(); var rows = statement.executeQuery(sql)) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }

        private void corrupt(String fault, DeploymentReleaseRecord active) throws Exception {
            switch (fault) {
                case "missing" -> update("DELETE FROM deployment_release");
                case "duplicate" -> {
                    var runtime = parse(active.runtimeJson(), DeploymentReleaseCodec.LIMIT);
                    ((ObjectNode) runtime.path("components").get(0)).put("releaseSha256", "e".repeat(64));
                    try (var statement = retained.prepareStatement("INSERT INTO deployment_release(application_id,release_id,execution_id,release_sha256,runtime_payload,health_payload,state,created_at) VALUES(?,?,?,?,?,?,'ACTIVE',?)")) {
                        statement.setString(1, "application");
                        statement.setObject(2, UUID.randomUUID());
                        statement.setObject(3, active.executionId());
                        statement.setString(4, DeploymentReleaseCodec.digest(runtime.toString(), active.healthJson()));
                        statement.setBytes(5, runtime.toString().getBytes(StandardCharsets.UTF_8));
                        statement.setBytes(6, active.healthJson().getBytes(StandardCharsets.UTF_8));
                        statement.setLong(7, NOW.toEpochMilli());
                        statement.executeUpdate();
                    }
                }
                case "digest" -> update("UPDATE deployment_release SET release_sha256='" + "0".repeat(64) + "'");
                case "noncanonical", "unknown" -> {
                    var runtime = parse(active.runtimeJson(), DeploymentReleaseCodec.LIMIT);
                    String text = fault.equals("noncanonical") ? " " + runtime : runtime.put("formatVersion", 99).toString();
                    try (var statement = retained.prepareStatement("UPDATE deployment_release SET runtime_payload=?,release_sha256=?")) {
                        statement.setBytes(1, text.getBytes(StandardCharsets.UTF_8));
                        statement.setString(2, DeploymentReleaseCodec.digest(text, active.healthJson()));
                        statement.executeUpdate();
                    }
                }
                case "unit" ->
                    update("UPDATE deployment_unit SET ownership_manifest_sha256='" + "e".repeat(64) + "' WHERE deployment_unit_id='frontend-unit'");
                case "application" -> update("UPDATE managed_application SET server_profile_id='foreign-server'");
                case "component" ->
                    update("UPDATE managed_application_component SET reviewed_runtime_payload=X'00' WHERE deployment_unit_id='frontend-unit'");
                case "componentRelease" ->
                    update("UPDATE deployment_unit SET current_release_sha256='" + active.identity().sha256() + "' WHERE deployment_unit_id='frontend-unit'");
                case "configuration" ->
                    update("UPDATE deployment_unit_release_binding SET configuration_sha256='" + "0".repeat(64) + "' WHERE deployment_unit_id='frontend-unit'");
                default -> throw new AssertionError(fault);
            }
        }

        private void update(String sql) throws SQLException {
            try (var statement = retained.createStatement()) {
                statement.executeUpdate(sql);
            }
        }

        @Override
        public void close() throws SQLException {
            retained.close();
        }
    }
}
