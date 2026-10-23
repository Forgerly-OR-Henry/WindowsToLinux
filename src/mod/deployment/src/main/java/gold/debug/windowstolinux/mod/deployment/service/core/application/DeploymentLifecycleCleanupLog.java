package gold.debug.windowstolinux.mod.deployment.service.core.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Separate STOP/disable receipts under an unchanged original UNKNOWN action. No schema or task identity is replaced. */
final class DeploymentLifecycleCleanupLog extends DeploymentManagementActionLog {
    static final String FIELD = "lifecycleCleanup";
    private final DeploymentManagementActionLog original;
    private final Entry anchor;
    private final String action;
    private final boolean confirmed;

    DeploymentLifecycleCleanupLog(ModContext context, Entry anchor, String action, boolean confirmed) {
        super(context);
        if (!anchor.action().equals("LIFECYCLE_RESTART") || !Set.of("EFFECTING", "UNKNOWN").contains(anchor.phase())
            || !Set.of("STOP", "DISABLE_AUTOSTART").contains(action))
            throw new IllegalArgumentException("Original uncertain RESTART cleanup required");
        this.original = new DeploymentManagementActionLog(context);
        this.anchor = anchor;
        this.action = action;
        this.confirmed = confirmed;
    }

    private Entry parent(TaskContext task) throws ApiException {
        var current = original.find(task).orElseThrow(DeploymentLifecycleCleanupLog::changed);
        if (!current.id().equals(anchor.id()) || !current.phase().equals(anchor.phase())
            || !current.taskId().equals(anchor.taskId()) || !current.ownerId().equals(anchor.ownerId())
            || !current.ownerVersion().equals(anchor.ownerVersion()) || !current.applicationId().equals(anchor.applicationId())
            || !current.server().equals(anchor.server()) || !current.action().equals(anchor.action())
            || !current.currentRevision().equals(anchor.currentRevision()) || !current.targetRevision().equals(anchor.targetRevision())
            || !withoutCleanup(current).equals(withoutCleanup(anchor))) throw changed();
        return current;
    }

    private static JsonNode withoutCleanup(Entry entry) {
        var facts = copy(entry); facts.remove(FIELD);
        return facts;
    }

    Map<String, ?> evidence(TaskContext task) throws ApiException {
        var parent = parent(task);
        return Map.of("originalTaskId", parent.taskId().toString(), "originalOperationId", parent.id().toString(),
            "originalAction", parent.action(), "originalOutcome", "UNKNOWN", "originalPhase", parent.phase(),
            "originalFacts", withoutCleanup(parent), "cleanupAction", action,
            "userConfirmedCleanup", confirmed, "meaning", "New independently reviewed cleanup only; no replay or proof of original RESTART success");
    }

    gold.debug.windowstolinux.appapi.linux.session.RemoteTarget originalTarget(TaskContext task) throws ApiException {
        return target(parent(task), task);
    }

    @Override
    public Optional<Entry> find(TaskContext task) throws ApiException {
        var parent = parent(task);
        var receipt = parent.facts().path(FIELD);
        if (receipt.isMissingNode()) return Optional.empty();
        if (receipt.path("formatVersion").asInt() != 1
            || !receipt.path("originalOperationId").asText().equals(parent.id().toString())
            || !receipt.path("confirmedTaskId").asText().equals(task.id().toString())
            || !receipt.path("originalOutcome").asText().equals("UNKNOWN")
            || !receipt.path("confirmedAt").isIntegralNumber() || !receipt.path("operations").isObject()) throw changed();
        var operation = receipt.path("operations").path(action);
        if (operation.isMissingNode()) return Optional.empty();
        if (!Set.of("REVIEWED", "EFFECTING", "UNKNOWN", "FINISHED").contains(operation.path("phase").asText())
            || operation.path("facts").has(FIELD)) throw changed();
        var entry = view(parent, operation.path("phase").asText(), operation.path("facts"));
        try { validateLifecycleEntry(entry, task); }
        catch (IllegalArgumentException invalid) { throw changed(); }
        return Optional.of(entry);
    }

