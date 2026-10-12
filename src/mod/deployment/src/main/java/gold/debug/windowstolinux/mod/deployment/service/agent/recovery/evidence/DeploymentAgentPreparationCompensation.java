package gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPartialPreparationProbe;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentProjectChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentManagedUnitResolver;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;

/** Compensation of one uploaded original preparation; never a claim that preparation was not sent. */
public final class DeploymentAgentPreparationCompensation {
    public static final String EVENT = "PREPARE_COMPENSATED";

    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Set<String> BEFORE = Set.of("REMEMBER_AUTHORIZATION", "REMOTE_TARGET", "REMOTE_LAYOUT", "SOURCE",
        "TARGET_PROFILE", "AGENT_QUERY", "AGENT_ASSESSMENT", "AGENT_OBSERVATION", "DECISION", "PLAN", "PLAN_APPLICATION", "STATE");

    private DeploymentAgentPreparationCompensation() {
    }

    public enum Cleanup {SUCCEEDED, UNCONFIRMED, NOT_REQUIRED}

    public record Captured(UUID task, DeploymentAgentActionLog.Snapshot snapshot, DeploymentRemoteLayout layout,
                           DeploymentAgentActionLog.Event preparation, DeploymentRemoteWorkspace workspace,
                           String source, String plan, String profile, String endpoint, String application,
                           List<DeploymentAgentActionLog.Event> events) {
        public Captured {
            events = List.copyOf(events);
        }

        /** Reuses only fixed absence metadata checks; the old capture and proof policy remain unchanged. */
        public DeploymentAgentPreparationAbsence.Captured absenceBinding() {
            return new DeploymentAgentPreparationAbsence.Captured(task, snapshot, layout, preparation, workspace,
                source, plan, profile, endpoint, application, events);
        }
    }

