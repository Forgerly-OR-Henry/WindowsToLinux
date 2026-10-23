package gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPreparationProbe;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentManagedUnitResolver;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;

/** An observed absence of one original pre-readiness preparation; never a claim that its command was not sent. */
public final class DeploymentAgentPreparationAbsence {
    public static final String EVENT = "PREPARE_RECONCILED_ABSENT";

    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Set<String> BEFORE = Set.of("REMEMBER_AUTHORIZATION", "REMOTE_TARGET", "REMOTE_LAYOUT", "SOURCE",
        "SUCCESSOR_BINDING", "TARGET_PROFILE", "AGENT_QUERY", "AGENT_ASSESSMENT", "AGENT_OBSERVATION", "DECISION", "PLAN", "PLAN_APPLICATION", "STATE", "WORKSPACE_QUOTA",
        "PREPARE_EXECUTING", "RECOVERY_REVIEW_BOUND", "RECOVERY_REVIEW_REBOUND");

    private static final Set<String> AFTER = Set.of("STATE", "RECOVERY_REVIEW_BOUND", "RECOVERY_REVIEW_REBOUND");

    private static final Set<String> FIELDS = Set.of("formatVersion", "task", "server", "targetSha256", "configurationSha256", "approvalMode", "state",
        "layoutSha256", "profileSha256", "endpointSha256", "sourceSha256", "archiveSha256", "planSha256", "application", "component",
        "candidate", "prepareEventId", "prepareAction", "priorEventsSha256", "requestSha256", "resultSha256", "observation");

    private DeploymentAgentPreparationAbsence() {
    }

    public record Captured(UUID task, DeploymentAgentActionLog.Snapshot snapshot, DeploymentRemoteLayout layout,
                           DeploymentAgentActionLog.Event preparation, DeploymentRemoteWorkspace workspace,
                           String source, String plan, String profile, String endpoint, String application,
                           List<DeploymentAgentActionLog.Event> events) {
        public Captured {
            events = List.copyOf(events);
        }
    }

