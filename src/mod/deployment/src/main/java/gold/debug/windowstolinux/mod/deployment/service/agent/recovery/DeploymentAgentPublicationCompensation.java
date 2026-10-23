package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.*;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision;
import gold.debug.windowstolinux.mod.deployment.db.execution.*;
import gold.debug.windowstolinux.mod.deployment.db.publish.*;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseCodec;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseStore;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentOriginalPublishStatus;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentWorkspaceBinding;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog.Event;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog.Snapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** Agent-only local evidence. These methods neither approve nor execute a remote action. */
public final class DeploymentAgentPublicationCompensation {
    public static final String PLAN = "PUBLICATION_COMPENSATION_PLAN";
    public static final String COMPONENT = "PUBLICATION_COMPENSATION_COMPONENT";
    public static final String READY = "PUBLICATION_COMPENSATION_READY";
    public static final String INTENT = "PUBLICATION_COMPENSATION_ACTION_INTENT";
    public static final String RESULT = "PUBLICATION_COMPENSATION_ACTION_RESULT";
    public static final String VERIFIED = "PUBLICATION_COMPENSATION_VERIFIED";
    public static final String DELIVERY_SETTLED = "DELIVERY_COMPENSATION_SETTLED";
    public static final String ROLLBACK_OPERATION = "deployment.agent.publication-recovery.guarded-first-rollback.v1";
    public static final String CLEANUP_OPERATION = "deployment.publication.candidate-cleanup.v1";
    public static final String FAILURE = "DEPLOYMENT_AGENT_FIRST_PUBLICATION_COMPENSATED";
    private static final Set<String> TYPES = Set.of(PLAN, COMPONENT, READY, INTENT, RESULT, VERIFIED);
    private static final Set<String> HEADER_FIELDS = Set.of("formatVersion", "kind", "operation", "task", "execution", "server",
        "application", "target", "configuration", "policy", "reviewRevision", "layout", "request", "recovery", "template", "proposal",
        "graph", "prefixEnd", "prefix", "observationEnd", "observation", "delivery", "action", "revision", "source", "archive", "plan", "components");
    private static final Set<String> COMPONENT_FIELDS = Set.of("formatVersion", "kind", "operation", "component", "unit", "owner",
        "candidate", "releaseRoot", "candidateRoot", "source", "artifact", "tools", "configuration", "runtime", "resources", "release",
        "receipts", "snapshotStart", "snapshotEnd", "snapshot", "observation", "status");

    private DeploymentAgentPublicationCompensation() { }

    public enum Verb { GUARDED_ROLLBACK, CLEANUP_CANDIDATE }
    public record Component(String id, String unit, String owner, String candidate, String release, String releaseRoot,
                            String candidateRoot, DeploymentOriginalPublishStatus initial, String frame) { }
    /** Canonical immutable frames retain every bound field without exposing mutable JSON. */
    public record Plan(UUID operation, UUID task, UUID execution, String server, String application, String reviewRevision,
                       String sha256, long beginEvent, long readyEvent, String header, List<Component> components) {
        public Plan { components = List.copyOf(components); }
    }
    public record Intent(UUID operation, UUID actionId, int sequence, String component, Verb verb, UUID grant, long event, String frame) { }
    public record ActionStep(int sequence, Component component, Verb verb) { }
    public record Verified(Plan plan, long event, long observationEnd, String frame) { }
    public record State(Plan plan, int completed, Optional<Intent> pending, long lastActionEvent, Optional<Verified> verified) { }
    /** A later complete original observation still disproves this failed rollback's goal; it grants no authority. */
    public record DiagnosticEligibility(Plan plan, Intent pending, Component component, long observationEnd, String observationSha256,
                                        DeploymentOriginalPublishStatus status) { }
    public static final class FailureDeliveryProof {
        private final UUID task;
        private final String action;
        private final String detail;
        private FailureDeliveryProof(UUID task, String action, String detail) { this.task = task; this.action = action; this.detail = detail; }
        public UUID task() { return task; }
        public String action() { return action; }
        public String detail() { return detail; }
    }

    /** Called by the real original TaskWork only, after any separately tracked sidecar installation has settled. */
    public static Plan begin(ModContext context, TaskContext task, DeploymentAgentActionLog journal) throws Exception {
        var saved = journal.find(task).orElseThrow();
        var formal = new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow();
        require(formal.phase().equals("EXECUTING") && formal.outcome().filter(v -> v != TaskResult.UNKNOWN).isEmpty(), "Original execution is not pending");
        var template = new DeploymentExecutionStore(context.db()).publicationTemplate(formal).orElseThrow();
        var prefix = List.copyOf(journal.events(task));
        DeploymentAgentPublicationRecoveryInstallation.requireNoPending(prefix);
        require(prefix.stream().noneMatch(e -> TYPES.contains(e.type()) || e.type().equals(DELIVERY_SETTLED)), "A compensation plan already exists");
        UUID operation = UUID.randomUUID();
        var frames = originalBinding(task.id(), saved, formal, template, prefix, operation);
        require(prefix.equals(journal.events(task)) && saved.equals(journal.find(task).orElseThrow()), "Original plan changed before persistence");
        journal.event(task, PLAN, operation.toString(), frames.getFirst());
        for (int i = 1; i < frames.size(); i++) journal.event(task, COMPONENT, operation.toString(), frames.get(i));
        var ready = frame("READY", operation).put("plan", framesDigest(frames));
        var ids = ready.putArray("componentEvents");
        journal.events(task).stream().filter(e -> e.type().equals(COMPONENT) && e.action().equals(operation.toString())).forEach(e -> ids.add(e.id()));
        journal.event(task, READY, operation.toString(), encoded(ready));
        return read(journal.events(task), task.id()).plan();
    }

    /** Persists an exact independently approved, newly granted intent before transport. No authority is created here. */
    public static Intent intend(ModContext context, TaskContext task, DeploymentAgentActionLog journal, Plan plan, UUID actionId, ActionIntent action,
                                CommandRequest request, DeploymentReviewDecision decision, String evidenceSha256,
                                ApprovalGrant grant) throws Exception {
        var state = requireCurrent(context, task, journal, plan);
        require(state.pending().isEmpty() && state.verified().isEmpty(), "An original action remains unresolved");
        require(state.completed() < steps(plan).size(), "All compensation actions are already complete");
        var step = steps(plan).get(state.completed());
        require(decision.decision().equals("APPROVE") && action.serverIds().equals(List.of(plan.server()))
            && action.revision().equals(plan.sha256()) && action.semantics().isPresent()
            && action.semantics().orElseThrow().operation().equals(semanticOperation(step.verb()))
            && action.exactAction().equals(RemoteActions.command(new ServerId(plan.server()), plan.sha256(), request).exactAction()), "Action is not the independently reviewed exact request");
        var targets = action.semantics().orElseThrow().targets();
        require(targets.size() <= 16 && targets.contains(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(plan.server(), "deployment-release", step.component().releaseRoot()))
            && targets.stream().allMatch(t -> t.serverId().equals(plan.server())), "Compensation targets changed");
        Objects.requireNonNull(actionId);
        for (var event : journal.events(task)) if (event.type().equals(INTENT)) {
            var previous = parsed(event.detail());
            require(!uuid(previous, "grant").equals(grant.id()) && !uuid(previous, "actionId").equals(actionId), "A compensation grant or action ID was reused");
        }
        if (step.verb() == Verb.CLEANUP_CANDIDATE) {
            var store = new DeploymentExecutionStore(context.db());
            var unit = store.publicationTemplate(store.findForTask(task).orElseThrow()).orElseThrow().graph().components().stream()
                .filter(p -> p.componentId().equals(step.component().id())).findFirst().orElseThrow().unit();
            String command = java.util.stream.Stream.of(unit.layout().helperPath(), "candidate-cleanup-task", unit.id(), step.component().candidate(), task.id().toString())
                .map(gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel::shellQuote).collect(java.util.stream.Collectors.joining(" "));
            require(request.script().equals(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.verifiedCommand(command, unit.layout()))
                && request.stdin().length == 0 && request.timeout().equals(java.time.Duration.ofSeconds(90)) && request.outputLimitBytes() == 4096,
                "Candidate cleanup differs from its original fixed three-argument helper request");
        }
        digestValue(evidenceSha256);
        var node = frame("INTENT", plan.operation()).put("plan", plan.sha256()).put("sequence", state.completed() + 1)
            .put("component", step.component().id()).put("verb", step.verb().name()).put("previous", prefix(journal.events(task)))
            .put("actionId", actionId.toString())
            .put("exactAction", hash(action.exactAction())).put("request", requestDigest(request)).put("evidence", evidenceSha256)
            .put("reviewRevision", plan.reviewRevision()).put("decision", decision.decision()).put("reason", hash(decision.reason()))
            .put("requiresUserConfirmation", decision.requiresUserConfirmation()).put("rememberAuthorization", false).put("grant", grant.id().toString());
        node.put("semanticOperation", semanticOperation(step.verb()));
        var targetRows = node.putArray("targets");
        targets.stream().sorted(Comparator.comparing(gold.debug.windowstolinux.appapi.task.action.ActionTarget::kind)
            .thenComparing(gold.debug.windowstolinux.appapi.task.action.ActionTarget::id)).forEach(t ->
                targetRows.addObject().put("server", t.serverId()).put("kind", t.kind()).put("id", t.id()));
        journal.event(task, INTENT, plan.operation().toString(), encoded(node));
        return read(journal.events(task), task.id()).pending().orElseThrow();
    }

