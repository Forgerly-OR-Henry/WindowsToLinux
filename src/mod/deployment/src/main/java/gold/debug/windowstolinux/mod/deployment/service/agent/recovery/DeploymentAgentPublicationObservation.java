package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionRecord;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentHealthEvidence;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** One original-task status observation; durable facts never authorize publication or settlement. */
public final class DeploymentAgentPublicationObservation {
    public static final String BEGIN = "PUBLICATION_OBSERVATION_BEGIN";
    public static final String COMPONENT = "PUBLICATION_OBSERVATION_COMPONENT";
    public static final String END = "PUBLICATION_OBSERVATION_END";
    private static final Set<String> REASONS = Set.of("NONE", "APP_DENIED", "INVALID_OR_INCOMPLETE_RESULT",
        "REQUESTED_WITHOUT_VERIFIED_RESULT", "NOT_ATTEMPTED_AFTER_STOP");

    private DeploymentAgentPublicationObservation() {
    }

    /** Immutable decoded framing, not a remote or business validator. */
    public record Recorded(UUID task, UUID invocation, boolean observationComplete, int observedComponents,
                           List<String> componentFrames, long endEvent) {
        public Recorded { componentFrames = List.copyOf(componentFrames); }
    }

    public static OperationResult<Void> observe(DeploymentCommandReviewScope reviewed, RemoteSession session,
        ServerId server, TaskContext task, String revision, DeploymentExecutionRecord execution,
        DeploymentPublishResult proposal, Map<String, DeploymentGraphPublisher.Candidate> candidates,
        DeploymentAgentActionLog journal) throws Exception {
        var prefix = List.copyOf(journal.events(task));
        var evidence = DeploymentAgentHealthEvidence.forOriginalPublicationObservation(reviewed, session, server, task,
            revision, proposal, candidates, journal);
        require(prefix.equals(journal.events(task)), "Original observation prefix changed");
        require(execution.identity().taskId().equals(task.id()) && proposal.graph().components().size() <= 16,
            "Original observation identity changed");
        String invocation = UUID.randomUUID().toString();
        var header = frame("BEGIN", invocation).put("task", task.id().toString())
            .put("execution", execution.identity().executionId().toString()).put("server", server.id())
            .put("application", proposal.graph().applicationId()).put("reviewRevision", revision)
            .put("templateSha256", DeploymentAgentInputs.hash(execution.requestJson()))
            .put("proposalSha256", DeploymentAgentInputs.hash(proposal.toString()))
            .put("prefixEndEvent", prefix.getLast().id()).put("prefixSha256", prefixSha256(prefix));
        var order = header.putArray("components");
        proposal.graph().components().forEach(part -> order.add(part.componentId()));
        String headerText = encodeFrame(header);
        journal.event(task, BEGIN, invocation, headerText);
        long begin = journal.events(task).getLast().id();
        var publisher = new DeploymentGraphPublisher(evidence.context(), evidence, server, task,
            (type, action, detail) -> journal.event(task, type, action, detail));
        var queried = publisher.observeOriginal(proposal, candidates);
        var receipts = evidence.publicationObservationReceipts();
        var missing = evidence.publicationObservationMissingReasons();
        var byComponent = new LinkedHashMap<String, DeploymentAgentHealthEvidence.StatusReceipt>();
        for (var receipt : receipts)
            require(byComponent.putIfAbsent(receipt.component(), receipt) == null, "Duplicate observation receipt");
        var componentEvents = new ArrayList<Long>();
        for (var part : proposal.graph().components()) {
            var candidate = candidates.get(part.componentId());
            var commit = proposal.units().stream().filter(unit -> unit.unit().equals(part.unit())).findFirst().orElseThrow();
            var row = frame("COMPONENT", invocation).put("beginEvent", begin)
                .put("beginSha256", DeploymentAgentInputs.hash(headerText)).put("component", part.componentId())
                .put("unit", part.unit().id()).put("expectedOwnerSha256", part.unit().ownershipManifestSha256())
                .put("candidate", candidate.candidateId()).put("releaseSha256", commit.release().sha256())
                .put("layoutSha256", part.unit().layout().digest());
            var snapshots = row.putArray("originalSnapshotEvents");
            for (var event : prefix)
                if (event.action().equals(part.componentId()) && Set.of("SNAPSHOT_EXECUTING", "SNAPSHOT_RESULT").contains(event.type()))
                    snapshots.addObject().put("id", event.id()).put("type", event.type())
                        .put("detailSha256", DeploymentAgentInputs.hash(event.detail()));
            var receipt = byComponent.get(part.componentId());
            if (receipt == null) {
                row.put("missingReason", missing.getOrDefault(part.componentId(), "NOT_ATTEMPTED_AFTER_STOP"));
                row.putNull("receipt");
            } else {
                row.put("missingReason", "NONE");
                var value = row.putObject("receipt").put("requestEvent", receipt.requestEvent())
                    .put("resultEvent", receipt.resultEvent()).put("exactActionSha256", receipt.exactActionSha256())
                    .put("requestSha256", receipt.requestSha256()).put("reviewSha256", receipt.reviewSha256())
                    .put("evidenceSha256", receipt.evidenceSha256()).put("resultSha256", receipt.resultSha256())
                    .put("statusSha256", statusSha256(receipt.status()));
                status(value.putObject("status"), receipt.status());
            }
            journal.event(task, COMPONENT, invocation, encodeFrame(row));
            componentEvents.add(journal.events(task).getLast().id());
        }
        boolean complete = byComponent.size() == proposal.graph().components().size() && queried.outcome() == TaskResult.SUCCEEDED;
        var ended = frame("END", invocation).put("beginEvent", begin).put("beginSha256", DeploymentAgentInputs.hash(headerText))
            .put("observationComplete", complete).put("outcome", "UNKNOWN").put("authority", "NONE");
        var ids = ended.putArray("componentEvents");
        componentEvents.forEach(ids::add);
        journal.event(task, END, invocation, encodeFrame(ended));
        var recorded = readLatest(journal.events(task), task.id());
        require(recorded.invocation().toString().equals(invocation), "Observation invocation changed");
        return unknown(recorded.observationComplete());
    }

