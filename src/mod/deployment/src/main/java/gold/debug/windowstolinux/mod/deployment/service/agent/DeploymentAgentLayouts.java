package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity.DeploymentExecutionMode;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;

/** Original nonsecret directory evidence for retained Agent tasks, independent of current YAML. */
public final class DeploymentAgentLayouts {
    private static final String CHECKPOINT = "deployment.config.remote-layout.v1";
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private DeploymentAgentLayouts() {
    }

    public static void record(DeploymentAgentActionLog journal, TaskContext task, ServerId server,
                              DeploymentRemoteLayout layout) throws Exception {
        if (journal.events(task).stream().anyMatch(event -> event.type().equals("REMOTE_LAYOUT")))
            throw new SQLException("Original Agent layout event already exists");
        var node = JSON.createObjectNode().put("formatVersion", 1).put("task", task.id().toString())
            .put("server", server.id());
        node.set("layout", JSON.readTree(layout.canonical()));
        node.put("layoutSha256", layout.digest());
        journal.event(task, "REMOTE_LAYOUT", task.id().toString(), node.toString());
    }

    /** Uses the original checkpoint and durable event before any recovery command. */
    public static DeploymentRemoteLayout original(ModContext context, TaskContext task,
                                                    DeploymentAgentActionLog.Identity identity,
                                                    List<DeploymentAgentActionLog.Event> events) throws Exception {
        var eventLayout = event(task.id(), identity, events);
        boolean captured = context.tasks().findCheckpoint(task, CHECKPOINT).isPresent();
        if (captured != eventLayout.isPresent())
            throw new SQLException("Original Agent layout checkpoint and event are incomplete");
        var layout = captured ? DeploymentConfigCapture.remoteLayout(context.tasks(), task)
            : legacy(identity, events);
        if (eventLayout.filter(value -> !value.equals(layout)).isPresent())
            throw new SQLException("Original Agent layout checkpoint differs from its event");
        requireFormal(context, task, task.id(), identity, layout);
        return layout;
    }

    /** A new management task reads the selected task's durable identity, never its own configuration. */
    public static DeploymentRemoteLayout historical(ModContext context, TaskContext task, UUID selected,
                                                      DeploymentAgentActionLog.Identity identity,
                                                      List<DeploymentAgentActionLog.Event> events) throws Exception {
        if (selected.equals(task.id()))
            return original(context, task, identity, events);
        var layout = event(selected, identity, events).orElseGet(() -> legacy(identity, events));
        requireFormal(context, task, selected, identity, layout);
        return layout;
    }

    static Optional<DeploymentRemoteLayout> event(UUID selected, DeploymentAgentActionLog.Identity identity,
                                                  List<DeploymentAgentActionLog.Event> events) throws Exception {
        var values = events.stream().filter(value -> value.type().equals("REMOTE_LAYOUT")).toList();
        if (values.isEmpty())
            return Optional.empty();
        if (values.size() != 1)
            throw new SQLException("Original Agent has conflicting layout events");
        var event = values.getFirst();
        if (!event.action().equals(selected.toString()) || event.detail().length() > 4096)
            throw new SQLException("Original Agent layout event identity is invalid");
        var node = JSON.readTree(event.detail());
        if (node == null || !node.isObject()) throw new SQLException("Original Agent layout event is not an object");
        var fields = new HashSet<String>();
        node.fieldNames().forEachRemaining(fields::add);
        if (!node.isObject() || !fields.equals(Set.of("formatVersion", "task", "server", "layout", "layoutSha256"))
            || !node.path("formatVersion").isInt() || node.path("formatVersion").intValue() != 1
            || !node.path("task").isTextual() || !node.path("task").textValue().equals(selected.toString())
            || !node.path("server").isTextual() || !node.path("server").textValue().equals(identity.server())
            || !node.path("layoutSha256").isTextual())
            throw new SQLException("Original Agent layout event binding is invalid");
        var layout = DeploymentRemoteLayout.decode(node.path("layout").toString());
        var canonical = JSON.createObjectNode().put("formatVersion", 1).put("task", selected.toString()).put("server", identity.server());
        canonical.set("layout", JSON.readTree(layout.canonical()));
        canonical.put("layoutSha256", layout.digest());
        if (!layout.digest().equals(node.path("layoutSha256").textValue()) || !canonical.toString().equals(event.detail()))
            throw new SQLException("Original Agent layout event is not canonical");
        return Optional.of(layout);
    }

    private static DeploymentRemoteLayout legacy(DeploymentAgentActionLog.Identity identity,
                                                  List<DeploymentAgentActionLog.Event> events) {
        Objects.requireNonNull(identity);
        if (events.stream().noneMatch(event -> event.type().equals("SOURCE"))
            || events.stream().noneMatch(event -> event.type().equals("TARGET_PROFILE")))
            throw new IllegalArgumentException("Legacy Agent layout requires its original retained header and source/profile evidence");
        return DeploymentRemoteLayout.original();
    }

    private static void requireFormal(ModContext context, TaskContext task, UUID selected,
                                      DeploymentAgentActionLog.Identity identity, DeploymentRemoteLayout layout) throws Exception {
        try (var connection = context.db().openConnection(task);
             var query = connection.prepareStatement("SELECT * FROM deployment_execution WHERE platform_task_id=?")) {
            query.setObject(1, selected);
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    return;
                var original = DeploymentExecutionStore.row(rows);
                if (rows.next() || original.identity().mode() != DeploymentExecutionMode.AGENT
                    || !original.identity().taskId().equals(selected) || !original.identity().server().id().equals(identity.server()))
                    throw new SQLException("Original Agent formal layout identity changed");
                var proposal = new DeploymentExecutionStore(context.db()).publicationTemplate(original);
                if (proposal.isPresent() && proposal.orElseThrow().graph().components().stream()
                    .anyMatch(component -> !component.unit().layout().equals(layout)))
                    throw new SQLException("Original Agent formal publication differs from its retained layout");
            }
        }
    }
}