    /** The producer must first validate its fixed helper/sidecar response. This records transport evidence, never substitutes for that parser or a post-state query. */
    public static void acknowledge(TaskContext task, DeploymentAgentActionLog journal, Intent intent,
                                   OperationResult<CommandResult> actual) throws Exception {
        acknowledge(task, journal, intent, actual, true);
    }

    /** A rejected fixed parser forces UNKNOWN while retaining the actual transport/result digest and classification. */
    public static void acknowledge(TaskContext task, DeploymentAgentActionLog journal, Intent intent,
                                   OperationResult<CommandResult> actual, boolean fixedReceiptVerified) throws Exception {
        var state = read(journal.events(task), task.id());
        require(state.pending().equals(Optional.of(intent)) && state.verified().isEmpty(), "Original pending intent changed");
        for (var event : journal.events(task)) if (event.type().equals(RESULT))
            require(positive(parsed(event.detail()), "intent") != intent.event(), "An old UNKNOWN or known ACK cannot be rewritten");
        var receipt = actual.value();
        boolean acknowledged = fixedReceiptVerified && actual.outcome() == TaskResult.SUCCEEDED && receipt.isPresent()
            && !receipt.orElseThrow().truncated() && receipt.orElseThrow().exitCode().equals(Optional.of(0));
        var node = result(state.plan(), intent, acknowledged ? "ACKNOWLEDGED" : "UNKNOWN", journal.events(task))
            .put("receipt", resultDigest(actual)).put("observedOutcome", actual.outcome().name()).put("fixedReceiptVerified", fixedReceiptVerified)
            .put("observationEnd", 0).put("observation", "none");
        journal.event(task, RESULT, intent.operation().toString(), encoded(node));
    }

    /** A later real independent status query can prove a goal, never the old command's missing ACK. */
    public static void observedOnly(TaskContext task, DeploymentAgentActionLog journal, Intent intent) throws Exception {
        var events = journal.events(task);
        var state = read(events, task.id());
        require(state.pending().equals(Optional.of(intent)) && state.verified().isEmpty(), "Original pending intent changed");
        var observed = observation(events, state.plan(), events.getLast().id());
        require(observed.end() > state.lastActionEvent(), "No fresh original observation");
        require(goal(observed.statuses().get(intent.component()), intent.verb() == Verb.CLEANUP_CANDIDATE), "Unknown action goal remains unconfirmed");
        var node = result(state.plan(), intent, "OBSERVED_ONLY", events).put("receipt", "none")
            .put("observationEnd", observed.end()).put("observation", observed.sha256());
        journal.event(task, RESULT, intent.operation().toString(), encoded(node));
    }

    /** Creates a durable local re-entry credential only after all action goals and a fresh complete final observation. */
    public static Verified verify(ModContext context, TaskContext task, DeploymentAgentActionLog journal) throws Exception {
        var events = journal.events(task);
        var state = read(events, task.id());
        require(state.verified().isEmpty() && state.pending().isEmpty() && state.completed() == steps(state.plan()).size(), "Compensation is incomplete");
        var store = new DeploymentExecutionStore(context.db());
        var formal = store.findForTask(task).orElseThrow();
        require(formal.phase().equals("EXECUTING") && formal.outcome().filter(v -> v != TaskResult.UNKNOWN).isEmpty(), "A known formal outcome cannot acquire compensation verification");
        requireBound(task.id(), journal.find(task).orElseThrow(), formal, store.publicationTemplate(formal).orElseThrow(), events, state.plan());
        var observed = observation(events, state.plan(), events.getLast().id());
        require(observed.end() > state.lastActionEvent() && observed.statuses().values().stream().allMatch(s -> goal(s, true)), "Final graph is not verified empty");
        require(otherEffectsSettled(events), "Another original effect remains unresolved");
        var layout = store.publicationTemplate(formal).orElseThrow().graph().components().getFirst().unit().layout();
        require(!gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainProgramState.hasUnconfirmed(
            context.tasks(), task, formal.identity().server().id(), layout, events), "An original controller action remains unconfirmed");
        var command = context.tasks().findCheckpoint(task, gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.KEY);
        require(command.isEmpty() || command.orElseThrow().snapshot().released(), "Original model command is not settled");
        for (var event : events) if (Set.of("AGENT_DATABASE_INDEX", "AGENT_SECRET_INDEX").contains(event.type())) {
            var child = context.tasks().findCheckpoint(task, event.action());
            require(child.isEmpty() || child.orElseThrow().snapshot().released(), "Original indexed resource remains retained");
        }
        var node = frame("VERIFIED", state.plan().operation()).put("plan", state.plan().sha256()).put("actions", state.completed())
            .put("history", prefix(events)).put("observationEnd", observed.end()).put("observation", observed.sha256())
            .put("outcome", "FAILED").put("problemCode", ProblemCode.IO_FAILURE.name()).put("eventCode", FAILURE);
        journal.event(task, VERIFIED, state.plan().operation().toString(), encoded(node));
        return read(journal.events(task), task.id()).verified().orElseThrow();
    }

    public static Problem failure() {
        return new Problem(ProblemCode.IO_FAILURE, FAILURE, "Original first publication was compensated and verified", Map.of(), Optional.empty());
    }

    /** Safe for the subsequent Recovery writer: validates immutable original identities as well as the complete local tail. */
    public static Verified verified(UUID task, Snapshot saved, DeploymentExecutionRecord formal, DeploymentPublishProposal template,
                                    List<Event> events) throws Exception {
        var state = read(events, task);
        requireBound(task, saved, formal, template, events, state.plan());
        return state.verified().orElseThrow(() -> new SQLException("Compensation has no durable verification"));
    }

    /** Only the archive's original row locks and resource gate may mint the failed DELIVERY settlement. */
    public static FailureDeliveryProof deliveryProof(Connection connection, UUID task, Snapshot saved, DeploymentExecutionRecord formal,
                                                     DeploymentPublishProposal template, List<Event> events) throws Exception {
        require(!connection.getAutoCommit(), "Compensation proof requires the original archive transaction");
        require(DeploymentAgentActionLog.find(connection, task).equals(Optional.of(saved))
            && DeploymentAgentActionLog.events(connection, task).equals(events)
            && DeploymentExecutionStore.read(connection, formal.identity().executionId(), true).equals(Optional.of(formal)), "Actual original archive rows changed");
        var verified = verified(task, saved, formal, template, events);
        require(saved.state().equals("FAILED") && formal.phase().equals("FINISHED") && formal.cleanupComplete()
            && !formal.businessCommitted() && formal.outcome().equals(Optional.of(TaskResult.FAILED)), "Original compensated failure is not finished");
        var formalResult = parse(formal.resultJson().orElseThrow());
        require(text(formalResult, "problemCode").equals(ProblemCode.IO_FAILURE.name()) && text(formalResult, "eventCode").equals(FAILURE), "Original compensation result changed");
        require(otherEffectsSettled(events), "Another original effect remains unresolved");
        var tail = events.stream().filter(e -> e.id() > verified.event() && !e.type().equals(DELIVERY_SETTLED)).toList();
        require(tail.size() == 3 && tail.get(0).type().equals("AI_PUBLICATION_CLEANED") && tail.get(1).type().equals("AI_RECOVERY_SETTLED")
            && tail.get(2).type().equals("STATE"), "Original compensation settlement tail is incomplete");
        requireNoBusiness(connection, verified.plan());
        var beforeProof = events.stream().filter(e -> !e.type().equals(DELIVERY_SETTLED)).toList();
        var header = parsed(verified.plan().header());
        var node = frame("DELIVERY_SETTLED", verified.plan().operation()).put("task", task.toString())
            .put("execution", formal.identity().executionId().toString()).put("plan", verified.plan().sha256())
            .put("delivery", positive(header, "delivery")).put("action", text(header, "action")).put("revision", positive(header, "revision"))
            .put("ordinal", 1)
            .put("verified", verified.event()).put("verification", hash(verified.frame())).put("history", prefix(beforeProof))
            .put("request", hash(formal.requestJson())).put("recovery", hash(formal.recoveryJson())).put("result", hash(formal.resultJson().orElseThrow()))
            .put("outcome", "FAILED").put("problemCode", ProblemCode.IO_FAILURE.name()).put("eventCode", FAILURE);
        var proof = new FailureDeliveryProof(task, text(header, "action"), encoded(node));
        var existing = events.stream().filter(e -> e.type().equals(DELIVERY_SETTLED)).toList();
        require(existing.size() <= 1 && (existing.isEmpty() || existing.getFirst().action().equals(proof.action())
            && existing.getFirst().detail().equals(proof.detail()) && existing.getFirst().id() > beforeProof.getLast().id()), "Conflicting compensation delivery proof");
        return proof;
    }

