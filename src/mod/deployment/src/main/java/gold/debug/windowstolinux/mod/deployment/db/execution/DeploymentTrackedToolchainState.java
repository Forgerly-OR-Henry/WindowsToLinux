package gold.debug.windowstolinux.mod.deployment.db.execution;

import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;

/** Bounded original helper evidence retained inside the existing execution request. */
public final class DeploymentTrackedToolchainState {
    private final Identity identity;
    private final DeploymentAssistedBuildIntent originalIntent;
    private final Optional<ScriptRecipe> recipe;
    private final List<Operation> operations;

    private DeploymentTrackedToolchainState(Identity identity, DeploymentAssistedBuildIntent originalIntent,
                                            Optional<ScriptRecipe> recipe, List<Operation> operations) {
        this.identity = identity;
        this.originalIntent = originalIntent;
        this.recipe = recipe;
        this.operations = List.copyOf(operations);
    }

    public Identity identity() { return identity; }
    public DeploymentAssistedBuildIntent originalIntent() { return originalIntent; }
    public Optional<ScriptRecipe> recipe() { return recipe; }
    public List<Operation> operations() { return operations; }
    public boolean allSettled() { return operations.stream().allMatch(Operation::settled); }

    public record Event(String verb, String event, Optional<Observation> observation, Instant observedAt) { }

    public record Operation(Request request, List<Event> events) {
        public Operation { events = List.copyOf(events); }
        public boolean dispatched(String verb) {
            return events.stream().anyMatch(value -> value.verb().equals(verb) && value.event().equals("DISPATCHED"));
        }
        public Optional<Observation> latestObservation() {
            for (int index = events.size() - 1; index >= 0; index--)
                if (events.get(index).event().equals("OBSERVED")) return events.get(index).observation();
            return Optional.empty();
        }
        public boolean settled() {
            if (events.stream().noneMatch(value -> value.event().equals("DISPATCHED"))) return true;
            for (String verb : List.of("prepare-tracked", "bind-tracked", "cleanup-tracked")) {
                int lastDispatch = -1, lastObserved = -1;
                for (int index = 0; index < events.size(); index++) {
                    var value = events.get(index);
                    if (!value.verb().equals(verb)) continue;
                    if (value.event().equals("DISPATCHED")) lastDispatch = index;
                    if (value.event().equals("OBSERVED")) lastObserved = index;
                }
                if (lastDispatch > lastObserved && events.stream().skip(lastDispatch + 1L)
                    .noneMatch(value -> value.event().equals("OBSERVED") && value.observation().isPresent())) return false;
            }
            return latestObservation().filter(value -> value.effectState() != EffectState.UNSETTLED
                && value.cleanupState() != CleanupState.REQUIRED).isPresent();
        }
    }

    public static Optional<DeploymentTrackedToolchainState> read(DeploymentExecutionRecord record,
                                                                String originalIntentSha256) throws SQLException {
        DeploymentExecutionCodec.known(record);
        if (originalIntentSha256 == null || !originalIntentSha256.matches("[0-9a-f]{64}")) throw invalid();
        var request = parse(record.requestJson(), DeploymentExecutionCodec.READ_LIMIT);
        return decodeAll(record, request).stream()
            .filter(value -> value.identity.originalIntentSha256().equals(originalIntentSha256)).findFirst();
    }