    public static OperationResult<Void> unknown(boolean complete) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            complete ? "DEPLOYMENT_AGENT_PUBLICATION_OBSERVED_UNKNOWN" : "DEPLOYMENT_AGENT_PUBLICATION_OBSERVATION_PARTIAL",
            "Original publication was observed without settling its result", Map.of(), Optional.empty())));
    }

    private static ObjectNode frame(String kind, String invocation) {
        return object().put("formatVersion", 1).put("kind", kind).put("invocation", invocation);
    }

    /** Uses the existing event character limit as well as a strict UTF-8 bound. */
    public static String encodeFrame(ObjectNode frame) throws SQLException {
        String value = encode(frame, 16384);
        require(value.length() <= 4096, "Observation frame exceeds original event limit");
        validateFrame(value);
        return value;
    }

    public static ObjectNode validateFrame(String value) throws SQLException {
        var node = parse(value, 16384);
        require(value.length() <= 4096 && node.path("formatVersion").intValue() == 1, "Unsupported observation frame");
        uuid(node, "invocation");
        switch (string(node, "kind")) {
            case "BEGIN" -> {
                fields(node, Set.of("formatVersion", "kind", "invocation", "task", "execution", "server", "application",
                    "reviewRevision", "templateSha256", "proposalSha256", "prefixEndEvent", "prefixSha256", "components"));
                uuid(node, "task"); uuid(node, "execution"); positive(node, "prefixEndEvent");
                identifier(string(node, "server")); identifier(string(node, "application"));
                for (String key : List.of("reviewRevision", "templateSha256", "proposalSha256", "prefixSha256")) digest(node, key);
                var components = array(node, "components", 16);
                require(!components.isEmpty(), "Missing observation components");
                var seen = new HashSet<String>();
                for (var component : components) require(component.isTextual() && seen.add(identifier(component.textValue())), "Duplicate observation component");
            }
            case "COMPONENT" -> {
                fields(node, Set.of("formatVersion", "kind", "invocation", "beginEvent", "beginSha256", "component", "unit",
                    "expectedOwnerSha256", "candidate", "releaseSha256", "layoutSha256", "originalSnapshotEvents", "missingReason", "receipt"));
                positive(node, "beginEvent"); digest(node, "beginSha256");
                for (String key : List.of("component", "unit", "candidate")) identifier(string(node, key));
                for (String key : List.of("expectedOwnerSha256", "releaseSha256", "layoutSha256")) digest(node, key);
                var snapshots = array(node, "originalSnapshotEvents", 32);
                var seen = new HashSet<Long>();
                for (var event : snapshots) {
                    fields(event, Set.of("id", "type", "detailSha256"));
                    require(seen.add(positive(event, "id")) && Set.of("SNAPSHOT_EXECUTING", "SNAPSHOT_RESULT").contains(string(event, "type")), "Invalid original snapshot reference");
                    digest(event, "detailSha256");
                }
                String reason = string(node, "missingReason");
                require(REASONS.contains(reason) && reason.equals("NONE") != node.path("receipt").isNull(), "Missing observation reason or receipt");
                if (reason.equals("NONE")) validateReceipt(node.path("receipt"));
            }
            case "END" -> {
                fields(node, Set.of("formatVersion", "kind", "invocation", "beginEvent", "beginSha256", "observationComplete", "outcome", "authority", "componentEvents"));
                positive(node, "beginEvent"); digest(node, "beginSha256");
                require(node.path("observationComplete").isBoolean() && string(node, "outcome").equals("UNKNOWN")
                    && string(node, "authority").equals("NONE"), "Observation is not business completion");
                var ids = array(node, "componentEvents", 16);
                require(!ids.isEmpty(), "Missing observation frames");
                long previous = 0;
                for (var id : ids) {
                    require(id.isIntegralNumber() && id.canConvertToLong() && id.longValue() > previous, "Unordered observation frames");
                    previous = id.longValue();
                }
            }
            default -> throw new SQLException("Unknown observation frame kind");
        }
        return node;
    }

    public static Recorded readLatest(List<DeploymentAgentActionLog.Event> events, UUID task) throws Exception {
        var begins = events.stream().filter(event -> event.type().equals(BEGIN)).toList();
        require(!begins.isEmpty(), "Original observation is absent");
        var begin = begins.getLast();
        var header = validateFrame(begin.detail());
        require(string(header, "kind").equals("BEGIN") && uuid(header, "task").equals(task)
            && begin.action().equals(string(header, "invocation")), "Foreign observation identity");
        var prefix = events.stream().filter(event -> event.id() < begin.id()).toList();
        require(!prefix.isEmpty() && prefix.getLast().id() == positive(header, "prefixEndEvent")
            && prefixSha256(prefix).equals(digest(header, "prefixSha256")), "Original observation prefix changed");
        require(begins.stream().filter(event -> event.action().equals(begin.action())).count() == 1, "Replayed observation invocation");
        var frames = events.stream().filter(event -> event.id() > begin.id() && event.action().equals(begin.action())
            && Set.of(COMPONENT, END).contains(event.type())).toList();
        require(events.stream().filter(event -> event.id() > begin.id() && Set.of(COMPONENT, END).contains(event.type()))
            .allMatch(event -> event.action().equals(begin.action())), "Interleaved observation invocation");
        int size = header.path("components").size();
        require(frames.size() == size + 1 && frames.getLast().type().equals(END), "Latest observation is incomplete");
        var ids = new ArrayList<Long>();
        var rows = new ArrayList<String>();
        var requestIds = new HashSet<Long>();
        var resultIds = new HashSet<Long>();
        int observed = 0;
        for (int index = 0; index < size; index++) {
            var event = frames.get(index);
            var row = validateFrame(event.detail());
            require(event.type().equals(COMPONENT) && string(row, "kind").equals("COMPONENT")
                && string(row, "component").equals(header.path("components").get(index).textValue()), "Duplicate or unordered component frame");
            bound(row, header, begin);
            var selected = prefix.stream().filter(old -> old.type().equals("CANDIDATE")
                && old.action().equals(stringUnchecked(row, "component"))).toList();
            require(!selected.isEmpty() && selected.getLast().detail().equals(string(row, "candidate")), "Original candidate changed");
            var originalSnapshots = prefix.stream().filter(old -> old.action().equals(stringUnchecked(row, "component"))
                && Set.of("SNAPSHOT_EXECUTING", "SNAPSHOT_RESULT").contains(old.type())).toList();
            require(originalSnapshots.size() == row.path("originalSnapshotEvents").size(), "Original snapshot membership changed");
            require(originalSnapshots.stream().filter(old -> old.type().equals("SNAPSHOT_EXECUTING"))
                .allMatch(old -> old.detail().equals(stringUnchecked(row, "expectedOwnerSha256"))), "Original snapshot owner changed");
            var publications = prefix.stream().filter(old -> old.type().equals("PUBLISH_EXECUTING")
                && old.action().equals(stringUnchecked(row, "component"))).toList();
            require(publications.isEmpty() || publications.getLast().detail().equals(string(row, "releaseSha256")), "Original attempted release changed");
            for (var snapshot : row.path("originalSnapshotEvents")) {
                var matched = prefix.stream().filter(old -> old.id() == snapshot.path("id").longValue()).toList();
                require(matched.size() == 1 && matched.getFirst().type().equals(snapshot.path("type").textValue())
                    && matched.getFirst().action().equals(string(row, "component"))
                    && DeploymentAgentInputs.hash(matched.getFirst().detail()).equals(snapshot.path("detailSha256").textValue()), "Original snapshot event changed");
            }
            if (!row.path("receipt").isNull()) {
                var receipt = row.path("receipt");
                var requests = events.stream().filter(e -> e.id() == receipt.path("requestEvent").longValue()).toList();
                var results = events.stream().filter(e -> e.id() == receipt.path("resultEvent").longValue()).toList();
                require(requestIds.add(positive(receipt, "requestEvent")) && resultIds.add(positive(receipt, "resultEvent")), "Replayed component receipt");
                String requestDetail = "exactAction=" + digest(receipt, "exactActionSha256") + ";evidence=" + digest(receipt, "evidenceSha256")
                    + ";requestSha256=" + digest(receipt, "requestSha256");
                require(requests.size() == 1 && results.size() == 1 && requests.getFirst().id() > begin.id()
                    && requests.getFirst().id() < results.getFirst().id() && results.getFirst().id() < event.id()
                    && requests.getFirst().type().equals("HEALTH_STATUS_QUERY_REQUEST")
                    && requests.getFirst().action().equals(digest(receipt, "reviewSha256")) && requests.getFirst().detail().equals(requestDetail)
                    && results.getFirst().type().equals("HEALTH_STATUS_QUERY_RESULT")
                    && results.getFirst().action().equals(digest(receipt, "exactActionSha256"))
                    && results.getFirst().detail().equals("resultSha256=" + digest(receipt, "resultSha256")
                        + ";review=" + digest(receipt, "reviewSha256") + ";evidence=" + digest(receipt, "evidenceSha256")
                        + ";statusSha256=" + digest(receipt, "statusSha256")), "Foreign original request/result receipt");
                String approvalDetail = "exactAction=" + digest(receipt, "exactActionSha256")
                    + ";evidence=" + digest(receipt, "evidenceSha256") + ";reasonSha256=";
                require(events.stream().filter(e -> e.id() > requests.getFirst().id() && e.id() < results.getFirst().id()
                    && e.type().equals("HEALTH_STATUS_QUERY_APPROVED") && e.action().equals(stringUnchecked(receipt, "reviewSha256"))
                    && e.detail().startsWith(approvalDetail) && e.detail().substring(approvalDetail.length()).matches("[0-9a-f]{64}")).count() == 1,
                    "Original independent approval receipt is absent or ambiguous");
                observed++;
            }
            ids.add(event.id()); rows.add(event.detail());
        }
        var endEvent = frames.getLast();
        var end = validateFrame(endEvent.detail());
        bound(end, header, begin);
        require(string(end, "kind").equals("END") && end.path("componentEvents").size() == ids.size(), "Invalid observation end");
        for (int i = 0; i < ids.size(); i++) require(end.path("componentEvents").get(i).longValue() == ids.get(i), "Observation end membership changed");
        boolean complete = end.path("observationComplete").booleanValue();
        require(!complete || observed == size, "Incomplete receipts cannot be complete observation");
        return new Recorded(task, uuid(header, "invocation"), complete, observed, rows, endEvent.id());
    }

    private static void bound(JsonNode row, JsonNode header, DeploymentAgentActionLog.Event begin) throws SQLException {
        require(uuid(row, "invocation").equals(uuid(header, "invocation")) && positive(row, "beginEvent") == begin.id()
            && digest(row, "beginSha256").equals(DeploymentAgentInputs.hash(begin.detail())), "Cross-invocation observation frame");
    }

    public static String prefixSha256(List<DeploymentAgentActionLog.Event> events) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        long previous = 0;
        for (var event : events) {
            require(event.id() > previous, "Original prefix events are unordered"); previous = event.id();
            var row = object().put("formatVersion", 1).put("id", event.id()).put("type", event.type())
                .put("action", event.action()).put("detail", event.detail());
            hash.update(encode(row, 32768).getBytes(StandardCharsets.UTF_8)); hash.update((byte) '\n');
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void status(ObjectNode node, gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentOriginalPublishStatus status) {
        node.put("current", status.current().orElse("none")).put("currentKind", status.currentKind()).put("currentMode", status.currentMode())
            .put("snapshot", status.snapshot()).put("previousDigest", status.previousDigest().orElse("none"))
            .put("previousKind", status.previousKind()).put("previousRunning", status.previousRunning().map(Object::toString).orElse("none"))
            .put("maintenance", status.maintenance()).put("candidate", status.candidate()).put("buildActive", status.buildActive())
            .put("unitPresent", status.unitPresent()).put("containerPresent", status.containerPresent().map(Object::toString).orElse("none"))
            .put("retainedCount", status.retainedCount());
    }

    private static void validateReceipt(JsonNode node) throws SQLException {
        fields(node, Set.of("requestEvent", "resultEvent", "exactActionSha256", "requestSha256", "reviewSha256", "evidenceSha256", "resultSha256", "statusSha256", "status"));
        positive(node, "requestEvent"); positive(node, "resultEvent");
        for (String key : List.of("exactActionSha256", "requestSha256", "reviewSha256", "evidenceSha256", "resultSha256", "statusSha256")) digest(node, key);
        var status = node.path("status");
        fields(status, Set.of("current", "currentKind", "currentMode", "snapshot", "previousDigest", "previousKind", "previousRunning",
            "maintenance", "candidate", "buildActive", "unitPresent", "containerPresent", "retainedCount"));
        for (String key : List.of("snapshot", "maintenance", "candidate", "buildActive", "unitPresent"))
            require(status.path(key).isBoolean(), "Observation status boolean required");
        for (String key : List.of("previousRunning", "containerPresent"))
            require(Set.of("none", "true", "false").contains(string(status, key)), "Observation status optional boolean required");
        require(status.path("retainedCount").isInt(), "Observation retained count required");
        try {
            var decoded = new gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentOriginalPublishStatus(optional(string(status, "current")),
                string(status, "currentKind"), string(status, "currentMode"), status.path("snapshot").booleanValue(),
                optional(string(status, "previousDigest")), string(status, "previousKind"), optionalBoolean(string(status, "previousRunning")),
                status.path("maintenance").booleanValue(), status.path("candidate").booleanValue(), status.path("buildActive").booleanValue(),
                status.path("unitPresent").booleanValue(), optionalBoolean(string(status, "containerPresent")), status.path("retainedCount").intValue());
            require(statusSha256(decoded).equals(digest(node, "statusSha256")), "Observation typed status digest changed");
        } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid original status snapshot", invalid); }
    }

    public static String statusSha256(gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentOriginalPublishStatus value) throws SQLException {
        var node = object().put("formatVersion", 1);
        status(node, value);
        return DeploymentAgentInputs.hash(encode(node, 4096));
    }

    private static String stringUnchecked(JsonNode node, String key) { return node.path(key).textValue(); }

    private static Optional<String> optional(String value) { return value.equals("none") ? Optional.empty() : Optional.of(value); }
    private static Optional<Boolean> optionalBoolean(String value) { return value.equals("none") ? Optional.empty() : Optional.of(value.equals("true")); }
    private static String identifier(String value) throws SQLException {
        require(value.matches("[a-z0-9][a-z0-9-]{0,127}"), "Invalid observation identifier"); return value;
    }
    private static String digest(JsonNode node, String key) throws SQLException {
        String value = string(node, key); require(value.matches("[0-9a-f]{64}"), "Observation SHA required"); return value;
    }
    private static long positive(JsonNode node, String key) throws SQLException {
        var value = node.path(key); require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0,
            "Observation positive integer required"); return value.longValue();
    }
    private static void require(boolean value, String message) throws SQLException { if (!value) throw new SQLException(message); }
}