    public static void appendDeliveryProof(Connection connection, UUID task, FailureDeliveryProof proof) throws SQLException {
        require(!connection.getAutoCommit() && task.equals(proof.task()), "Foreign compensation proof transaction");
        // The caller already holds the same original event/task locks as the successful archive path.
        gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentEvidenceLimit.require(connection, task, false);
        try (var insert = connection.prepareStatement("INSERT INTO deployment_agent_event(task_id,action_id,event_type,event_detail,created_at) VALUES(?,?,?,?,?)")) {
            insert.setString(1, task.toString()); insert.setString(2, proof.action()); insert.setString(3, DELIVERY_SETTLED);
            insert.setString(4, proof.detail()); insert.setLong(5, Instant.now().toEpochMilli());
            require(insert.executeUpdate() == 1, "Compensation proof insertion was unconfirmed");
        }
    }

    /** Authenticated original platform-reference resolution; the archive checks physical release before opening this connection. */
    public static void requireReleasedReferences(ModContext context, TaskContext task, DeploymentExecutionRecord formal, Connection connection) throws Exception {
        require(task.id().equals(formal.identity().taskId()) && context.tasks().inspect(task).id().equals(formal.identity().taskId()), "Original task identity changed");
        var references = new DeploymentExecutionStore(context.db()).references(formal);
        for (var entry : references.entrySet()) {
            var intent = entry.getKey();
            gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefKey.requireOriginal(intent, formal.identity().applicationId(), formal.identity().server());
            require(entry.getValue().isPresent(), "Original reference reservation was not recorded");
            if (intent.kind() == gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefIntent.DeploymentRefKind.SERVER) {
                require(context.servers().findReference(new ServerId(intent.resourceId()), intent.businessKey(), task).isEmpty(), "Original application or unit reference is retained");
            } else {
                var revision = gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefKey.revision(intent);
                var ref = context.secrets().findReference(new gold.debug.windowstolinux.appapi.secret.model.SecretId(intent.resourceId(), intent.purpose().orElseThrow()), intent.businessKey(), task);
                try (var query = connection.prepareStatement("SELECT credential_id FROM secret_revision WHERE secret_id=? AND secret_revision=?")) {
                    query.setString(1, revision.identifier()); query.setLong(2, revision.revision());
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) require(rows.getString(1).equals(intent.resourceId()) && ref.map(r -> r.id()).equals(entry.getValue()), "Original shared secret association changed");
                        else require(ref.isEmpty(), "Original unreferenced secret is retained");
                    }
                }
            }
        }
    }

    /** Structural evaluator used by effectsSettled; formal/result/business checks remain mandatory in the archive builder. */
    public static boolean matchesDeliveryProof(Event proof, List<Event> all) {
        try {
            var node = parsed(proof.detail());
            fields(node, Set.of("formatVersion", "kind", "operation", "task", "execution", "plan", "delivery", "action", "revision", "ordinal",
                "verified", "verification", "history", "request", "recovery", "result", "outcome", "problemCode", "eventCode"));
            require(text(node, "kind").equals("DELIVERY_SETTLED") && text(node, "outcome").equals("FAILED")
                && text(node, "problemCode").equals(ProblemCode.IO_FAILURE.name()) && text(node, "eventCode").equals(FAILURE), "Invalid compensated outcome");
            var before = all.stream().filter(e -> e.id() < proof.id()).toList();
            var state = read(before, uuid(node, "task"));
            var verified = state.verified().orElseThrow();
            var header = parsed(state.plan().header());
            for (String key : List.of("plan", "verification", "history", "request", "recovery", "result", "action")) digest(node, key);
            return proof.action().equals(text(node, "action")) && text(node, "action").equals(text(header, "action"))
                && integer(node, "ordinal") == 1 && text(node, "request").equals(text(header, "request"))
                && positive(node, "delivery") == positive(header, "delivery") && positive(node, "revision") == positive(header, "revision")
                && uuid(node, "operation").equals(state.plan().operation()) && uuid(node, "execution").equals(state.plan().execution())
                && text(node, "plan").equals(state.plan().sha256()) && positive(node, "verified") == verified.event()
                && text(node, "verification").equals(hash(verified.frame())) && text(node, "history").equals(prefix(before))
                && before.stream().noneMatch(e -> e.type().equals(DELIVERY_SETTLED) || e.type().equals(DeploymentAgentActionLog.DELIVERY_RECOVERY_SETTLED) || e.type().equals("DELIVERY_RESULT"));
        } catch (Exception invalid) { return false; }
    }

    /** Never falls back to an older complete plan or verification. */
    public static State read(List<Event> events, UUID task) throws Exception {
        prefix(events);
        DeploymentAgentPublicationContractDiagnosticFollowup.rejectHistoricalReuse(events,0,events.size());
        DeploymentAgentPublicationCorrection.rejectHistoricalReuse(events,0,events.size());
        DeploymentAgentPublicationReconsideration.rejectHistoricalReuse(events);
        var begins = events.stream().filter(e -> e.type().equals(PLAN)).toList();
        require(begins.size() == 1, "Compensation plan is absent or duplicated");
        var begin = begins.getFirst();
        var header = parsed(begin.detail()); fields(header, HEADER_FIELDS);
        require(text(header, "kind").equals("PLAN") && uuid(header, "task").equals(task), "Foreign compensation plan");
        UUID operation = uuid(header, "operation");
        require(begin.action().equals(operation.toString()), "Foreign plan event owner");
        var before = events.stream().filter(e -> e.id() < begin.id()).toList();
        require(!before.isEmpty() && before.getLast().id() == positive(header, "prefixEnd") && prefix(before).equals(digest(header, "prefix")), "Original plan prefix changed");
        var originalDelivery = only(before, "DELIVERY_EXECUTING", null);
        require(originalDelivery.id() == positive(header, "delivery") && originalDelivery.action().equals(digest(header, "action"))
            && originalDelivery.detail().equals(Long.toString(positive(header, "revision")))
            && before.stream().noneMatch(e -> TYPES.contains(e.type()) || e.type().equals("DELIVERY_RESULT") || e.type().equals(DELIVERY_SETTLED)
                || e.type().equals(DeploymentAgentActionLog.DELIVERY_RECOVERY_SETTLED)), "Original delivery is no longer uniquely pending");
        var components = new ArrayList<Component>();
        var componentEvents = new ArrayList<Long>();
        var planFrames = new ArrayList<String>(); planFrames.add(begin.detail());
        var names = array(header, "components", 16);
        require(!names.isEmpty(), "Compensation has no components");
        int cursor = events.indexOf(begin) + 1;
        for (var name : names) {
            require(name.isTextual() && cursor < events.size(), "Compensation plan is incomplete");
            var event = events.get(cursor++); var row = parsed(event.detail()); fields(row, COMPONENT_FIELDS);
            require(event.type().equals(COMPONENT) && event.action().equals(operation.toString()) && uuid(row, "operation").equals(operation)
                && text(row, "kind").equals("COMPONENT") && text(row, "component").equals(name.textValue()), "Plan component order changed");
            var component = component(row, event.detail());
            require(components.stream().noneMatch(c -> c.id().equals(component.id()) || c.unit().equals(component.unit())), "Duplicate plan component");
            components.add(component); componentEvents.add(event.id()); planFrames.add(event.detail());
        }
        require(cursor < events.size(), "Compensation plan is not ready");
        var readyEvent = events.get(cursor++); var ready = parsed(readyEvent.detail());
        fields(ready, Set.of("formatVersion", "kind", "operation", "plan", "componentEvents"));
        String planSha = framesDigest(planFrames);
        require(readyEvent.type().equals(READY) && readyEvent.action().equals(operation.toString()) && text(ready, "kind").equals("READY")
            && uuid(ready, "operation").equals(operation) && digest(ready, "plan").equals(planSha), "Compensation readiness changed");
        var ids = array(ready, "componentEvents", 16);
        require(ids.size() == componentEvents.size(), "Plan component membership changed");
        for (int i = 0; i < ids.size(); i++) require(ids.get(i).isIntegralNumber() && ids.get(i).longValue() == componentEvents.get(i), "Plan event membership changed");
        var plan = new Plan(operation, task, uuid(header, "execution"), text(header, "server"), text(header, "application"), digest(header, "reviewRevision"),
            planSha, begin.id(), readyEvent.id(), begin.detail(), components);
        validateHeader(header);
        long active = components.stream().filter(c -> c.initial().current().isPresent()).count();
        require(active > 0 && active < components.size() && components.stream().allMatch(c -> eligibleInitial(c.initial(), c.release())), "Plan is outside first native partial-publication scope");
        int completed = 0; Intent pending = null; long lastAction = readyEvent.id(); Verified verified = null;
        boolean unknownRecorded = false;
        var grants = new HashSet<UUID>();
        var actionIds = new HashSet<UUID>();
        var diagnosticIntents = new HashSet<Long>();
        var preflightPriors = new HashSet<Long>();
        var continuationIntents = new HashSet<Long>();
        var contractPriors = new HashSet<Long>();
        var followupPriors=new HashSet<Long>();
        var correctionPriors=new HashSet<String>();
        DeploymentAgentPublicationReconsideration.Eligibility reconsiderationProof=null;
        int tailPosition = 0;
        while (cursor < events.size()) {
            var event = events.get(cursor);
            if (verified != null) {
                require(localTail(event, task, plan.application()), "Remote or foreign evidence follows compensation verification");
                var tailOrder = List.of("AI_PUBLICATION_CLEANED", "AI_RECOVERY_SETTLED", "STATE", DELIVERY_SETTLED);
                require(tailPosition < tailOrder.size() && event.type().equals(tailOrder.get(tailPosition++)), "Out-of-order or duplicated compensation settlement tail");
                if (event.type().equals(DELIVERY_SETTLED)) require(cursor == events.size() - 1 && matchesDeliveryProof(event, events), "Invalid failed delivery tail");
                cursor++; continue;
            }
            if (event.type().equals(DeploymentAgentPublicationObservation.BEGIN)) {
                int end = cursor + 1;
                while (end < events.size() && !events.get(end).type().equals(DeploymentAgentPublicationObservation.END)) end++;
                require(end < events.size(), "Latest compensation observation is incomplete");
                observation(events, plan, events.get(end).id());
                DeploymentAgentPublicationContractDiagnostic.rejectHistoricalReuse(events,cursor,end+1);
                cursor = end + 1; continue;
            }
            if (event.type().equals(DeploymentAgentPublicationRecoveryDiagnostic.EVENT)) {
                require(pending != null && unknownRecorded && diagnosticIntents.add(pending.event()), "Diagnostic is outside its unique UNKNOWN pending intent");
                var eligible = diagnosticEligibility(plan, pending, lastAction, events.subList(0, cursor)).orElseThrow(
                    () -> new SQLException("Failed original rollback lacks a later complete unmet-goal observation"));
                int end = DeploymentAgentPublicationRecoveryDiagnostic.validateBlock(events, cursor, eligible);
                DeploymentAgentPublicationRecoveryPreflight.rejectHistoricalReuse(events,cursor,end);
                DeploymentAgentPublicationContinuation.rejectHistoricalReuse(events,cursor,end);
                DeploymentAgentPublicationContractDiagnostic.rejectHistoricalReuse(events,cursor,end);
                cursor = end;
                continue;
            }
            if (event.type().equals(DeploymentAgentPublicationRecoveryPreflight.EVENT)) {
                require(pending != null && unknownRecorded, "Preflight is outside its original UNKNOWN pending intent");
                var state = new State(plan, completed, Optional.of(pending), lastAction, Optional.empty());
                var eligible = DeploymentAgentPublicationRecoveryPreflight.eligibility(state, events.subList(0,cursor)).orElseThrow(
                    () -> new SQLException("Preflight lacks a complete original v1 environment-failure receipt"));
                require(preflightPriors.add(eligible.prior().requestEvent()), "Preflight already attempted for this original diagnostic");
                int end=DeploymentAgentPublicationRecoveryPreflight.validateBlock(events,cursor,eligible);
                DeploymentAgentPublicationContinuation.rejectHistoricalReuse(events,cursor,end);
                DeploymentAgentPublicationContractDiagnostic.rejectHistoricalReuse(events,cursor,end);cursor=end;
                continue;
            }
            if(event.type().equals(DeploymentAgentPublicationContinuation.EVENT)) {
                require(pending!=null&&unknownRecorded&&continuationIntents.add(pending.event()),"Continuation is outside its unique original UNKNOWN pending intent");
                var state=new State(plan,completed,Optional.of(pending),lastAction,Optional.empty());
                var eligible=DeploymentAgentPublicationContinuation.eligibility(state,events.subList(0,cursor)).orElseThrow(
                    ()->new SQLException("Continuation lacks a complete verified current-state preflight"));
                int end=DeploymentAgentPublicationContinuation.validateBlock(events,cursor,eligible);
                DeploymentAgentPublicationContractDiagnostic.rejectHistoricalReuse(events,cursor,end);cursor=end;
                lastAction=events.get(cursor-1).id(); // Even an interrupted attempt requires a newer independently reviewed whole-graph observation.
                continue;
            }
            if(event.type().equals(DeploymentAgentPublicationReconsideration.EVENT)) {
                require(pending!=null&&unknownRecorded,"Reconsideration is outside its original UNKNOWN pending intent");
                var state=new State(plan,completed,Optional.of(pending),lastAction,Optional.empty());
                require(reconsiderationProof!=null,"Reconsideration lacks a validated original undispatched DENY");
                cursor=DeploymentAgentPublicationReconsideration.validateBlock(events,cursor,state,reconsiderationProof);
                lastAction=events.get(cursor-1).id();
                continue;
            }
            if(event.type().equals(DeploymentAgentPublicationCorrection.EVENT)) {
                require(pending!=null&&unknownRecorded,"Correction is outside its original UNKNOWN pending intent");
                var state=new State(plan,completed,Optional.of(pending),lastAction,Optional.empty());
                var eligible=DeploymentAgentPublicationCorrection.eligibility(state,events.subList(0,cursor)).orElseThrow(
                    ()->new SQLException("Correction lacks a complete OBSERVED three-contract followup and newer unmet-goal observation"));
                require(correctionPriors.add(pending.event()+":"+eligible.binding().followup().diagnostic().prior().requestEvent()),"Correction already attempted for this original pending/continuation pair");
                int correctionStart=cursor;
                cursor=DeploymentAgentPublicationCorrection.validateBlock(events,cursor,eligible);
                reconsiderationProof=DeploymentAgentPublicationReconsideration.validatedDenial(state,events,correctionStart,cursor,eligible).orElse(null);
                lastAction=events.get(cursor-1).id();
                continue;
            }
            if(event.type().equals(DeploymentAgentPublicationContractDiagnosticFollowup.EVENT)) {
                require(pending!=null&&unknownRecorded,"Contract followup is outside its original UNKNOWN pending intent");
                var state=new State(plan,completed,Optional.of(pending),lastAction,Optional.empty());
                var eligible=DeploymentAgentPublicationContractDiagnosticFollowup.eligibility(state,events.subList(0,cursor)).orElseThrow(
                    ()->new SQLException("Contract followup lacks a complete old UNKNOWN/CONTROL receipt and newer unmet-goal observation"));
                require(followupPriors.add(eligible.binding().prior().requestEvent()),"Contract followup already attempted for the original diagnostic");
                cursor=DeploymentAgentPublicationContractDiagnosticFollowup.validateBlock(events,cursor,eligible);
                lastAction=events.get(cursor-1).id();
                continue;
            }
            if(event.type().equals(DeploymentAgentPublicationContractDiagnostic.EVENT)) {
                require(pending!=null&&unknownRecorded,"Contract diagnostic is outside its original UNKNOWN pending intent");
                var state=new State(plan,completed,Optional.of(pending),lastAction,Optional.empty());
                var eligible=DeploymentAgentPublicationContractDiagnostic.eligibility(state,events.subList(0,cursor)).orElseThrow(
                    ()->new SQLException("Contract diagnostic lacks a complete unknown continuation and newer unmet-goal observation"));
                require(contractPriors.add(eligible.binding().prior().requestEvent()),"Contract diagnostic already attempted for this pending/continuation pair");
                cursor=DeploymentAgentPublicationContractDiagnostic.validateBlock(events,cursor,eligible);
                lastAction=events.get(cursor-1).id(); // Any attempt requires a later independent whole-graph observation before resolving the old pending action.
                continue;
            }
            require(event.action().equals(operation.toString()), "Unrelated evidence inside compensation actions");
            var node = parsed(event.detail());
            require(uuid(node, "operation").equals(operation) && digest(node, "plan").equals(planSha), "Compensation action plan changed");
            if (event.type().equals(INTENT)) {
                fields(node, Set.of("formatVersion", "kind", "operation", "plan", "sequence", "component", "verb", "previous", "exactAction", "request", "evidence",
                    "reviewRevision", "decision", "reason", "requiresUserConfirmation", "rememberAuthorization", "grant", "semanticOperation", "targets", "actionId"));
                require(pending == null && completed < steps(plan).size() && text(node, "kind").equals("INTENT")
                    && integer(node, "sequence") == completed + 1, "Compensation intent is duplicated or out of order");
                DeploymentAgentPublicationRecoveryPreflight.rejectPriorPreflight(events.subList(0,cursor),uuid(node,"actionId"),uuid(node,"grant"));
                DeploymentAgentPublicationContinuation.rejectPriorContinuation(events.subList(0,cursor),uuid(node,"actionId"),uuid(node,"grant"));
                DeploymentAgentPublicationContractDiagnostic.rejectPriorContractDiagnostic(events.subList(0,cursor),uuid(node,"actionId"),uuid(node,"grant"));
                var step = steps(plan).get(completed);
                require(text(node, "component").equals(step.component().id()) && text(node, "verb").equals(step.verb().name())
                    && digest(node, "previous").equals(prefix(events.subList(0, cursor))) && text(node, "decision").equals("APPROVE")
                    && digest(node, "reviewRevision").equals(plan.reviewRevision()) && node.path("requiresUserConfirmation").isBoolean()
                    && node.path("rememberAuthorization").isBoolean() && !node.path("rememberAuthorization").booleanValue(), "Compensation review binding changed");
                for (String key : List.of("exactAction", "request", "evidence", "reason")) digest(node, key);
                require(text(node, "semanticOperation").equals(semanticOperation(step.verb())), "Compensation operation changed");
                var targetRows = array(node, "targets", 16); var targets = new HashSet<gold.debug.windowstolinux.appapi.task.action.ActionTarget>();
                for (var target : targetRows) {
                    fields(target, Set.of("server", "kind", "id"));
                    require(text(target, "server").equals(plan.server()) && targets.add(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(
                        text(target, "server"), text(target, "kind"), text(target, "id"))), "Foreign or duplicated compensation target");
                }
                require(targets.contains(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(plan.server(), "deployment-release", step.component().releaseRoot())), "Missing original unit target");
                UUID grant = uuid(node, "grant"); require(grants.add(grant), "A compensation grant was reused");
                UUID actionId = uuid(node, "actionId"); require(actionIds.add(actionId), "A compensation action ID was reused");
                pending = new Intent(operation, actionId, completed + 1, step.component().id(), step.verb(), grant, event.id(), event.detail());
                unknownRecorded = false; lastAction = event.id();
            } else if (event.type().equals(RESULT)) {
                fields(node, Set.of("formatVersion", "kind", "operation", "plan", "sequence", "intent", "intentSha256", "resolution", "history", "receipt", "observationEnd", "observation", "observedOutcome", "fixedReceiptVerified"));
                require(pending != null && text(node, "kind").equals("RESULT") && integer(node, "sequence") == pending.sequence()
                    && positive(node, "intent") == pending.event() && digest(node, "intentSha256").equals(hash(pending.frame()))
                    && digest(node, "history").equals(prefix(events.subList(0, cursor))), "Original compensation result changed");
                String resolution = text(node, "resolution");
                require(node.path("fixedReceiptVerified").isBoolean(), "Fixed receipt parser classification is missing");
                if (resolution.equals("OBSERVED_ONLY")) {
                    require(text(node, "observedOutcome").equals("NOT_OBSERVED") && !node.path("fixedReceiptVerified").booleanValue(), "Observation invented an original command ACK");
                    var observed = observation(events, plan, positive(node, "observationEnd"));
                    require(observed.end() > lastAction && observed.end() < event.id() && observed.sha256().equals(digest(node, "observation"))
                        && text(node, "receipt").equals("none") && goal(observed.statuses().get(pending.component()), pending.verb() == Verb.CLEANUP_CANDIDATE), "Original unknown action goal is not observed");
                    completed++; pending = null;
                } else {
                    require(Set.of("ACKNOWLEDGED", "UNKNOWN").contains(resolution) && !unknownRecorded
                        && node.path("observationEnd").isIntegralNumber() && node.path("observationEnd").longValue() == 0
                        && text(node, "observation").equals("none"), "Invalid or duplicated original ACK");
                    digest(node, "receipt");
                    require(Set.of("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN").contains(text(node, "observedOutcome")), "Actual original result classification is missing");
                    if (resolution.equals("ACKNOWLEDGED")) require(text(node, "observedOutcome").equals("SUCCEEDED") && node.path("fixedReceiptVerified").booleanValue(), "Unverified result cannot acknowledge a fixed action");
                    if (resolution.equals("ACKNOWLEDGED")) { completed++; pending = null; }
                    else unknownRecorded = true;
                }
                lastAction = event.id();
            } else if (event.type().equals(VERIFIED)) {
                fields(node, Set.of("formatVersion", "kind", "operation", "plan", "actions", "history", "observationEnd", "observation", "outcome", "problemCode", "eventCode"));
                require(text(node, "kind").equals("VERIFIED") && pending == null && completed == steps(plan).size()
                    && integer(node, "actions") == completed && digest(node, "history").equals(prefix(events.subList(0, cursor)))
                    && text(node, "outcome").equals("FAILED") && text(node, "problemCode").equals(ProblemCode.IO_FAILURE.name())
                    && text(node, "eventCode").equals(FAILURE), "Compensation verification is incomplete");
                var observed = observation(events, plan, positive(node, "observationEnd"));
                require(observed.end() > lastAction && observed.end() < event.id() && observed.sha256().equals(digest(node, "observation"))
                    && observed.statuses().values().stream().allMatch(s -> goal(s, true)), "Final compensation graph is unconfirmed");
                verified = new Verified(plan, event.id(), observed.end(), event.detail());
            } else throw new SQLException("Unexpected compensation event " + event.type());
            cursor++;
        }
        return new State(plan, completed, Optional.ofNullable(pending), lastAction, Optional.ofNullable(verified));
    }

    private record Step(Component component, Verb verb) { }
    public static Optional<ActionStep> next(State state) {
        if (state.pending().isPresent() || state.verified().isPresent() || state.completed() >= steps(state.plan()).size()) return Optional.empty();
        var step = steps(state.plan()).get(state.completed());
        return Optional.of(new ActionStep(state.completed() + 1, step.component(), step.verb()));
    }
    private static String semanticOperation(Verb verb) { return verb == Verb.GUARDED_ROLLBACK ? ROLLBACK_OPERATION : CLEANUP_OPERATION; }
    private static List<Step> steps(Plan plan) {
        var steps = new ArrayList<Step>();
        plan.components().stream().filter(c -> c.initial().current().isPresent()).forEach(c -> steps.add(new Step(c, Verb.GUARDED_ROLLBACK)));
        plan.components().forEach(c -> steps.add(new Step(c, Verb.CLEANUP_CANDIDATE)));
        return List.copyOf(steps);
    }

    /** Also used by the producer's APP/host/send-time evidence verifier; it performs no remote work. */
    public static State requireCurrent(ModContext context, TaskContext task, DeploymentAgentActionLog journal, Plan expected) throws Exception {
        var events = journal.events(task);
        var state = read(events, task.id());
        require(state.plan().equals(expected), "Original compensation plan changed");
        var store = new DeploymentExecutionStore(context.db());
        var formal = store.findForTask(task).orElseThrow();
        require(formal.phase().equals("EXECUTING") && formal.outcome().filter(v -> v != TaskResult.UNKNOWN).isEmpty(), "Known formal outcomes cannot start or resend remote compensation");
        requireBound(task.id(), journal.find(task).orElseThrow(), formal, store.publicationTemplate(formal).orElseThrow(), events, expected);
        return state;
    }

    private static ObjectNode result(Plan plan, Intent intent, String resolution, List<Event> events) throws Exception {
        return frame("RESULT", plan.operation()).put("plan", plan.sha256()).put("sequence", intent.sequence()).put("intent", intent.event())
            .put("intentSha256", hash(intent.frame())).put("resolution", resolution).put("history", prefix(events))
            .put("observedOutcome", "NOT_OBSERVED").put("fixedReceiptVerified", false);
    }

    private record Observation(long end, String sha256, Map<String, DeploymentOriginalPublishStatus> statuses) { }
    public static Optional<DiagnosticEligibility> diagnosticEligibility(State state, List<Event> events) throws Exception {
        if (state.pending().isEmpty() || state.verified().isPresent()) return Optional.empty();
        return diagnosticEligibility(state.plan(), state.pending().orElseThrow(), state.lastActionEvent(), events);
    }

    private static Optional<DiagnosticEligibility> diagnosticEligibility(Plan plan, Intent pending, long lastAction, List<Event> events) throws Exception {
        if (pending.verb() != Verb.GUARDED_ROLLBACK) return Optional.empty();
        var results = new ArrayList<Event>();
        for (var event : events) if (event.type().equals(RESULT) && positive(parsed(event.detail()), "intent") == pending.event()) results.add(event);
        if (results.size() != 1) return Optional.empty();
        var result = parsed(results.getFirst().detail());
        if (!text(result, "resolution").equals("UNKNOWN") || !text(result, "observedOutcome").equals("FAILED")
            || result.path("fixedReceiptVerified").asBoolean(true)) return Optional.empty();
        try {
            var latest = DeploymentAgentPublicationObservation.readLatest(events, plan.task());
            if (!latest.observationComplete() || latest.endEvent() <= lastAction) return Optional.empty();
            var observed = observation(events, plan, latest.endEvent());
            if (goal(observed.statuses().get(pending.component()), false)) return Optional.empty();
            var component = plan.components().stream().filter(c -> c.id().equals(pending.component())).findFirst().orElseThrow();
            return Optional.of(new DiagnosticEligibility(plan, pending, component, observed.end(), observed.sha256(), observed.statuses().get(pending.component())));
        } catch (Exception incomplete) { return Optional.empty(); }
    }
    private static Observation observation(List<Event> events, Plan plan, long through) throws Exception {
        var selected = events.stream().filter(e -> e.id() <= through).toList();
        var observed = DeploymentAgentPublicationObservation.readLatest(selected, plan.task());
        require(observed.observationComplete() && observed.endEvent() == through, "Latest complete original observation is required");
        var begin = selected.stream().filter(e -> e.type().equals(DeploymentAgentPublicationObservation.BEGIN)).reduce((a,b) -> b).orElseThrow();
        var block = selected.stream().filter(e -> e.id() >= begin.id()).toList();
        require(block.size() == 4 * plan.components().size() + 2 && block.stream().allMatch(e -> Set.of(
            DeploymentAgentPublicationObservation.BEGIN, DeploymentAgentPublicationObservation.COMPONENT, DeploymentAgentPublicationObservation.END,
            "HEALTH_STATUS_QUERY_REQUEST", "HEALTH_STATUS_QUERY_APPROVED", "HEALTH_STATUS_QUERY_RESULT").contains(e.type())), "Unrelated or incomplete effects inside original observation");
        var header = DeploymentAgentPublicationObservation.validateFrame(begin.detail());
        var planHeader = parsed(plan.header());
        require(uuid(header, "execution").equals(plan.execution()) && text(header, "server").equals(plan.server())
            && text(header, "application").equals(plan.application()) && text(header, "reviewRevision").equals(plan.reviewRevision())
            && text(header, "templateSha256").equals(text(planHeader, "request")) && text(header, "proposalSha256").equals(text(planHeader, "proposal")), "Observation belongs to another original publication");
        var statuses = new LinkedHashMap<String, DeploymentOriginalPublishStatus>();
        require(observed.componentFrames().size() == plan.components().size(), "Observation graph changed");
        for (int i = 0; i < plan.components().size(); i++) {
            var component = plan.components().get(i); var row = DeploymentAgentPublicationObservation.validateFrame(observed.componentFrames().get(i));
            require(text(row, "component").equals(component.id()) && text(row, "unit").equals(component.unit())
                && text(row, "expectedOwnerSha256").equals(component.owner()) && text(row, "candidate").equals(component.candidate())
                && text(row, "releaseSha256").equals(component.release()) && text(row, "layoutSha256").equals(text(planHeader, "layout")), "Observation component binding changed");
            statuses.put(component.id(), status(row.path("receipt").path("status")));
        }
        return new Observation(through, prefix(selected.stream().filter(e -> e.id() >= begin.id()).toList()), Map.copyOf(statuses));
    }

    private static List<String> originalBinding(UUID task, Snapshot saved, DeploymentExecutionRecord formal, DeploymentPublishProposal template,
                                                 List<Event> events, UUID operation) throws Exception {
        require(Set.of(4,5).contains(saved.executionSemantics()) && !saved.identity().rememberAuthorization()
            && formal.identity().taskId().equals(task) && formal.identity().mode() == DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT
            && formal.identity().server().id().equals(saved.identity().server()) && !formal.businessCommitted(), "Original Agent compensation identity changed");
        var delivery = only(events, "DELIVERY_EXECUTING", null); var publication = only(events, "AI_PUBLICATION", formal.identity().applicationId());
        require(events.stream().noneMatch(e -> Set.of("DELIVERY_RESULT", DeploymentAgentActionLog.DELIVERY_RECOVERY_SETTLED, DELIVERY_SETTLED,
            "AI_PUBLICATION_FINISHED", "AI_PUBLICATION_FAILED_ACCEPTED", "CONTINUATION_RESERVED", DeploymentAgentWorkspaceBinding.EVENT).contains(e.type())), "Original delivery is not uniquely pending");
        long revision = Long.parseLong(delivery.detail()); digestValue(delivery.action());
        require(revision > 0 && revision == events.stream().filter(e -> e.id() < delivery.id() && Set.of("PLAN", "PATCH_RESULT").contains(e.type())).count()
            && publication.id() > delivery.id() && publication.detail().equals("MODEL_GENERATED_COMMANDS"), "Original delivery interval changed");
        require(events.stream().noneMatch(e -> e.id() > delivery.id() && Set.of("SOURCE", "PLAN", "PLAN_APPLICATION", "PATCH_EXECUTING", "PATCH_RESULT", "CANDIDATE", "CURRENT_SOURCE",
            "SEAL_EXECUTING", "SEAL_RESULT", "SEALED_ARTIFACT", "ARTIFACT_INVALIDATED", "COMMAND_EXECUTING", "CAPABILITY_EXECUTING").contains(e.type())), "Original delivered build changed");
        var source = only(events, "SOURCE", null); digestValue(source.action()); digestValue(source.detail());
        var plan = latest(events, "PLAN", null, delivery.id()); var application = latest(events, "PLAN_APPLICATION", null, plan.id());
        require(source.id() < plan.id() && application.detail().equals(formal.identity().applicationId()) && template.graph().applicationId().equals(formal.identity().applicationId()), "Original graph/source changed");
        digestValue(plan.detail());
        var layout = template.graph().components().getFirst().unit().layout();
        var captured = only(events, "REMOTE_LAYOUT", null); var capturedLayout = parse(captured.detail());
        fields(capturedLayout, Set.of("formatVersion", "task", "server", "layout", "layoutSha256"));
        require(captured.id() < delivery.id() && captured.action().equals(task.toString()) && capturedLayout.path("formatVersion").intValue() == 1
            && text(capturedLayout, "task").equals(task.toString()) && text(capturedLayout, "server").equals(saved.identity().server())
            && text(capturedLayout, "layoutSha256").equals(layout.digest()) && capturedLayout.path("layout").toString().equals(layout.canonical()), "Original layout changed");
        for (String type : List.of("TARGET_PROFILE", "REMOTE_TARGET")) { var event = only(events,type,null); digestValue(event.detail()); require(event.id() < delivery.id() && event.action().isEmpty(), "Original target evidence changed"); }
        var observed = DeploymentAgentPublicationObservation.readLatest(events, task);
        require(observed.observationComplete(), "Initial original observation is incomplete");
        var observationBegin = events.stream().filter(e -> e.type().equals(DeploymentAgentPublicationObservation.BEGIN)).reduce((a,b)->b).orElseThrow();
        var observationHeader = DeploymentAgentPublicationObservation.validateFrame(observationBegin.detail());
        require(uuid(observationHeader,"execution").equals(formal.identity().executionId()) && text(observationHeader,"server").equals(saved.identity().server())
            && text(observationHeader,"application").equals(formal.identity().applicationId()) && text(observationHeader,"templateSha256").equals(hash(formal.requestJson()))
            && text(observationHeader,"proposalSha256").equals(hash(new DeploymentPublishResult(template.graph(),template.units()).toString())), "Initial observation template changed");
        String review = effectiveReview(saved,events);
        var header = frame("PLAN",operation).put("task",task.toString()).put("execution",formal.identity().executionId().toString()).put("server",saved.identity().server())
            .put("application",formal.identity().applicationId()).put("target",saved.identity().targetDigest()).put("configuration",saved.identity().configurationDigest()).put("policy",hash(saved.identity().toString()))
            .put("reviewRevision",review).put("layout",layout.digest()).put("request",hash(formal.requestJson())).put("recovery",recoveryDigest(formal))
            .put("template",hash(DeploymentPublishTemplateCodec.encodeTemplate(template))).put("proposal",hash(new DeploymentPublishResult(template.graph(),template.units()).toString()))
            .put("graph",hash(template.graph().toString())).put("prefixEnd",events.getLast().id()).put("prefix",prefix(events)).put("observationEnd",observed.endEvent())
            .put("observation",prefix(events.stream().filter(e->e.id()>=observationBegin.id() && e.id()<=observed.endEvent()).toList()))
            .put("delivery",delivery.id()).put("action",delivery.action()).put("revision",revision).put("source",source.detail()).put("archive",source.action()).put("plan",plan.detail());
        var names = header.putArray("components"); var frames = new ArrayList<String>(); int activeComponents = 0;
        require(template.graph().components().size() <= 16 && observed.componentFrames().size() == template.graph().components().size(), "Original graph is outside compensation scope");
        for (int index=0;index<template.graph().components().size();index++) {
            var part=template.graph().components().get(index); String id=part.componentId(); names.add(id);
            require(!(part.reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container) && part.unit().server().equals(formal.identity().server())
                && part.reviewedRuntime().workload().mode().name().equals("DAEMON")
                && part.unit().layout().equals(layout) && part.unit().equals(DeploymentUnitIdentity.forManaged(part.unit().id(),formal.identity().server(),part.unit().ownershipManifestSha256(),layout))
                && part.unit().id().equals(gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentManagedUnitResolver.managedId(formal.identity().applicationId(),id)), "Compensation requires original canonical native units");
            var unit=template.units().stream().filter(u->u.unit().equals(part.unit())).findFirst().orElseThrow();
            var candidate=only(events,"CANDIDATE",id); var current=latest(events,"CURRENT_SOURCE",id,delivery.id()); var artifact=latest(events,"SEALED_ARTIFACT",id,delivery.id());
            var tools=only(events,"SEALED_TOOLS",id); var seal=latest(events,"SEAL_EXECUTING",hash(id+":"+current.detail()+":seal"),artifact.id());
            var sealResult=latest(events,"SEAL_RESULT",seal.action(),artifact.id());
            var workspace=DeploymentAgentWorkspaceBinding.workspace(task,saved,events,part.unit().id(),source.action(),layout);
            require(candidate.detail().equals(workspace.candidateId()) && candidate.id()<current.id() && current.id()<seal.id() && seal.id()<sealResult.id()
                && sealResult.id()<artifact.id() && artifact.id()<delivery.id() && delivery.id()<tools.id() && tools.id()<publication.id()
                && seal.detail().equals(current.detail()) && sealResult.detail().equals(artifact.detail()) && tools.detail().matches("[0-9a-f]{64}:(true|false)")
                && unit.release().sha256().equals(DeploymentAgentInputs.publicationReleaseIdentity(artifact.detail(),current.detail(),unit.configuration().sha256(),part.reviewedRuntime(),part.resources())), "Original candidate/seal/release changed");
            DeploymentAgentActionLog.requireOriginalCandidateQuota(events,candidate,task,saved.identity(),source,layout.digest());
            var snapshotStart=only(events,"SNAPSHOT_EXECUTING",id); var snapshotEnd=only(events,"SNAPSHOT_RESULT",id);
            require(publication.id()<snapshotStart.id() && snapshotStart.id()<snapshotEnd.id() && snapshotEnd.id()<observationBegin.id()
                && snapshotStart.detail().equals(part.unit().ownershipManifestSha256()) && snapshotEnd.detail().equals("none:0:none:none"), "Original first-publication snapshot pair is unconfirmed");
            var publishes=events.stream().filter(e->e.type().equals("PUBLISH_EXECUTING") && e.action().equals(id)).toList();
            require(publishes.size()<=1,"Original component publication is ambiguous");
            for(var publish:publishes)
                require(publish.id()>snapshotEnd.id() && publish.id()<observationBegin.id() && publish.detail().equals(unit.release().sha256()), "Original attempted release changed");
            var row=DeploymentAgentPublicationObservation.validateFrame(observed.componentFrames().get(index));
            require(text(row,"component").equals(id) && text(row,"unit").equals(part.unit().id()) && text(row,"expectedOwnerSha256").equals(part.unit().ownershipManifestSha256())
                && text(row,"candidate").equals(candidate.detail()) && text(row,"releaseSha256").equals(unit.release().sha256()) && text(row,"layoutSha256").equals(layout.digest()), "Initial component observation changed");
            var initial=status(row.path("receipt").path("status"));
            if (initial.current().isPresent()) activeComponents++;
            require(initial.current().isEmpty() || publishes.size() == 1, "Active original release has no unique publication intent");
            require(eligibleInitial(initial, unit.release().sha256()), "Initial partial native state is not eligible");
            var node=frame("COMPONENT",operation).put("component",id).put("unit",part.unit().id()).put("owner",part.unit().ownershipManifestSha256()).put("candidate",candidate.detail())
                .put("releaseRoot",part.unit().releaseRoot()).put("candidateRoot",workspace.candidateRoot()).put("source",current.detail()).put("artifact",artifact.detail()).put("tools",tools.detail())
                .put("configuration",unit.configuration().sha256()).put("runtime",hash(part.reviewedRuntime().toString())).put("resources",hash(part.resources().toString())).put("release",unit.release().sha256())
                .put("receipts",prefix(List.of(candidate,current,seal,sealResult,artifact,tools))).put("snapshotStart",snapshotStart.id()).put("snapshotEnd",snapshotEnd.id())
                .put("snapshot",hash(snapshotEnd.detail())).put("observation",hash(observed.componentFrames().get(index)));
            status(node.putObject("status"),initial); frames.add(encoded(node));
        }
        var componentIds=template.graph().components().stream().map(p->p.componentId()).collect(java.util.stream.Collectors.toSet());
        for(String type:List.of("CANDIDATE","CURRENT_SOURCE","SEALED_ARTIFACT","SEALED_TOOLS","SNAPSHOT_EXECUTING","SNAPSHOT_RESULT"))
            require(events.stream().filter(e->e.type().equals(type)).map(Event::action).collect(java.util.stream.Collectors.toSet()).equals(componentIds),"Original component evidence set changed");
        require(events.stream().filter(e->e.type().equals("PUBLISH_EXECUTING")).allMatch(e->componentIds.contains(e.action())),"Foreign original publication effect");
        require(activeComponents > 0 && activeComponents < frames.size(), "Only an originally partially active native first-publication graph is eligible");
        frames.addFirst(encoded(header));
        return List.copyOf(frames);
    }

    private static void requireBound(UUID task,Snapshot saved,DeploymentExecutionRecord formal,DeploymentPublishProposal template,List<Event> events,Plan plan) throws Exception {
        var originalPrefix=events.stream().filter(e->e.id()<plan.beginEvent()).toList();
        DeploymentAgentPublicationRecoveryInstallation.requireNoPending(originalPrefix);
        var expected=originalBinding(task,saved,formal,template,originalPrefix,plan.operation());
        var actual=new ArrayList<String>(); actual.add(plan.header()); plan.components().forEach(c->actual.add(c.frame()));
        require(expected.equals(actual),"Original compensation binding changed");
    }

    /** Pure original-scope validation, not permission to install, create a Plan or send. The producer must separately inspect any pending installation. */
    public static void requireInitial(UUID task, Snapshot saved, DeploymentExecutionRecord formal, DeploymentPublishProposal template, List<Event> prefix) throws Exception {
        if (DeploymentAgentPublicationRecoveryInstallation.present(prefix)) DeploymentAgentPublicationRecoveryInstallation.read(prefix);
        originalBinding(task, saved, formal, template, prefix, new UUID(0, 0));
    }

    private static String recoveryDigest(DeploymentExecutionRecord formal) throws SQLException {
        var recovery=parse(formal.recoveryJson());
        // The supported execution codec owns this sole phase transition; original reference IDs stay exact.
        require(DeploymentExecutionCodec.supported(formal) && Set.of("EXECUTING","FINISHED").contains(formal.phase()),"Unsupported original recovery phase");
        recovery.put("phase","EXECUTING"); return hash(encode(recovery));
    }

    private static Component component(ObjectNode row,String frame) throws SQLException {
        for(String key:List.of("owner","source","artifact","configuration","runtime","resources","release","receipts","snapshot","observation")) digest(row,key);
        require(text(row,"tools").matches("[0-9a-f]{64}:(true|false)") && positive(row,"snapshotStart")<positive(row,"snapshotEnd"),"Invalid original component receipts");
        for(String key:List.of("component","unit","candidate")) require(text(row,key).matches("[a-z0-9][a-z0-9-]{0,127}"),"Invalid original component identifier");
        for(String key:List.of("releaseRoot","candidateRoot")) require(text(row,key).startsWith("/") && text(row,key).length()<=1024,"Invalid original resource root");
        return new Component(text(row,"component"),text(row,"unit"),text(row,"owner"),text(row,"candidate"),text(row,"release"),text(row,"releaseRoot"),text(row,"candidateRoot"),status(row.path("status")),frame);
    }

    private static void validateHeader(ObjectNode node) throws SQLException {
        for(String key:List.of("target","configuration","policy","reviewRevision","layout","request","recovery","template","proposal","graph","prefix","observation","action","source","archive","plan")) digest(node,key);
        for(String key:List.of("prefixEnd","observationEnd","delivery","revision")) positive(node,key);
        for(String key:List.of("server","application")) require(!text(node,key).isBlank() && text(node,key).length()<=512,"Invalid compensation owner");
    }

    private static String effectiveReview(Snapshot saved,List<Event> events) throws SQLException {
        String review=saved.identity().configurationDigest(); long previous=0;
        for(var event:events.stream().filter(e->e.type().startsWith("RECOVERY_REVIEW_")).toList()) {
            require(event.action().equals(saved.identity().configurationDigest()),"Foreign recovery review owner"); String next;
            if(previous==0) { require(event.type().equals("RECOVERY_REVIEW_BOUND"),"Missing recovery review predecessor"); next=event.detail(); }
            else { String start="previousEventId="+previous+"\npreviousReviewRevision="+review+"\nreviewRevision="; require(event.type().equals("RECOVERY_REVIEW_REBOUND") && event.detail().startsWith(start),"Recovery review chain changed"); next=event.detail().substring(start.length()); }
            digestValue(next); require(!next.equals(review),"Repeated recovery review revision"); previous=event.id(); review=next;
        }
        return review;
    }

    private static boolean localTail(Event event,UUID task,String application) {
        return event.type().equals("AI_PUBLICATION_CLEANED") && event.action().equals(application) && event.detail().equals("ABSENT")
            || event.type().equals("AI_RECOVERY_SETTLED") && event.action().equals(task.toString()) && event.detail().equals("FAILED:COMMAND_NATIVE_DATABASE_SECRET_CANDIDATE_SOURCE_SETTLED")
            || event.type().equals("STATE") && event.action().isEmpty() && event.detail().equals("FAILED")
            || event.type().equals(DELIVERY_SETTLED);
    }

    private static boolean otherEffectsSettled(List<Event> events) {
        return DeploymentAgentActionLog.effectsSettled(events.stream().filter(e->!e.type().startsWith("DELIVERY_")).toList());
    }

    private static boolean goal(DeploymentOriginalPublishStatus s,boolean candidateAbsent) {
        return s!=null && s.current().isEmpty() && !s.snapshot() && s.previousDigest().isEmpty() && s.previousRunning().isEmpty()
            && !s.maintenance() && !s.buildActive() && !s.unitPresent() && s.containerPresent().isEmpty() && s.retainedCount()==0
            && (!candidateAbsent || !s.candidate());
    }

    private static boolean eligibleInitial(DeploymentOriginalPublishStatus s, String release) {
        return !s.buildActive() && !s.snapshot() && s.previousDigest().isEmpty() && s.previousRunning().isEmpty() && s.containerPresent().isEmpty() && s.candidate()
            && (s.current().isPresent() ? s.current().equals(Optional.of(release)) && s.currentKind().equals("deployment") && s.currentMode().equals("DAEMON")
                && s.unitPresent() && s.maintenance() && s.retainedCount() == 1 : !s.unitPresent() && !s.maintenance() && s.retainedCount() == 0);
    }

    private static void requireNoBusiness(Connection connection,Plan plan) throws Exception {
        for(String table:List.of("managed_application","deployment_release")) {
            try(var query=connection.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE application_id=?")) {
                query.setString(1,plan.application());try(var rows=query.executeQuery()){require(rows.next() && rows.getLong(1)==0,"Compensation application has local business history");}
            }
        }
        for(var component:plan.components()) for(String table:List.of("deployment_unit","deployment_unit_release_binding")) {
            try(var query=connection.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE deployment_unit_id=?")) {
                query.setString(1,component.unit());try(var rows=query.executeQuery()){require(rows.next() && rows.getLong(1)==0,"Compensation unit has local business associations");}
            }
        }
        try(var query=connection.prepareStatement("SELECT * FROM deployment_release");var rows=query.executeQuery()) {
            int count=0; var units=new HashSet<String>();plan.components().forEach(c->units.add(c.unit()));
            while(rows.next()) {
                require(++count<=10000,"Retained release validation exceeds bound");
                var graph=DeploymentReleaseCodec.snapshot(DeploymentReleaseStore.row(rows)).graph();
                require(graph.components().stream().noneMatch(p->units.contains(p.unit().id())),"Another retained graph references the original unit");
            }
        }
    }

    private static DeploymentOriginalPublishStatus status(JsonNode node) throws SQLException {
        fields(node,Set.of("current","currentKind","currentMode","snapshot","previousDigest","previousKind","previousRunning","maintenance","candidate","buildActive","unitPresent","containerPresent","retainedCount"));
        for(String key:List.of("snapshot","maintenance","candidate","buildActive","unitPresent")) require(node.path(key).isBoolean(),"Status boolean required");
        for(String key:List.of("previousRunning","containerPresent")) require(Set.of("none","true","false").contains(text(node,key)),"Optional status boolean required");
        require(node.path("retainedCount").isInt(),"Status retained count required");
        try {return new DeploymentOriginalPublishStatus(optional(text(node,"current")),text(node,"currentKind"),text(node,"currentMode"),node.path("snapshot").booleanValue(),
            optional(text(node,"previousDigest")),text(node,"previousKind"),optionalBoolean(text(node,"previousRunning")),node.path("maintenance").booleanValue(),node.path("candidate").booleanValue(),
            node.path("buildActive").booleanValue(),node.path("unitPresent").booleanValue(),optionalBoolean(text(node,"containerPresent")),node.path("retainedCount").intValue());}
        catch(IllegalArgumentException invalid){throw new SQLException("Invalid typed compensation status",invalid);}
    }

    private static void status(ObjectNode node,DeploymentOriginalPublishStatus s) {
        node.put("current",s.current().orElse("none")).put("currentKind",s.currentKind()).put("currentMode",s.currentMode()).put("snapshot",s.snapshot())
            .put("previousDigest",s.previousDigest().orElse("none")).put("previousKind",s.previousKind()).put("previousRunning",s.previousRunning().map(Object::toString).orElse("none"))
            .put("maintenance",s.maintenance()).put("candidate",s.candidate()).put("buildActive",s.buildActive()).put("unitPresent",s.unitPresent())
            .put("containerPresent",s.containerPresent().map(Object::toString).orElse("none")).put("retainedCount",s.retainedCount());
    }

    private static String requestDigest(CommandRequest request) throws SQLException {
        return hash(encode(object().put("formatVersion",1).put("scriptSha256",hash(request.script())).put("stdinBytes",request.stdin().length)
            .put("stdinSha256",bytesDigest(request.stdin())).put("timeout",request.timeout().toString()).put("outputLimitBytes",request.outputLimitBytes())));
    }
    private static String resultDigest(OperationResult<CommandResult> result) throws SQLException {
        var node=object().put("formatVersion",1).put("outcome",result.outcome().name());
        if(result.value().isPresent()) {var value=result.value().orElseThrow();node.put("stdout",hash(value.stdout())).put("stderr",hash(value.stderr())).put("truncated",value.truncated());value.exitCode().ifPresentOrElse(v->node.put("exitCode",v),()->node.putNull("exitCode"));}
        else node.putNull("value");
        result.problem().ifPresentOrElse(p->node.put("problemCode",p.code().name()).put("eventCode",p.eventCode()),()->node.putNull("problemCode").putNull("eventCode"));
        return hash(encode(node));
    }
    private static Optional<String> optional(String value){return value.equals("none")?Optional.empty():Optional.of(value);}
    private static Optional<Boolean> optionalBoolean(String value){return value.equals("none")?Optional.empty():Optional.of(value.equals("true"));}
    private static ObjectNode frame(String kind,UUID operation){return object().put("formatVersion",1).put("kind",kind).put("operation",operation.toString());}
    private static ObjectNode parsed(String value)throws SQLException{require(value.length()<=4096,"Compensation frame exceeds character bound");var node=parse(value,16384);require(node.path("formatVersion").intValue()==1,"Unsupported compensation version");return node;}
    private static String encoded(ObjectNode node)throws SQLException{String value=encode(node,16384);parsed(value);return value;}
    private static String text(JsonNode node,String field)throws SQLException{return string(node,field);}
    private static int integer(JsonNode node,String field)throws SQLException{require(node.path(field).isInt() && node.path(field).intValue()>=0,"Nonnegative integer required");return node.path(field).intValue();}
    private static long positive(JsonNode node,String field)throws SQLException{require(node.path(field).isIntegralNumber()&&node.path(field).canConvertToLong()&&node.path(field).longValue()>0,"Positive event reference required");return node.path(field).longValue();}
    private static String digest(JsonNode node,String field)throws SQLException{String value=text(node,field);digestValue(value);return value;}
    private static void digestValue(String value)throws SQLException{require(value.matches("[0-9a-f]{64}"),"Compensation digest required");}
    private static String hash(String value){return DeploymentAgentInputs.hash(value);}
    private static String bytesDigest(byte[] value)throws SQLException{try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException e){throw new SQLException(e);}}
    private static String framesDigest(List<String> frames)throws SQLException{var node=object().put("formatVersion",1);var array=node.putArray("frames");frames.forEach(array::add);return hash(encode(node,1_048_576));}
    private static String prefix(List<Event> events)throws Exception{return DeploymentAgentPublicationObservation.prefixSha256(events);}
    private static Event only(List<Event> events,String type,String action)throws SQLException{var selected=events.stream().filter(e->e.type().equals(type)&&(action==null||e.action().equals(action))).toList();require(selected.size()==1,"Original "+type+" is not unique");return selected.getFirst();}
    private static Event latest(List<Event> events,String type,String action,long before)throws SQLException{return events.stream().filter(e->e.id()<before&&e.type().equals(type)&&(action==null||e.action().equals(action))).reduce((a,b)->b).orElseThrow(()->new SQLException("Original "+type+" is absent"));}
    private static void require(boolean condition,String message)throws SQLException{if(!condition)throw new SQLException(message);}
}