    static List<DeploymentTrackedToolchainState> decodeAll(DeploymentExecutionRecord record, JsonNode request)
        throws SQLException {
        if (!request.has("trackedToolchains")) return List.of();
        try {
            if (record.identity().mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.ASSISTED
                || !string(request, "operationType").equals("deployment-publication") || record.phase().equals("PREPARED")) throw invalid();
            var root = request.path("trackedToolchains");
            fields(root, Set.of("formatVersion", "attempts"));
            integer(root, "formatVersion", 1, 1);
            var attempts = array(root, "attempts", 2);
            if (attempts.isEmpty()) throw invalid();
            var states = new ArrayList<DeploymentTrackedToolchainState>();
            var operationIds = new HashSet<UUID>();
            var attemptIds = new HashSet<Integer>();
            for (var attempt : attempts) {
                fields(attempt, Set.of("identity", "originalIntent", "recipe", "operations", "reconciledResultSha256"));
                var frame = attempt.path("identity");
                fields(frame, Set.of("executionId", "unitId", "recoveryAttempt", "originalIntentSha256", "fingerprintSha256"));
                var identity = new Identity(string(frame, "executionId"), string(frame, "unitId"), integer(frame, "recoveryAttempt", 1, 2),
                    string(frame, "originalIntentSha256"), string(frame, "fingerprintSha256"));
                var intent = DeploymentAssistedBuildIntent.decode(attempt.path("originalIntent"));
                var reviewedLayout = DeploymentAssistedBuildReviewCodec.decodeDescription(intent.nextPlan()).layout();
                if (!identity.executionId().equals(record.identity().executionId().toString()) || !identity.unitId().equals(intent.unitId())
                    || identity.recoveryAttempt() != intent.attempt() || !attemptIds.add(intent.attempt())
                    || !identity.originalIntentSha256().equals(sha(intent.encode()))) throw invalid();
                var review = matchingReview(request, intent);
                boolean pending = request.path("buildIntent").equals(attempt.path("originalIntent"));
                if (!pending && review.isEmpty()) throw invalid();
                String reconciled = nullable(attempt, "reconciledResultSha256");
                if (reconciled != null && (!reconciled.matches("[0-9a-f]{64}") || review.isEmpty())) throw invalid();
                Optional<ScriptRecipe> recipe = Optional.empty();
                if (!attempt.path("recipe").isNull()) {
                    var value = attempt.path("recipe");
                    fields(value, Set.of("formatVersion", "toolchainEnvironmentFormatVersion", "prefix", "suffix"));
                    recipe = Optional.of(new ScriptRecipe(integer(value, "formatVersion", 1, 1),
                        integer(value, "toolchainEnvironmentFormatVersion", 1, 1), string(value, "prefix"), string(value, "suffix")));
                }
                var operations = new ArrayList<Operation>();
                String layout = null;
                for (var operation : array(attempt, "operations", 16)) {
                    fields(operation, Set.of("requestBase64", "requestSha256", "events"));
                    String encoded = string(operation, "requestBase64");
                    if (encoded.length() > 4 * ((DeploymentTrackedToolchains.MAX_REQUEST_BYTES + 2) / 3)) throw invalid();
                    byte[] bytes = Base64.getDecoder().decode(encoded);
                    if (bytes.length == 0 || bytes.length > DeploymentTrackedToolchains.MAX_REQUEST_BYTES
                        || !Base64.getEncoder().encodeToString(bytes).equals(encoded)) throw invalid();
                    var original = DeploymentTrackedToolchains.decode(bytes);
                    if (!Arrays.equals(bytes, original.bytes()) || !original.sha256().equals(string(operation, "requestSha256"))
                        || !original.identity().equals(identity) || !original.taskId().equals(record.identity().taskId().toString())
                        || !original.serverId().equals(record.identity().server().id()) || !operationIds.add(original.operationId())
                        || !original.layout().equals(reviewedLayout)
                        || layout != null && !layout.equals(original.layout().canonical()) || recipe.isEmpty()) throw invalid();
                    layout = original.layout().canonical();
                    var events = new ArrayList<Event>();
                    Instant previous = null;
                    for (var event : array(operation, "events", 16)) {
                        fields(event, Set.of("verb", "event", "observation", "observedAt"));
                        String verb = string(event, "verb"), kind = string(event, "event");
                        Optional<Observation> observation = event.path("observation").isNull() ? Optional.empty()
                            : Optional.of(DeploymentTrackedToolchains.observation((event.path("observation").toString() + "\n")
                                .getBytes(StandardCharsets.UTF_8), original));
                        if (!event.path("observedAt").isIntegralNumber() || !event.path("observedAt").canConvertToLong()) throw invalid();
                        var at = Instant.ofEpochMilli(event.path("observedAt").longValue());
                        if (at.isBefore(record.createdAt()) || at.isAfter(record.updatedAt()) || previous != null && at.isBefore(previous)) throw invalid();
                        validateEvent(original, events, verb, kind, observation);
                        events.add(new Event(verb, kind, observation, at));
                        previous = at;
                    }
                    if (events.isEmpty()) throw invalid();
                    operations.add(new Operation(original, events));
                }
                if (operations.stream().filter(value -> value.request().kind() == Kind.BIND).count() > 1) throw invalid();
                var state = new DeploymentTrackedToolchainState(identity, intent, recipe, operations);
                if (review.isPresent()) state.requireReview(review.orElseThrow());
                states.add(state);
            }
            return List.copyOf(states);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Invalid retained tracked toolchain evidence", invalid);
        }
    }

