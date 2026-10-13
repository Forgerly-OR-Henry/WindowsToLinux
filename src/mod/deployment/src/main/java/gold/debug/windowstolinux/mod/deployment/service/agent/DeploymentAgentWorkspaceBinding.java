package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.util.*;
import java.sql.SQLException;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;

/** Exact candidate nonce decoding for original semantics four and claimed child semantics five. */
public final class DeploymentAgentWorkspaceBinding {
    public static final String EVENT = "SUCCESSOR_BINDING";

    public record Binding(UUID root, UUID predecessor, UUID ticket, UUID operation, String application,
                          String source, String layout, String target, String configuration, String ticketSha256) {
        public Binding {
            for (var id : List.of(root, predecessor, ticket, operation)) Objects.requireNonNull(id);
            for (var digest : List.of(source, layout, target, configuration, ticketSha256))
                if (!digest.matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("Invalid successor binding digest");
            if (!application.matches("[a-z0-9][a-z0-9-]{0,62}") || operation.equals(root) || operation.equals(predecessor))
                throw new IllegalArgumentException("Invalid successor workspace owner");
        }

        public String detail(UUID child, String server) {
            if (child.equals(predecessor) || child.equals(root) || child.equals(operation))
                throw new IllegalArgumentException("Successor task must have a fresh identity");
            return DeploymentJsonDocument.object().put("formatVersion", 1).put("task", child.toString())
                .put("root", root.toString()).put("predecessor", predecessor.toString()).put("ticket", ticket.toString())
                .put("operation", operation.toString()).put("application", application).put("source", source)
                .put("layout", layout).put("target", target).put("configuration", configuration)
                .put("ticketSha256", ticketSha256).put("server", server).put("claim", child.toString()).toString();
        }
    }

    private DeploymentAgentWorkspaceBinding() {
    }

    public static Optional<Binding> find(UUID task, DeploymentAgentActionLog.Snapshot saved,
                                         List<DeploymentAgentActionLog.Event> events) throws SQLException {
        var bindings = events.stream().filter(event -> event.type().equals(EVENT)).toList();
        if (saved.executionSemantics() == 4) {
            if (!bindings.isEmpty())
                throw new IllegalStateException("Original frozen semantics cannot acquire a later nonce");
            return Optional.empty();
        }
        if (saved.executionSemantics() != 5 || bindings.size() != 1)
            throw new IllegalStateException("Claimed successor binding is absent or ambiguous");
        var event = bindings.getFirst();
        var node = DeploymentJsonDocument.parse(event.detail());
        DeploymentJsonDocument.fields(node, Set.of("formatVersion", "task", "root", "predecessor", "ticket", "operation",
            "application", "source", "layout", "target", "configuration", "ticketSha256", "server", "claim"));
        if (!node.path("formatVersion").isInt() || node.path("formatVersion").intValue() != 1
            || !event.action().equals(task.toString()) || !string(node, "task").equals(task.toString())
            || !string(node, "claim").equals(task.toString()) || !string(node, "server").equals(saved.identity().server()))
            throw new IllegalStateException("Foreign successor task binding");
        var value = new Binding(UUID.fromString(string(node, "root")), UUID.fromString(string(node, "predecessor")),
            UUID.fromString(string(node, "ticket")), UUID.fromString(string(node, "operation")), string(node, "application"),
            string(node, "source"), string(node, "layout"), string(node, "target"), string(node, "configuration"), string(node, "ticketSha256"));
        if (!event.detail().equals(value.detail(task, saved.identity().server()))
            || !value.target().equals(saved.identity().targetDigest()) || !value.configuration().equals(saved.identity().configurationDigest()))
            throw new IllegalStateException("Successor policy binding changed");
        var sources = events.stream().filter(e -> e.type().equals("SOURCE")).toList();
        if (sources.size() != 1 || !sources.getFirst().detail().equals(value.source()) || event.id() >= sources.getFirst().id()
            || events.stream().filter(e -> e.type().equals("PLAN_APPLICATION")).anyMatch(e -> !e.detail().equals(value.application())))
            throw new IllegalStateException("Successor original source changed");
        return Optional.of(value);
    }

    public static DeploymentRemoteWorkspace workspace(UUID task, DeploymentAgentActionLog.Snapshot saved,
                                                      List<DeploymentAgentActionLog.Event> events, String managedUnit,
                                                      String archive, DeploymentRemoteLayout layout) throws SQLException {
        var binding = find(task, saved, events);
        if (binding.isPresent() && !binding.orElseThrow().layout().equals(layout.digest()))
            throw new IllegalStateException("Successor original layout changed");
        return new DeploymentRemoteWorkspace(managedUnit, archive, binding.map(Binding::operation), layout);
    }

    private static String string(com.fasterxml.jackson.databind.JsonNode node, String key) throws SQLException {
        return DeploymentJsonDocument.string(node, key);
    }
}