    public static Optional<Captured> capture(UUID task, DeploymentAgentActionLog.Snapshot saved, DeploymentRemoteLayout layout,
                                             List<DeploymentAgentActionLog.Event> events) {
        try {
            if (saved.executionSemantics() != 4 || !Set.of("UNKNOWN", "CANCELLED").contains(saved.state())
                || events.isEmpty() || events.size() >= 10000) return Optional.empty();
            var prepare = one(events, "PREPARE_EXECUTING");
            var source = one(events, "SOURCE");
            var plan = one(events, "PLAN");
            var application = one(events, "PLAN_APPLICATION");
            var profile = one(events, "TARGET_PROFILE");
            var endpoint = one(events, "REMOTE_TARGET");
            var policy = one(events, "REMEMBER_AUTHORIZATION");
            var header = one(events, "REMOTE_LAYOUT");
            if (!prepare.detail().matches("[a-z0-9][a-z0-9-]{0,62}") || !digest(source.action()) || !digest(source.detail())
                || !plan.action().isEmpty() || !digest(plan.detail()) || !application.action().isEmpty()
                || !application.detail().matches("[a-z0-9][a-z0-9-]{0,62}") || !profile.action().isEmpty() || !digest(profile.detail())
                || !endpoint.action().isEmpty() || !digest(endpoint.detail())
                || !policy.action().equals("rememberAuthorization.v1") || !policy.detail().equals(Boolean.toString(saved.identity().rememberAuthorization()))
                || !prepare.action().equals(hash(task + ":" + prepare.detail() + ":" + source.detail())))
                return Optional.empty();
            for (var original : List.of(source, plan, application, profile, endpoint, policy, header))
                if (original.id() >= prepare.id()) return Optional.empty();
            var canonical = JSON.createObjectNode().put("formatVersion", 1).put("task", task.toString()).put("server", saved.identity().server());
            canonical.set("layout", JSON.readTree(layout.canonical()));
            canonical.put("layoutSha256", layout.digest());
            if (!header.action().equals(task.toString()) || !header.detail().equals(canonical.toString()))
                return Optional.empty();
            var workspace = new DeploymentRemoteWorkspace(DeploymentManagedUnitResolver.managedId(application.detail(), prepare.detail()), source.action(), layout);
            long prior = 0;
            long reviewEvent = 0;
            String review = null;
            DeploymentAgentActionLog.Event inspection = null;
            int readiness = 0;
            boolean ready = false;
            DeploymentAgentActionLog.Event state = null;
            for (var event : events) {
                if (event.id() <= prior || event.action() == null || event.detail() == null) return Optional.empty();
                prior = event.id();
                if (event.type().equals("STATE")) {
                    if (!event.action().isEmpty() || !Set.of("RUNNING", "WAITING", "UNKNOWN", "CANCELLED").contains(event.detail())
                        || event.id() > prepare.id() && !Set.of("UNKNOWN", "CANCELLED").contains(event.detail()))
                        return Optional.empty();
                    state = event;
                } else if (event.type().equals("PREPARE_EXECUTING")) {
                    if (!event.equals(prepare)) return Optional.empty();
                } else if (event.type().equals("HELPER_READINESS_OBSERVED")) {
                    if (event.id() <= prepare.id() || !digest(event.action()) || ++readiness > 2
                        || !Set.of("MISSING:HELPER_MISSING", "READY:CURRENT_TASK_READY").contains(event.detail()))
                        return Optional.empty();
                    if (ready || state != null && state.id() > prepare.id()) return Optional.empty();
                    ready = event.detail().equals("READY:CURRENT_TASK_READY");
                } else if (event.type().equals("RECOVERY_REVIEW_BOUND")) {
                    if (event.id() <= prepare.id() || review != null || !event.action().equals(saved.identity().configurationDigest())
                        || !digest(event.detail())) return Optional.empty();
                    review = event.detail();
                    reviewEvent = event.id();
                } else if (event.type().equals("RECOVERY_REVIEW_REBOUND")) {
                    if (review == null || !event.action().equals(saved.identity().configurationDigest()))
                        return Optional.empty();
                    String prefix = "previousEventId=" + reviewEvent + "\npreviousReviewRevision=" + review + "\nreviewRevision=";
                    if (!event.detail().startsWith(prefix)) return Optional.empty();
                    String selected = event.detail().substring(prefix.length());
                    if (!digest(selected) || selected.equals(review)) return Optional.empty();
                    review = selected;
                    reviewEvent = event.id();
                } else if (event.type().equals("INSPECT_REQUESTED")) {
                    if (event.id() <= prepare.id() || inspection != null || !event.action().equals(task.toString()))
                        return Optional.empty();
                    if (!UUID.fromString(event.detail()).toString().equals(event.detail())) return Optional.empty();
                    inspection = event;
                } else if (event.type().equals("INSPECT_RESULT")) {
                    if (inspection == null || !event.action().equals(Long.toString(inspection.id()))
                        || !(Set.of("{" + workspace.candidateId() + "=PRESENT}", "{" + workspace.candidateId() + "=ABSENT}",
                        "{" + workspace.candidateId() + "=BUILD_ACTIVE}").contains(event.detail())
                        || event.detail().matches("UNCONFIRMED:[A-Za-z][A-Za-z0-9]{0,79}"))) return Optional.empty();
                    inspection = null;
                } else if (event.id() >= prepare.id() || !BEFORE.contains(event.type())) return Optional.empty();
            }
            if (!ready || inspection != null || state == null || state.id() <= prepare.id() || !state.detail().equals(saved.state()))
                return Optional.empty();
            return Optional.of(new Captured(task, saved, layout, prepare, workspace, source.detail(), plan.detail(), profile.detail(), endpoint.detail(), application.detail(), events));
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    /** Local references are checked in the same original-task transaction as the proof. */
    public static void requireNoBusinessReference(Connection connection, Captured captured) throws SQLException {
        for (var selected : List.of(
            List.of("SELECT execution_id FROM deployment_execution WHERE platform_task_id=?", captured.task()),
            List.of("SELECT application_id FROM managed_application WHERE application_id=?", captured.application()),
            List.of("SELECT deployment_unit_id FROM deployment_unit WHERE deployment_unit_id=?", captured.workspace().applicationId()))) {
            try (var query = connection.prepareStatement((String) selected.getFirst())) {
                query.setObject(1, selected.get(1));
                try (var rows = query.executeQuery()) {
                    if (rows.next())
                        throw new SQLException("Original partial preparation has a formal business reference");
                }
            }
        }
    }

    public static String proof(Captured captured, boolean beforePresent, Cleanup cleanup, String cleanupRequest) {
        if (beforePresent == (cleanup == Cleanup.NOT_REQUIRED))
            throw new IllegalArgumentException("Cleanup observation differs from pre-observation");
        String expectedCleanup = beforePresent ? DeploymentAgentProjectChannel.originalCleanupRequestDigest(captured.task(),
            captured.snapshot().identity().server(), captured.snapshot().identity().configurationDigest(), captured.workspace()) : "";
        if (!expectedCleanup.equals(cleanupRequest))
            throw new IllegalArgumentException("Cleanup request is not the captured original action");
        var identity = captured.snapshot().identity();
        return JSON.createObjectNode().put("formatVersion", 1).put("task", captured.task().toString())
            .put("server", identity.server()).put("targetSha256", identity.targetDigest()).put("configurationSha256", identity.configurationDigest())
            .put("approvalMode", identity.approvalMode()).put("state", captured.snapshot().state()).put("layoutSha256", captured.layout().digest())
            .put("profileSha256", captured.profile()).put("endpointSha256", captured.endpoint()).put("sourceSha256", captured.source())
            .put("archiveSha256", captured.workspace().archiveSha256()).put("planSha256", captured.plan()).put("application", captured.application())
            .put("component", captured.preparation().detail()).put("candidate", captured.workspace().candidateId())
            .put("prepareEventId", captured.preparation().id()).put("prepareAction", captured.preparation().action())
            .put("priorEventsSha256", DeploymentAgentPreparationAbsence.digest(captured.events()))
            .put("helperSha256", DeploymentManagedHelperBundle.helperSha256(captured.layout()))
            .put("beforeRequestSha256", DeploymentAgentPartialPreparationProbe.requestDigest(captured, false))
            .put("beforeResultSha256", hash(beforePresent ? DeploymentAgentPartialPreparationProbe.presentOutput(captured)
                : DeploymentAgentPartialPreparationProbe.absentOutput(captured)))
            .put("cleanup", cleanup.name()).put("cleanupRequestSha256", cleanupRequest)
            .put("afterRequestSha256", DeploymentAgentPartialPreparationProbe.requestDigest(captured, true))
            .put("afterResultSha256", hash(DeploymentAgentPartialPreparationProbe.absentOutput(captured))).toString();
    }

    public static boolean matches(DeploymentAgentActionLog.Event event, List<DeploymentAgentActionLog.Event> events) {
        try {
            if (!event.type().equals(EVENT) || event.detail().length() > 8192) return false;
            var node = JSON.readTree(event.detail());
            if (!node.isObject() || !node.path("formatVersion").isInt() || node.path("formatVersion").intValue() != 1)
                return false;
            UUID task = UUID.fromString(node.path("task").textValue());
            var prefix = events.stream().filter(value -> value.id() < event.id()).toList();
            var policy = one(prefix, "REMEMBER_AUTHORIZATION");
            if (!Set.of("true", "false").contains(policy.detail())) return false;
            var identity = new DeploymentAgentActionLog.Identity(node.path("server").textValue(), node.path("targetSha256").textValue(),
                node.path("configurationSha256").textValue(), node.path("approvalMode").textValue(), Boolean.parseBoolean(policy.detail()));
            var layout = DeploymentRemoteLayout.decode(JSON.readTree(one(prefix, "REMOTE_LAYOUT").detail()).path("layout").toString());
            var captured = capture(task, new DeploymentAgentActionLog.Snapshot(identity, node.path("state").textValue(), 4), layout, prefix).orElseThrow();
            var suffix = events.stream().filter(value -> value.id() > event.id()).toList();
            String outcome = captured.snapshot().state().equals("CANCELLED") ? "CANCELLED" : "FAILED";
            if (suffix.size() > 2 || !suffix.isEmpty() && (!suffix.getFirst().type().equals("AI_RECOVERY_SETTLED")
                || !suffix.getFirst().action().equals(task.toString())
                || !suffix.getFirst().detail().equals(outcome + ":COMMAND_NATIVE_DATABASE_SECRET_CANDIDATE_SOURCE_SETTLED"))
                || suffix.size() == 2 && (!suffix.getLast().type().equals("STATE") || !suffix.getLast().action().isEmpty()
                || !suffix.getLast().detail().equals(outcome))) return false;
            Cleanup cleanup = Cleanup.valueOf(node.path("cleanup").textValue());
            return event.action().equals(captured.preparation().action()) && event.id() > captured.preparation().id()
                && proof(captured, cleanup != Cleanup.NOT_REQUIRED, cleanup, node.path("cleanupRequestSha256").textValue()).equals(event.detail());
        } catch (Exception invalid) {
            return false;
        }
    }

    public static boolean matches(UUID task, DeploymentAgentActionLog.Identity identity, DeploymentRemoteLayout layout,
                                  DeploymentAgentActionLog.Event event, List<DeploymentAgentActionLog.Event> events) {
        if (!matches(event, events)) return false;
        try {
            var n = JSON.readTree(event.detail());
            return n.path("task").textValue().equals(task.toString()) && n.path("server").textValue().equals(identity.server())
                && n.path("targetSha256").textValue().equals(identity.targetDigest()) && n.path("configurationSha256").textValue().equals(identity.configurationDigest())
                && n.path("approvalMode").textValue().equals(identity.approvalMode()) && n.path("layoutSha256").textValue().equals(layout.digest());
        } catch (Exception invalid) {
            return false;
        }
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String hash(String value) {
        return DeploymentAgentPreparationAbsence.hash(value);
    }

    private static DeploymentAgentActionLog.Event one(List<DeploymentAgentActionLog.Event> events, String type) {
        var found = events.stream().filter(event -> event.type().equals(type)).toList();
        if (found.size() != 1)
            throw new IllegalArgumentException("Original preparation header is missing or duplicated");
        return found.getFirst();
    }
}