    static String append(DeploymentExecutionRecord record, Identity identity, Request original, String verb,
                         String event, JsonNode observation, ScriptRecipe recipe, Instant observedAt) throws SQLException {
        var request = parse(record.requestJson(), DeploymentExecutionCodec.READ_LIMIT);
        var states = decodeAll(record, request);
        boolean active = record.phase().equals("EXECUTING") && record.outcome().isEmpty();
        if (!active && record.outcome().orElse(null) != TaskResult.UNKNOWN || record.businessCommitted() || record.cleanupComplete()) throw invalid();
        if (!active && !(Set.of("observe-tracked", "cleanup-tracked").contains(Objects.requireNonNullElse(verb, "")))) throw invalid();
        var pending = request.path("buildIntent");
        if (pending.isNull()) throw invalid();
        var intent = DeploymentAssistedBuildIntent.decode(pending);
        if (!identity.executionId().equals(record.identity().executionId().toString()) || !identity.unitId().equals(intent.unitId())
            || identity.recoveryAttempt() != intent.attempt() || !identity.originalIntentSha256().equals(sha(intent.encode()))) throw invalid();
        ObjectNode root;
        if (request.has("trackedToolchains")) root = (ObjectNode) request.path("trackedToolchains");
        else { root = request.putObject("trackedToolchains").put("formatVersion", 1); root.putArray("attempts"); }
        ObjectNode attempt = null;
        for (var value : root.withArray("attempts"))
            if (value.path("identity").path("originalIntentSha256").asText().equals(identity.originalIntentSha256())) attempt = (ObjectNode) value;
        if (attempt == null) {
            if (!active || !event.equals("RECIPE") || states.size() >= 2) throw invalid();
            attempt = root.withArray("attempts").addObject();
            attempt.putObject("identity").put("executionId", identity.executionId()).put("unitId", identity.unitId())
                .put("recoveryAttempt", identity.recoveryAttempt()).put("originalIntentSha256", identity.originalIntentSha256())
                .put("fingerprintSha256", identity.fingerprintSha256());
            attempt.set("originalIntent", pending.deepCopy()); attempt.putNull("recipe");
            attempt.putArray("operations"); attempt.putNull("reconciledResultSha256");
        } else if (states.stream().filter(value -> value.identity.originalIntentSha256().equals(identity.originalIntentSha256()))
            .noneMatch(value -> value.identity.equals(identity))) throw invalid();
        if (event.equals("RECIPE")) {
            if (original != null || verb != null || observation != null || recipe == null || !active) throw invalid();
            var value = object().put("formatVersion", recipe.version()).put("toolchainEnvironmentFormatVersion", recipe.toolchainEnvironmentFormatVersion())
                .put("prefix", recipe.prefix()).put("suffix", recipe.suffix());
            if (!attempt.path("recipe").isNull() && !attempt.path("recipe").equals(value)) throw invalid();
            attempt.set("recipe", value);
        } else {
            if (original == null || recipe != null || !original.identity().equals(identity) || attempt.path("recipe").isNull()) throw invalid();
            ObjectNode operation = null;
            for (var value : attempt.withArray("operations"))
                if (value.path("requestSha256").asText().equals(original.sha256())) operation = (ObjectNode) value;
            if (operation == null) {
                if (!active || !event.equals("INTENT") || !(verb.equals("prepare-tracked") || verb.equals("bind-tracked"))) throw invalid();
                operation = attempt.withArray("operations").addObject()
                    .put("requestBase64", Base64.getEncoder().encodeToString(original.bytes())).put("requestSha256", original.sha256());
                operation.putArray("events");
            } else if (!operation.path("requestBase64").textValue().equals(Base64.getEncoder().encodeToString(original.bytes()))) throw invalid();
            if (!active && (verb.equals("prepare-tracked") || verb.equals("bind-tracked"))) throw invalid();
            String persistedEvent = event;
            if (event.equals("OBSERVED") && observation == null) {
                JsonNode previous = null;
                for (var value : operation.withArray("events"))
                    if (value.path("verb").asText().equals(verb)) previous = value;
                if (previous != null && previous.path("event").asText().equals("INTENT")) persistedEvent = "NOT_SENT";
            }
            var value = operation.withArray("events").addObject().put("verb", verb).put("event", persistedEvent).put("observedAt", observedAt.toEpochMilli());
            value.set("observation", observation == null ? com.fasterxml.jackson.databind.node.NullNode.instance : observation.deepCopy());
        }
        return DeploymentPublishAttemptCodec.encodeBounded(request);
    }

