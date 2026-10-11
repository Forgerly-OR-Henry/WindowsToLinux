package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentAgentLayoutsTest {
    private static final UUID TASK = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    private static final DeploymentAgentActionLog.Identity IDENTITY = new DeploymentAgentActionLog.Identity(
        "original-server", "a".repeat(64), "b".repeat(64), "FULL_CONTROL", true);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void capturedOriginalAndNewManagementQueryUseDurableSelectedLayoutAndRejectCheckpointDrift() throws Exception {
        try (var frame = new Frame()) {
            var layout = custom();
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(frame.tasks, frame.task, layout);
            DeploymentAgentLayouts.record(frame.journal, frame.task, new gold.debug.windowstolinux.appapi.server.model.ServerId(IDENTITY.server()), layout);
            var events = frame.journal.events(frame.task);
            assertEquals(layout, DeploymentAgentLayouts.original(frame.context, frame.task, IDENTITY, events));
            var queryTask = proxy(gold.debug.windowstolinux.appapi.task.run.TaskContext.class, (method, arguments) -> UUID.randomUUID());
            assertEquals(layout, DeploymentAgentLayouts.historical(frame.context, queryTask, TASK, IDENTITY, events));
            frame.payload = frame.payload.replace(layout.digest(), "f".repeat(64));
            assertThrows(Exception.class, () -> DeploymentAgentLayouts.original(frame.context, frame.task, IDENTITY, events));
            assertEquals(events, frame.journal.events(frame.task));
        }
    }

    @Test
    void captureWithoutDurableEventAndLegacyWithoutOriginalHeaderEvidenceFailBeforeAnyEffects() throws Exception {
        try (var frame = new Frame()) {
            assertThrows(Exception.class, () -> DeploymentAgentLayouts.original(frame.context, frame.task, IDENTITY, List.of()));
            frame.journal.event(frame.task, "SOURCE", "original", "c".repeat(64));
            frame.journal.event(frame.task, "TARGET_PROFILE", "", "original-profile");
            assertEquals(DeploymentRemoteLayout.original(), DeploymentAgentLayouts.original(frame.context, frame.task, IDENTITY, frame.journal.events(frame.task)));
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(frame.tasks, frame.task, custom());
            var before = frame.journal.events(frame.task);
            assertThrows(Exception.class, () -> DeploymentAgentLayouts.original(frame.context, frame.task, IDENTITY, before));
            DeploymentAgentLayouts.record(frame.journal, frame.task, new gold.debug.windowstolinux.appapi.server.model.ServerId(IDENTITY.server()), custom());
            assertThrows(Exception.class, () -> DeploymentAgentLayouts.record(frame.journal, frame.task,
                new gold.debug.windowstolinux.appapi.server.model.ServerId(IDENTITY.server()), custom()));
            assertEquals(1, frame.journal.events(frame.task).stream().filter(value -> value.type().equals("REMOTE_LAYOUT")).count());
        }
    }

    @Test
    void durableEventRestoresAllFiveOriginalRootsWithoutReadingCurrentConfiguration() throws Exception {
        var layout = custom();
        var original = event(layout);
        assertEquals(layout, DeploymentAgentLayouts.event(TASK, IDENTITY, List.of(original)).orElseThrow());
        assertEquals(DeploymentRemoteLayout.original(), DeploymentAgentLayouts.event(TASK, IDENTITY,
            List.of(event(DeploymentRemoteLayout.original()))).orElseThrow());
        assertTrue(DeploymentAgentLayouts.event(TASK, IDENTITY, List.of()).isEmpty());
        assertFalse(original.detail().contains("permission"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "server", "digest", "root", "duplicate", "version", "extra", "noncanonical", "null"})
    void damagedOrForeignLayoutEvidenceCannotBecomeDefaultOrCurrentConfiguration(String change) throws Exception {
        var original = event(custom());
        var node = JSON.readTree(original.detail());
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
        switch (change) {
            case "task" -> object.put("task", UUID.randomUUID().toString());
            case "server" -> object.put("server", "foreign-server");
            case "digest" -> object.put("layoutSha256", "f".repeat(64));
            case "root" -> ((com.fasterxml.jackson.databind.node.ObjectNode) object.get("layout").get("remote")).put("helperDirectory", "/foreign/helper");
            case "version" -> object.put("formatVersion", 2);
            case "extra" -> object.put("permission", "APPROVED");
            default -> { }
        }
        String text = change.equals("null") ? "null" : change.equals("noncanonical") ? original.detail() + " " : object.toString();
        var changed = new DeploymentAgentActionLog.Event(1, "REMOTE_LAYOUT", TASK.toString(), text);
        List<DeploymentAgentActionLog.Event> events = change.equals("duplicate") ? List.of(original, original) : List.of(changed);
        assertThrows(Exception.class, () -> DeploymentAgentLayouts.event(TASK, IDENTITY, events));
        assertThrows(Exception.class, () -> DeploymentAgentLayouts.event(UUID.randomUUID(), IDENTITY, List.of(original)));
    }

    private static DeploymentAgentActionLog.Event event(DeploymentRemoteLayout layout) throws Exception {
        var node = JSON.createObjectNode().put("formatVersion", 1).put("task", TASK.toString()).put("server", IDENTITY.server());
        node.set("layout", JSON.readTree(layout.canonical()));
        node.put("layoutSha256", layout.digest());
        return new DeploymentAgentActionLog.Event(1, "REMOTE_LAYOUT", TASK.toString(), node.toString());
    }

    private static DeploymentRemoteLayout custom() {
        return new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths("/srv/wtl/helper", "/srv/wtl/apps", "/srv/wtl/state",
            "/srv/wtl/config", "/srv/wtl/data"));
    }

    private static final class Frame implements AutoCloseable {
        private final String jdbc = "jdbc:h2:mem:agent-layout-" + UUID.randomUUID();
        private final java.sql.Connection retained = java.sql.DriverManager.getConnection(jdbc);
        private final gold.debug.windowstolinux.appapi.task.run.TaskContext task = proxy(
            gold.debug.windowstolinux.appapi.task.run.TaskContext.class, (method, arguments) -> TASK);
        private final gold.debug.windowstolinux.appapi.task.TaskService tasks;
        private final gold.debug.windowstolinux.appapi.mod.ModContext context;
        private final DeploymentAgentActionLog journal;
        private String payload;
        private boolean released;

        Frame() throws Exception {
            try (var schema = new java.io.InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
                "/gold/debug/windowstolinux/mod/deployment/db/schema-v1.sql")), java.nio.charset.StandardCharsets.UTF_8)) {
                org.h2.tools.RunScript.execute(retained, schema);
            }
            var db = proxy(gold.debug.windowstolinux.appapi.db.ModDatabase.class, (method, arguments) -> {
                if (method.equals("identity")) return new gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity(
                    gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity.OWNER, "1", TASK);
                assertEquals("openConnection", method);
                return java.sql.DriverManager.getConnection(jdbc);
            });
            journal = new DeploymentAgentActionLog(db);
            journal.create(task, IDENTITY);
            tasks = proxy(gold.debug.windowstolinux.appapi.task.TaskService.class, (method, arguments) -> {
                assertSame(task, arguments[0]);
                assertEquals("deployment.config.remote-layout.v1", arguments[1]);
                if (method.equals("beginCheckpoint")) {
                    payload = (String) arguments[2];
                    return checkpoint();
                }
                assertEquals("findCheckpoint", method);
                return payload == null ? Optional.empty() : Optional.of(checkpoint());
            });
            context = proxy(gold.debug.windowstolinux.appapi.mod.ModContext.class, (method, arguments) -> switch (method) {
                case "db" -> db;
                case "tasks" -> tasks;
                default -> throw new AssertionError("Layout evidence cannot access " + method);
            });
        }

        private gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint checkpoint() {
            return proxy(gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.class, (method, arguments) -> {
                if (method.equals("close")) { released = true; return null; }
                assertEquals("snapshot", method);
                return new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot(0, payload, released);
            });
        }

        public void close() throws Exception { retained.close(); }
    }

    private interface Invocation { Object call(String method, Object[] arguments) throws Exception; }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation callback) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (value, method, arguments) -> callback.call(method.getName(), arguments));
    }
}
