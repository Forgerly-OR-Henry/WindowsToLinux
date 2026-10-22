package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;

/** Agent-only proof of a complete independent refusal before a grant or dispatch. */
public final class DeploymentAgentUnsentReview {
    public static final String EVENT = "ACTION_REVIEW_NOT_SENT";
    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> FIELDS = Set.of("formatVersion", "task", "server", "kind", "scopeSha256",
        "requestSha256", "reviewSha256", "decision", "dispatch", "authority");

    private DeploymentAgentUnsentReview() { }

    public static void requireDifferent(DeploymentAgentActionLog journal, TaskContext task, ServerId server,
                                        String kind, String scope) throws Exception {
        requireActive(task);
        String binding = key(task, server, kind, hash(scope));
        try {
            if (!journal.find(task).orElseThrow().identity().server().equals(server.id()))
                throw new IllegalArgumentException("Original Agent refusal target changed");
            boolean found = false;
            for (var event : journal.events(task)) {
                if (!event.type().equals(EVENT)) continue;
                JsonNode proof = JSON.readTree(event.detail());
                var fields = new HashSet<String>(); proof.fieldNames().forEachRemaining(fields::add);
                if (!proof.isObject() || !fields.equals(FIELDS) || !proof.path("formatVersion").isInt()
                    || proof.path("formatVersion").intValue() != 1
                    || !proof.path("task").asText().equals(task.id().toString())
                    || !proof.path("server").asText().equals(server.id())
                    || !Set.of("COMMAND", "PATCH", "ISOLATION").contains(proof.path("kind").asText())
                    || !proof.path("decision").asText().equals("DENY") || !proof.path("dispatch").asText().equals("NOT_SENT")
                    || !proof.path("authority").asText().equals("NONE")
                    || List.of("scopeSha256", "requestSha256", "reviewSha256").stream()
                        .anyMatch(field -> !proof.path(field).isTextual() || !proof.path(field).asText().matches("[0-9a-f]{64}"))
                    || !event.action().equals(key(task, server, proof.path("kind").asText(), proof.path("scopeSha256").asText())))
                    throw new IllegalArgumentException("Original unsent refusal evidence changed");
                if (event.action().equals(binding)) {
                    if (found) throw new IllegalArgumentException("Duplicate original unsent refusal");
                    found = true;
                }
            }
            if (found) throw new DeploymentAgentAlternativeRequiredException(task,
                "The same refused action cannot be submitted again; choose a different proposal.");
        } catch (DeploymentAgentAlternativeRequiredException refused) {
            throw refused;
        } catch (Exception failure) {
            throw unknown(task, failure);
        }
    }

    public static DeploymentAgentAlternativeRequiredException command(DeploymentAgentActionLog journal,
            TaskContext task, ServerId server, String kind, String scope, ActionIntent intent,
            CommandRequest request, ApiException denied) throws Exception {
        var refusal = DeploymentAgentAlternativeRequiredException.fromReview(task, denied);
        var actual = RemoteActions.command(server, intent.revision(), request).withSemantics(intent.semantics().orElseThrow());
        if (!actual.equals(intent)) throw new IllegalArgumentException("Unsent refusal request differs from the actual command");
        record(journal, task, server, kind, scope, hash(intent.exactAction()), hash(DeploymentAiJson.facts(Map.of(
            "code", denied.problem().code().name(), "event", denied.problem().eventCode(), "facts", denied.problem().context()), 65536)));
        return refusal;
    }

    public static DeploymentAgentAlternativeRequiredException action(DeploymentAgentActionLog journal,
            TaskContext task, ServerId server, String kind, String scope, String revision,
            Map<String, ?> facts, DeploymentReviewDecision decision) throws Exception {
        if (!decision.decision().equals("DENY")) throw new IllegalArgumentException("Only complete DENY can prove an unsent refusal");
        record(journal, task, server, kind, scope, hash(DeploymentAiJson.facts(Map.of(
            "server", server.id(), "kind", kind, "revision", revision, "facts", facts), 131072)),
            hash(DeploymentAiJson.facts(Map.of("decision", decision.decision(),
                "requiresUserConfirmation", decision.requiresUserConfirmation(), "reason", decision.reason()), 16384)));
        return new DeploymentAgentAlternativeRequiredException(task, DeploymentAgentReview.denialReason(decision.reason()));
    }

    private static void record(DeploymentAgentActionLog journal, TaskContext task, ServerId server, String kind,
                                String scope, String request, String review) throws Exception {
        requireActive(task);
        requireDifferent(journal, task, server, kind, scope);
        try {
            var proof = JSON.createObjectNode().put("formatVersion", 1).put("task", task.id().toString())
                .put("server", server.id()).put("kind", kind).put("scopeSha256", hash(scope))
                .put("requestSha256", request).put("reviewSha256", review)
                .put("decision", "DENY").put("dispatch", "NOT_SENT").put("authority", "NONE");
            journal.event(task, EVENT, key(task, server, kind, hash(scope)), JSON.writeValueAsString(proof));
            requireActive(task);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            throw unknown(task, failure);
        }
    }

    private static String key(TaskContext task, ServerId server, String kind, String scopeSha) {
        if (!Set.of("COMMAND", "PATCH", "ISOLATION").contains(kind)) throw new IllegalArgumentException("Unknown Agent refusal kind");
        return hash(task.id() + "\n" + server.id() + "\n" + kind + "\n" + scopeSha);
    }

    private static void requireActive(TaskContext task) {
        if (task.cancelToken().requested() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static ApiException unknown(TaskContext task, Exception failure) {
        var error = new ApiException(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_REFUSAL_EVIDENCE_UNCONFIRMED",
            "Original unsent refusal could not be confirmed", Map.of("task", task.id().toString()), Optional.of(task.id())));
        error.initCause(failure);
        return error;
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