    static String reconcile(DeploymentExecutionRecord record, DeploymentAssistedBuildReview review) throws SQLException {
        var pending = DeploymentExecutionCodec.buildIntent(record).orElseThrow(DeploymentTrackedToolchainState::invalid);
        if (!pending.matches(review)) throw invalid();
        var state = read(record, sha(pending.encode())).orElseThrow(DeploymentTrackedToolchainState::invalid);
        state.requireReview(review);
        var request = parse(DeploymentExecutionCodec.withBuildReview(record, review), DeploymentExecutionCodec.READ_LIMIT);
        for (var attempt : request.path("trackedToolchains").path("attempts"))
            if (attempt.path("identity").path("originalIntentSha256").textValue().equals(state.identity.originalIntentSha256()))
                ((ObjectNode) attempt).put("reconciledResultSha256", sha(record.resultJson().orElseThrow(DeploymentTrackedToolchainState::invalid)));
        return DeploymentPublishAttemptCodec.encodeBounded(request);
    }

    private void requireReview(DeploymentAssistedBuildReview review) throws SQLException {
        if (!originalIntent.matches(review) || recipe.isEmpty() || operations.isEmpty()
            || operations.stream().anyMatch(value -> !value.settled())) throw invalid();
        var bindings = operations.stream().filter(value -> value.request().kind() == Kind.BIND
            && value.dispatched("bind-tracked") && value.latestObservation().filter(Observation::bound).isPresent()).toList();
        if (bindings.size() != 1) throw invalid();
        var binding = bindings.getFirst().request().binding();
        if (!DeploymentToolchainBindingCodec.encode(binding).equals(review.nextTools()) || !recipe.orElseThrow().render(binding).equals(review.nextScript())) throw invalid();
    }

    static DeploymentExecutionIdentity resultIdentity(DeploymentExecutionRecord record, JsonNode request, JsonNode result)
        throws SQLException {
        var current = record.identity();
        if (string(result, "reviewedRevision").equals(current.reviewedRevision())) return current;
        if (record.outcome().orElse(null) != TaskResult.UNKNOWN || record.businessCommitted() || record.cleanupComplete()) throw invalid();
        var states = decodeAll(record, request);
        for (var state : states) {
            if (!state.originalIntent.previousRoot().equals(string(result, "reviewedRevision"))) continue;
            for (var attempt : request.path("trackedToolchains").path("attempts"))
                if (attempt.path("identity").path("originalIntentSha256").textValue().equals(state.identity.originalIntentSha256())
                    && sha(record.resultJson().orElseThrow(DeploymentTrackedToolchainState::invalid)).equals(nullable(attempt, "reconciledResultSha256")))
                    return new DeploymentExecutionIdentity(current.executionId(), current.taskId(), current.projectId(), current.applicationId(),
                        current.server(), current.mode(), state.originalIntent.previousRoot());
        }
        throw invalid();
    }

    private static Optional<DeploymentAssistedBuildReview> matchingReview(JsonNode request, DeploymentAssistedBuildIntent intent) throws SQLException {
        for (var entry : array(request, "buildReviews", 2)) {
            var value = DeploymentAssistedBuildReview.decode(entry);
            if (intent.matches(value)) return Optional.of(value);
        }
        return Optional.empty();
    }

    private static void validateEvent(Request request, List<Event> events, String verb, String event, Optional<Observation> observation) throws SQLException {
        DeploymentTrackedToolchains.command(verb, request);
        if (!Set.of("INTENT", "DISPATCHED", "OBSERVED", "NOT_SENT").contains(event) || !event.equals("OBSERVED") && observation.isPresent()) throw invalid();
        if (events.isEmpty() && (!event.equals("INTENT") || !(verb.equals("prepare-tracked") || verb.equals("bind-tracked")))) throw invalid();
        var previous = events.stream().filter(value -> value.verb().equals(verb)).toList();
        var last = previous.isEmpty() ? null : previous.getLast();
        if (event.equals("INTENT")) {
            if (!previous.isEmpty() && !verb.equals("observe-tracked")) throw invalid();
            if (verb.equals("cleanup-tracked") && events.stream().noneMatch(value -> value.event().equals("DISPATCHED"))) throw invalid();
        } else if (last == null || (event.equals("DISPATCHED") || event.equals("NOT_SENT")) && !last.event().equals("INTENT")
            || event.equals("OBSERVED") && !last.event().equals("DISPATCHED")) throw invalid();
    }

    private static int integer(JsonNode node, String field, int min, int max) throws SQLException {
        if (!node.path(field).isInt() || node.path(field).intValue() < min || node.path(field).intValue() > max) throw invalid();
        return node.path(field).intValue();
    }
    private static String nullable(JsonNode node, String field) throws SQLException { return node.path(field).isNull() ? null : string(node, field); }
    static String sha(String text) { return DeploymentTrackedToolchains.sha256(text.getBytes(StandardCharsets.UTF_8)); }
    private static SQLException invalid() { return new SQLException("Tracked toolchain evidence differs from the original assisted execution"); }
}