    /** Only semantics 4's first preparation, before its durably observed readiness, is eligible. */
    public static Optional<Captured> capture(UUID task, DeploymentAgentActionLog.Snapshot snapshot, DeploymentRemoteLayout layout,
                                             List<DeploymentAgentActionLog.Event> events) {
        try {
            if (!Set.of(4, 5).contains(snapshot.executionSemantics()) || !Set.of("UNKNOWN", "CANCELLED").contains(snapshot.state())
                || events.isEmpty() || events.size() > 10000) return Optional.empty();
            long previous = 0;
            for (var event : events) {
                if (event.id() <= previous || !BEFORE.contains(event.type()) || event.action() == null || event.detail() == null)
                    return Optional.empty();
                previous = event.id();
            }
            var preparation = one(events, "PREPARE_EXECUTING");
            var source = one(events, "SOURCE");
            var plan = one(events, "PLAN");
            var application = one(events, "PLAN_APPLICATION");
            var profile = one(events, "TARGET_PROFILE");
            var endpoint = one(events, "REMOTE_TARGET");
            var policy = one(events, "REMEMBER_AUTHORIZATION");
            var capturedLayout = one(events, "REMOTE_LAYOUT");
            if (!preparation.detail().matches("[a-z0-9][a-z0-9-]{0,62}") || !source.action().matches("[0-9a-f]{64}")
                || !source.detail().matches("[0-9a-f]{64}") || !plan.action().isEmpty() || !plan.detail().matches("[0-9a-f]{64}")
                || !application.action().isEmpty() || !application.detail().matches("[a-z0-9][a-z0-9-]{0,62}")
                || !profile.action().isEmpty() || !profile.detail().matches("[0-9a-f]{64}")
                || !endpoint.action().isEmpty() || !endpoint.detail().matches("[0-9a-f]{64}")
                || !policy.action().equals("rememberAuthorization.v1") || !policy.detail().equals(Boolean.toString(snapshot.identity().rememberAuthorization()))
                || !preparation.action().equals(DeploymentAgentPreparationBinding.preparationAction(task, snapshot.identity(), source, plan, preparation, layout.digest(), events)))
                return Optional.empty();
            for (var header : List.of(source, plan, application, profile, endpoint, policy, capturedLayout))
                if (header.id() >= preparation.id()) return Optional.empty();
            var states = events.stream().filter(event -> event.type().equals("STATE")).toList();
            if (states.isEmpty() || states.getLast().id() <= preparation.id() || !states.getLast().detail().equals(snapshot.state())
                || states.stream().anyMatch(event -> !event.action().isEmpty() || !Set.of("RUNNING", "WAITING", "UNKNOWN", "CANCELLED").contains(event.detail())))
                return Optional.empty();
            if (events.stream().anyMatch(event -> event.id() > preparation.id() && !AFTER.contains(event.type())))
                return Optional.empty();
            var node = JSON.readTree(capturedLayout.detail());
            var canonical = JSON.createObjectNode().put("formatVersion", 1).put("task", task.toString()).put("server", snapshot.identity().server());
            canonical.set("layout", JSON.readTree(layout.canonical()));
            canonical.put("layoutSha256", layout.digest());
            if (!capturedLayout.action().equals(task.toString()) || !canonical.equals(node) || !canonical.toString().equals(capturedLayout.detail()))
                return Optional.empty();
            String managed = DeploymentManagedUnitResolver.managedId(application.detail(), preparation.detail());
            var workspace = gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentWorkspaceBinding.workspace(task, snapshot, events, managed, source.action(), layout);
            return Optional.of(new Captured(task, snapshot, layout, preparation, workspace, source.detail(), plan.detail(), profile.detail(),
                endpoint.detail(), application.detail(), events));
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    public static String proof(Captured captured, String requestSha256, String resultSha256) {
        if (!requestSha256.matches("[0-9a-f]{64}") || !resultSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid preparation observation digest");
        var identity = captured.snapshot().identity();
        var proof = JSON.createObjectNode().put("formatVersion", captured.snapshot().executionSemantics() == 5 ? 2 : 1).put("task", captured.task().toString()).put("server", identity.server())
            .put("targetSha256", identity.targetDigest()).put("configurationSha256", identity.configurationDigest()).put("approvalMode", identity.approvalMode()).put("state", captured.snapshot().state())
            .put("layoutSha256", captured.layout().digest()).put("profileSha256", captured.profile()).put("endpointSha256", captured.endpoint())
            .put("sourceSha256", captured.source()).put("archiveSha256", captured.workspace().archiveSha256()).put("planSha256", captured.plan())
            .put("application", captured.application()).put("component", captured.preparation().detail()).put("candidate", captured.workspace().candidateId())
            .put("prepareEventId", captured.preparation().id()).put("prepareAction", captured.preparation().action()).put("priorEventsSha256", digest(captured.events()))
            .put("requestSha256", requestSha256).put("resultSha256", resultSha256).put("observation", "ABSENT_NO_BUILD_MOUNT_LOOP");
        if (captured.snapshot().executionSemantics() == 5) proof.put("executionSemantics", 5);
        return proof.toString();
    }

    /** Validates the entire original prefix, including the exact preparation event and all immutable headers. */
    public static boolean matches(DeploymentAgentActionLog.Event event, List<DeploymentAgentActionLog.Event> events) {
        try {
            if (!EVENT.equals(event.type()) || event.detail().length() > 4096) return false;
            var node = JSON.readTree(event.detail());
            if (node == null || !node.isObject()) return false;
            var fields = new HashSet<String>();
            node.fieldNames().forEachRemaining(fields::add);
            int semantics = node.path("formatVersion").intValue() == 1 ? 4 : 5;
            var expected = new HashSet<>(FIELDS);
            if (semantics == 5) expected.add("executionSemantics");
            if (!fields.equals(expected) || !node.path("formatVersion").isInt() || !Set.of(1, 2).contains(node.path("formatVersion").intValue())
                || semantics == 5 && (!node.path("executionSemantics").isInt() || node.path("executionSemantics").intValue() != 5)
                || !node.path("prepareEventId").isIntegralNumber() || !node.path("prepareEventId").canConvertToLong())
                return false;
            for (String field : FIELDS)
                if (!Set.of("formatVersion", "prepareEventId").contains(field) && !node.path(field).isTextual())
                    return false;
            UUID task = UUID.fromString(node.path("task").textValue());
            var prefix = events.stream().filter(value -> value.id() < event.id()).toList();
            var policy = one(prefix, "REMEMBER_AUTHORIZATION");
            if (!Set.of("true", "false").contains(policy.detail())) return false;
            var identity = new DeploymentAgentActionLog.Identity(node.path("server").textValue(), node.path("targetSha256").textValue(),
                node.path("configurationSha256").textValue(), node.path("approvalMode").textValue(), Boolean.parseBoolean(policy.detail()));
            var snapshot = new DeploymentAgentActionLog.Snapshot(identity, node.path("state").textValue(), semantics);
            var layoutNode = JSON.readTree(one(prefix, "REMOTE_LAYOUT").detail()).path("layout");
            var layout = DeploymentRemoteLayout.decode(layoutNode.toString());
            var captured = capture(task, snapshot, layout, prefix).orElseThrow();
            String request = node.path("requestSha256").textValue();
            String result = node.path("resultSha256").textValue();
            return event.action().equals(captured.preparation().action()) && captured.preparation().id() < event.id()
                && request.equals(DeploymentAgentPreparationProbe.requestDigest(captured))
                && result.equals(hash(DeploymentAgentPreparationProbe.absentOutput(captured)))
                && proof(captured, request, result).equals(event.detail());
        } catch (Exception invalid) {
            return false;
        }
    }

    public static boolean matches(UUID task, DeploymentAgentActionLog.Identity identity, DeploymentRemoteLayout layout,
                                  DeploymentAgentActionLog.Event event, List<DeploymentAgentActionLog.Event> events) {
        if (!matches(event, events)) return false;
        try {
            var node = JSON.readTree(event.detail());
            return task.toString().equals(node.path("task").textValue()) && identity.server().equals(node.path("server").textValue())
                && identity.targetDigest().equals(node.path("targetSha256").textValue())
                && identity.configurationDigest().equals(node.path("configurationSha256").textValue())
                && identity.approvalMode().equals(node.path("approvalMode").textValue())
                && layout.digest().equals(node.path("layoutSha256").textValue());
        } catch (Exception invalid) {
            return false;
        }
    }

    public static String digest(List<DeploymentAgentActionLog.Event> events) {
        var array = JSON.createArrayNode();
        for (var event : events)
            array.add(JSON.createObjectNode().put("id", event.id()).put("type", event.type()).put("action", event.action()).put("detail", event.detail()));
        return hash(array.toString());
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static DeploymentAgentActionLog.Event one(List<DeploymentAgentActionLog.Event> events, String type) {
        var selected = events.stream().filter(event -> event.type().equals(type)).toList();
        if (selected.size() != 1) throw new IllegalArgumentException("Original preparation header is incomplete");
        return selected.getFirst();
    }
}