    private Entry view(Entry parent, String phase, JsonNode facts) {
        var id = UUID.nameUUIDFromBytes((parent.id() + ":cleanup:" + action).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Entry(id, parent.taskId(), parent.ownerId(), parent.ownerVersion(), parent.applicationId(),
            parent.server(), "LIFECYCLE_" + action, parent.currentRevision(), Optional.empty(), phase, facts.deepCopy());
    }

    @Override
    public Entry begin(String application, ServerId server, String operation, String revision,
                       Optional<String> target, JsonNode facts, TaskContext task) throws ApiException {
        var parent = parent(task);
        if (!application.equals(parent.applicationId()) || !server.equals(parent.server())
            || !operation.equals("LIFECYCLE_" + action) || !revision.equals(parent.currentRevision()) || target.isPresent()
            || review(view(parent, "REVIEWED", facts)).isEmpty() || selected(view(parent, "REVIEWED", facts)).isPresent()
            || !facts.path("target").equals(parent.facts().path("target"))) throw changed();
        var prior = find(task);
        if (prior.isPresent()) {
            if (!immutable(prior.orElseThrow().facts()).equals(immutable(facts))) throw changed();
            return prior.orElseThrow();
        }
        if (!confirmed) throw changed();
        var entry = view(parent, "REVIEWED", facts);
        validateLifecycleEntry(entry, task);
        save(parent, entry, task);
        return entry;
    }

    @Override
    public Entry advance(Entry entry, String phase, JsonNode facts, TaskContext task) throws ApiException {
        var current = find(task).orElseThrow(DeploymentLifecycleCleanupLog::changed);
        if (!current.equals(entry)) throw changed();
        requireProgress(entry, phase, facts);
        var next = view(parent(task), phase, facts);
        validateLifecycleEntry(next, task);
        save(parent(task), next, task);
        return next;
    }

    private void save(Entry parent, Entry entry, TaskContext task) throws ApiException {
        var facts = copy(parent);
        ObjectNode receipt;
        if (facts.has(FIELD)) receipt = facts.withObject(FIELD);
        else {
            receipt = facts.putObject(FIELD).put("formatVersion", 1).put("originalOperationId", parent.id().toString())
                .put("confirmedTaskId", task.id().toString()).put("confirmedAt", Instant.now().toEpochMilli())
                .put("originalOutcome", "UNKNOWN");
            receipt.putObject("operations");
        }
        receipt.withObject("operations").putObject(action).put("phase", entry.phase()).set("facts", entry.facts());
        original.advance(parent, parent.phase(), facts, task);
    }

    static void requireRetained(Entry parent, JsonNode next) throws ApiException {
        var before = parent.facts().path(FIELD);
        var after = next.path(FIELD);
        if (!after.isObject()) throw changed();
        var identity = (ObjectNode) before.deepCopy(); identity.remove("operations");
        var nextIdentity = (ObjectNode) after.deepCopy(); nextIdentity.remove("operations");
        if (!identity.equals(nextIdentity)) throw changed();
        var names = before.path("operations").fieldNames();
        while (names.hasNext()) {
            String action = names.next();
            var old = before.path("operations").path(action);
            var current = after.path("operations").path(action);
            if (!current.isObject()) throw changed();
            var entry = new Entry(parent.id(), parent.taskId(), parent.ownerId(), parent.ownerVersion(), parent.applicationId(),
                parent.server(), "LIFECYCLE_" + action, parent.currentRevision(), Optional.empty(), old.path("phase").asText(), old.path("facts"));
            requireProgress(entry, current.path("phase").asText(), current.path("facts"));
            if (entry.phase().equals("FINISHED") && !current.path("phase").asText().equals("FINISHED")) throw changed();
        }
    }

    private static ApiException changed() {
        return new ApiException(new Problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_ORIGINAL_CLEANUP_CHANGED",
            "Original cleanup identity or receipt changed", Map.of(), Optional.empty()));
    }
}
