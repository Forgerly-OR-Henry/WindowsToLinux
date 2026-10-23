package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup;

import java.nio.charset.StandardCharsets;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.db.execution.*;
import gold.debug.windowstolinux.mod.deployment.linux.cleanup.DeploymentCandidateCompensationProgram;
import gold.debug.windowstolinux.mod.deployment.linux.cleanup.DeploymentCandidateCompensationProgram.*;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentOriginalPublishStatus;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.DeploymentPublishRecoveryFacts;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph.DeploymentStandardGraphPublishActionLog;

/** One explicit new compensation under the original task; an uncertain execution is followed only by receipt reads. */
public final class DeploymentCandidateCompensation {
    public static final String TITLE = "确认原候选补偿清理";
    public static final String CONFIRMATION = "CONFIRM_CANDIDATE_COMPENSATION ";
    public static final String RECEIPT_EVENT = "DEPLOYMENT_CANDIDATE_COMPENSATION_RECEIPT";
    public static final String OBSERVATION_EVENT = "DEPLOYMENT_CANDIDATE_COMPENSATION_OBSERVATION";
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = Set.of("formatVersion", "childKey", "stage", "binding", "originalGraph", "originalChild",
        "observationSha256", "questionSha256", "response", "event");
    private static final Set<String> CURRENT_FIELDS;
    static {
        var fields = new HashSet<>(FIELDS);
        fields.addAll(Set.of("guardSha256", "originalExecutionCreatedAtEpochMillis", "predecessor", "predecessorResponse"));
        CURRENT_FIELDS = Set.copyOf(fields);
    }

    private DeploymentCandidateCompensation() { }

    public static String key(String candidate) {
        return "candidate-compensation-" + DeploymentModelCommandIdentity.digest(candidate).substring(0, 24);
    }

    public static String successorKey(String candidate) { return key(candidate) + "-observation"; }

    public static String prompt(Binding binding, String observation, String canonicalObserverResponse, String canonicalLegacyReference) {
        if (canonicalObserverResponse.contains("\n") || canonicalObserverResponse.contains("\r")
            || canonicalLegacyReference.contains("\n") || canonicalLegacyReference.contains("\r"))
            throw new IllegalArgumentException("Compensation confirmation evidence is not canonical");
        try {
            var reference = JSON.readTree(canonicalLegacyReference);
            if (reference == null || !reference.isObject()
                || !canonicalLegacyReference.equals(DeploymentCandidateCompensationProgram.canonical(reference)))
                throw new IllegalArgumentException("Compensation legacy reference differs");
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Compensation legacy reference is invalid", invalid); }
        return "请求程序对原任务的精确候选执行一次新的受管补偿清理。原清理 UNKNOWN 历史会保留；原部署仍为 FAILED，不会重构建或重发布。"
            + "程序仍须重新核验候选归属、旧发送链终止、无构建及无其他写入者，并取得独立审批与单次许可。\n"
            + (canonicalLegacyReference.equals("{}") ? "" : "后继动作还要求确认者已核对旧观察的实际执行来源：旧检查点自身不含 guard 摘要，"
                + "只能依据外部冻结执行证据核对下列旧源码摘要、旧任务、操作及原文摘要；此确认不回填或改写旧记录。缺少这些证据请留空。\n")
            + "Task=" + binding.taskId() + "\nApplication=" + binding.applicationId() + "\nUnit=" + binding.unitId()
            + "\nCandidate=" + binding.layout().candidateRoot(binding.candidateId()) + "\nPlanSHA256=" + binding.planSha256()
            + "\nOwnerSHA256=" + binding.ownerManifestSha256() + "\nLayoutSHA256=" + binding.layout().digest()
            + "\nOperation=" + binding.operationId() + "\nBindingSHA256=" + binding.sha256() + "\nObservationSHA256=" + observation
            + "\nGuardSHA256=" + DeploymentCandidateCompensationProgram.bodySha256()
            + "\nObserverResponse=" + canonicalObserverResponse
            + "\nLegacyObservationSource=" + canonicalLegacyReference
            + "\n确认请输入 " + CONFIRMATION + binding.operationId() + "；留空保留 UNKNOWN。";
    }

    /** Called only for the already pending original candidate; it never mutates that old journal. */
    public static boolean reconcile(ModContext context, TaskContext task, DeploymentExecutionRecord record,
                                    DeploymentStandardGraphPublishActionLog root, String childKey,
                                    DeploymentPublishCleanupActionLog child, DeploymentOriginalPublishStatus status,
                                    RemoteSession session) throws ApiException {
        Journal journal = null;
        try {
            var layout = DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
            eligible(task, record, root.facts(), childKey, child.facts(), layout);
            if (!unpublished(status)) return false;
            String candidate = child.facts().path("candidate").asText();
            var retained = context.tasks().findRecoveryCheckpoint(task, key(candidate));
            var successor = context.tasks().findRecoveryCheckpoint(task, successorKey(candidate));
            if (retained.isPresent()) {
                var original = new Journal(retained.orElseThrow(), key(candidate));
                original.requireOriginal(context, task, record, root.facts(), childKey, child.facts(), layout);
                if (original.legacyRefusal()) {
                    journal = successor.isPresent() ? new Journal(successor.orElseThrow(), successorKey(candidate))
                        : create(context, task, record, root.facts(), childKey, child.facts(), layout, original);
                } else {
                    if (successor.isPresent()) return false;
                    journal = original;
                }
                journal.requireOriginal(context, task, record, root.facts(), childKey, child.facts(), layout);
                if (journal.stage().equals("SUCCEEDED") || journal.stage().equals("ABSENT"))
                    return journal.snapshot.released() || journal.release();
                if (journal.snapshot.released() || Set.of("REFUSED", "DECLINED").contains(journal.stage())) return false;
                if (journal.stage().equals("DISPATCHED"))
                    return receipt(context, task, record, session, journal);
                if (journal.stage().equals("VERIFY_PREDECESSOR") && !verifyPredecessor(context, task, record, session, journal))
                    return false;
                // A confirmation is tied to one observation and one invocation; interruption never silently confirms it.
                if (journal.legacy() || !journal.stage().equals("PREPARED")) return false;
            } else {
                if (successor.isPresent()) return false;
                journal = create(context, task, record, root.facts(), childKey, child.facts(), layout, null);
                journal.requireOriginal(context, task, record, root.facts(), childKey, child.facts(), layout);
            }
            var observed = call(context, task, record, session, request(Mode.OBSERVE, journal.binding(), "", record));
            journal.response(observed);
            if (observed.status().equals("ABSENT") && !observed.candidatePresent() && observed.quiescent()
                && !observed.observationSha256().isEmpty())
                return persist(context, task, journal, observed, true) && journal.terminal("ABSENT");
            if (!observed.ready()) { journal.terminal("REFUSED"); return false; }
            String question = prompt(journal.binding(), observed.observationSha256(), observed.canonicalResponse(), journal.canonicalLegacyReference());
            journal.value.put("observationSha256", observed.observationSha256())
                .put("questionSha256", DeploymentModelCommandIdentity.digest(question));
            journal.advance("AWAITING_CONFIRMATION");
            var selected = context.interaction().requestText(TITLE, question, false, task);
            if (selected.outcome() != TaskResult.SUCCEEDED
                || selected.value().filter((CONFIRMATION + journal.binding().operationId())::equals).isEmpty()) {
                journal.terminal("DECLINED"); return false;
            }
            journal.advance("CONFIRMED");
            var request = request(Mode.EXECUTE, journal.binding(), observed.observationSha256(), record);
            try (var command = DeploymentCandidateCompensationProgram.command(request)) {
                var intent = DeploymentCandidateCompensationProgram.intent(record.identity().server(), request, command);
                var permission = approve(context, task, intent, command);
                if (permission.outcome() != TaskResult.SUCCEEDED || permission.value().isEmpty()) {
                    journal.terminal("REFUSED"); return false;
                }
                // Persist before the single possible send. Any error from this point permits only receipt reads.
                journal.advance("DISPATCHED");
                var executed = response(request, session.execute(command, permission.value().orElseThrow()));
                journal.response(executed);
                if (executed.completed())
                    return persist(context, task, journal, executed, false) && journal.terminal("SUCCEEDED");
                return false;
            }
        } catch (ApiException unavailable) {
            if (journal != null && !Set.of("VERIFY_PREDECESSOR", "DISPATCHED", "SUCCEEDED", "ABSENT").contains(journal.stage()) && !journal.snapshot.released()) {
                journal.value.put("event", unavailable.problem().eventCode()); journal.terminal("REFUSED");
            }
            return false;
        } catch (IllegalArgumentException | IllegalStateException unavailable) { return false; }
    }

    private static Request request(Mode mode, Binding binding, String observation, DeploymentExecutionRecord record) {
        return new Request(mode, binding, observation, record.createdAt().toEpochMilli());
    }

    private static Journal create(ModContext context, TaskContext task, DeploymentExecutionRecord record, JsonNode root,
                                  String childKey, JsonNode child, DeploymentRemoteLayout layout, Journal predecessor) throws ApiException {
        UUID operation = predecessor == null ? UUID.randomUUID() : UUID.nameUUIDFromBytes(
            ("candidate-compensation-observation-v1:" + predecessor.binding().sha256()).getBytes(StandardCharsets.UTF_8));
        var binding = new Binding(task.id(), operation, record.identity().applicationId(), child.path("application").asText(),
            child.path("candidate").asText(), child.path("planSha256").asText(), child.path("ownerManifestSha256").asText(),
            child.path("archiveSha256").asText(), layout, DeploymentManagedHelperBundle.helperSha256(layout));
        if (predecessor != null && operation.equals(predecessor.binding().operationId()))
            throw new IllegalArgumentException("Successor operation must differ");
        var value = JSON.createObjectNode().put("formatVersion", 2).put("childKey", childKey)
            .put("stage", predecessor == null ? "PREPARED" : "VERIFY_PREDECESSOR")
            .put("observationSha256", "").put("questionSha256", "").put("response", "").put("event", "")
            .put("guardSha256", DeploymentCandidateCompensationProgram.bodySha256())
            .put("originalExecutionCreatedAtEpochMillis", record.createdAt().toEpochMilli()).put("predecessorResponse", "");
        value.set("binding", binding.json()); value.set("originalGraph", root); value.set("originalChild", child);
        value.set("predecessor", predecessor == null ? JSON.createObjectNode() : predecessor.reference());
        bounded(value);
        String key = predecessor == null ? key(binding.candidateId()) : successorKey(binding.candidateId());
        return new Journal(context.tasks().beginRecoveryCheckpoint(task, key, value.toString()), key);
    }

    private static boolean verifyPredecessor(ModContext context, TaskContext task, DeploymentExecutionRecord record,
                                             RemoteSession session, Journal journal) throws ApiException {
        var predecessor = journal.predecessor(context, task, record);
        var result = call(context, task, record, session, request(Mode.RECEIPT, predecessor.binding(), "", record));
        journal.value.put("predecessorResponse", result.canonicalResponse());
        journal.advance("VERIFY_PREDECESSOR");
        if (!receiptAbsent(result)) return false;
        journal.advance("PREPARED");
        return true;
    }

    private static boolean receiptAbsent(Observation observation) {
        return observation.status().equals("UNKNOWN") && observation.reason().equals("receipt-absent")
            && !observation.candidatePresent() && !observation.quiescent() && observation.systemProcessExclusion().isEmpty()
            && observation.receiptSha256().isEmpty() && observation.observationSha256().isEmpty();
    }

    private static boolean receipt(ModContext context, TaskContext task, DeploymentExecutionRecord record,
                                   RemoteSession session, Journal journal) throws ApiException {
        var result = call(context, task, record, session, request(Mode.RECEIPT, journal.binding(), "", record));
        journal.response(result);
        return result.completed() && persist(context, task, journal, result, false) && journal.terminal("SUCCEEDED");
    }

    /** The separate deployment log must durably retain the safe new-operation response before checkpoint archival. */
    private static boolean persist(ModContext context, TaskContext task, Journal journal, Observation observation, boolean observationOnly) {
        try {
            String details = observation.canonicalResponse();
            if (details.getBytes(StandardCharsets.UTF_8).length > DeploymentCandidateCompensationProgram.OUTPUT_LIMIT)
                return false;
            var target = context.logs().register(new LogCategory("deployment", "Deployment", Optional.of("deployment_log")));
            var event = new LogEvent(LogLevel.INFO, observationOnly ? OBSERVATION_EVENT : RECEIPT_EVENT,
                observationOnly ? "Original candidate absence observed; no compensation execution receipt is claimed"
                    : "New candidate compensation receipt verified; original deployment outcome remains FAILED",
                Optional.of(details), Optional.of(task.id()), Optional.of(journal.binding().operationId()), Optional.empty());
            var saved = context.logs().write(target, event);
            return saved != null && saved.storage() == LogStorage.DATABASE && !saved.truncated() && saved.problem().isEmpty();
        } catch (ApiException | RuntimeException unavailable) {
            // The checkpoint stays occupied; a dispatched operation can only retry its read-only receipt and local persistence.
            return false;
        }
    }

    private static Observation call(ModContext context, TaskContext task, DeploymentExecutionRecord record,
                                    RemoteSession session, Request request) throws ApiException {
        try (var command = DeploymentCandidateCompensationProgram.command(request)) {
            var intent = DeploymentCandidateCompensationProgram.intent(record.identity().server(), request, command);
            var permission = approve(context, task, intent, command);
            if (permission.outcome() != TaskResult.SUCCEEDED || permission.value().isEmpty())
                throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_CANDIDATE_COMPENSATION_NOT_APPROVED",
                    "The exact new candidate compensation request was not approved", Map.of(), Optional.empty()));
            return response(request, session.execute(command, permission.value().orElseThrow()));
        }
    }

    private static OperationResult<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant> approve(
        ModContext context, TaskContext task, ActionIntent intent, CommandRequest command) throws ApiException {
        if (!(context.interaction() instanceof DeploymentCommandReview review))
            throw new IllegalArgumentException("Compensation requires the original independent command review route");
        return review.approveCommand(intent, command, task);
    }

    private static Observation response(Request request, OperationResult<CommandResult> result) {
        if (result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty())
            throw new IllegalArgumentException("Compensation command observation is unknown");
        var value = result.value().orElseThrow();
        if (value.exitCode().filter(code -> code == 0).isEmpty() || value.truncated() || !value.stderr().isEmpty())
            throw new IllegalArgumentException("Compensation command receipt is incomplete");
        return DeploymentCandidateCompensationProgram.parse(request, value.stdout());
    }

    /** Independent approval validates the current formal record and both original checkpoint payloads again. */
    public static Map<String, Object> reviewFacts(ModContext context, TaskContext task, ActionIntent intent,
                                                  CommandRequest command, DeploymentRemoteLayout layout) throws ApiException {
        try {
            return reviewFacts(context, task, new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow(), intent, command, layout);
        } catch (java.sql.SQLException unavailable) {
            throw new ApiException(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_CANDIDATE_COMPENSATION_EVIDENCE_UNAVAILABLE",
                "Original compensation evidence cannot be confirmed", Map.of(), Optional.empty()));
        }
    }

    static Map<String, Object> reviewFacts(ModContext context, TaskContext task, DeploymentExecutionRecord record, ActionIntent intent,
                                          CommandRequest command, DeploymentRemoteLayout layout) throws ApiException {
        var request = DeploymentCandidateCompensationProgram.verify(intent, command, layout);
        if (!request.binding().taskId().equals(task.id()) || request.originalExecutionCreatedAtEpochMillis() != record.createdAt().toEpochMilli())
            throw new IllegalArgumentException("Compensation original task or creation time differs");
        var root = DeploymentStandardGraphPublishActionLog.reopen(context, task).orElseThrow();
        String key = key(request.binding().candidateId());
        var successor = context.tasks().findRecoveryCheckpoint(task, successorKey(request.binding().candidateId()));
        if (successor.isPresent()) key = successorKey(request.binding().candidateId());
        var journal = new Journal(successor.isPresent() ? successor.orElseThrow()
            : context.tasks().findRecoveryCheckpoint(task, key).orElseThrow(), key);
        var child = DeploymentPublishCleanupActionLog.reopen(context, task, journal.value.path("childKey").asText()).orElseThrow();
        journal.requireOriginal(context, task, record, root.facts(), journal.value.path("childKey").asText(), child.facts(), layout);
        boolean verifiesPredecessor = journal.stage().equals("VERIFY_PREDECESSOR") && request.mode() == Mode.RECEIPT
            && request.binding().equals(journal.predecessor(context, task, record).binding());
        if ((!verifiesPredecessor && !request.binding().equals(journal.binding())) || journal.snapshot.released()
            || !intent.serverIds().equals(List.of(record.identity().server().id()))
            || request.mode() == Mode.OBSERVE && (journal.legacy() || !journal.stage().equals("PREPARED"))
            || request.mode() == Mode.EXECUTE && (!journal.stage().equals("CONFIRMED")
                || journal.legacy()
                || !request.expectedObservationSha256().equals(journal.value.path("observationSha256").asText())
                || !journal.value.path("questionSha256").asText().equals(DeploymentModelCommandIdentity.digest(
                    prompt(journal.binding(), request.expectedObservationSha256(), journal.value.path("response").asText(), journal.canonicalLegacyReference()))))
            || request.mode() == Mode.RECEIPT && !verifiesPredecessor && !journal.stage().equals("DISPATCHED"))
            throw new IllegalArgumentException("Compensation recovery stage or confirmation differs");
        JsonNode observed = JSON.createObjectNode();
        if (request.mode() == Mode.EXECUTE) {
            var observation = DeploymentCandidateCompensationProgram.parse(request(Mode.OBSERVE, journal.binding(), "", record),
                journal.value.path("response").asText() + "\n");
            if (!observation.ready() || !observation.observationSha256().equals(request.expectedObservationSha256()))
                throw new IllegalArgumentException("Compensation exact observer response differs");
            try { observed = JSON.readTree(observation.canonicalResponse()); }
            catch (java.io.IOException invalid) { throw new IllegalArgumentException("Compensation observer evidence differs", invalid); }
        }
        return Map.ofEntries(Map.entry("binding", request.binding().json()), Map.entry("bindingSha256", request.binding().sha256()),
            Map.entry("mode", request.mode().wire()), Map.entry("recoveryCheckpoint", key), Map.entry("stage", journal.stage()),
            Map.entry("originalGraph", journal.value.path("originalGraph")), Map.entry("originalChild", journal.value.path("originalChild")),
            Map.entry("originalExecutionCreatedAtEpochMillis", request.originalExecutionCreatedAtEpochMillis()),
            Map.entry("guardSha256", DeploymentCandidateCompensationProgram.bodySha256()), Map.entry("observerResponse", observed),
            Map.entry("predecessor", journal.legacy() ? JSON.createObjectNode() : journal.value.path("predecessor")),
            Map.entry("predecessorResponse", journal.legacy() ? "" : journal.value.path("predecessorResponse").asText()),
            Map.entry("verifiesPredecessorReceiptOnly", verifiesPredecessor),
            Map.entry("confirmedQuestion", request.mode() == Mode.EXECUTE ? prompt(journal.binding(), request.expectedObservationSha256(),
                journal.value.path("response").asText(), journal.canonicalLegacyReference()) : ""),
            Map.entry("legacyExecutionSource", request.mode() == Mode.EXECUTE && !journal.canonicalLegacyReference().equals("{}")
                ? "USER_CONFIRMED_EXTERNAL_FROZEN_EXECUTION_EVIDENCE" : "NOT_ATTESTED"),
            Map.entry("completeStdinSha256", DeploymentModelCommandIdentity.digest(new String(DeploymentCandidateCompensationProgram.stdin(request), StandardCharsets.UTF_8))),
            Map.entry("meaning", "A new explicit original-task compensation, not replay authority. Full fixed stdin, guard SHA256, original FAILED graph and exact child are bound. The sole deterministic successor compares its immutable released legacy observation refusal byte-for-byte; VERIFY_PREDECESSOR permits only independently reviewed old-operation receipt reads. Only a durable receipt-absent response opens PREPARED. The legacy checkpoint did not store its writer or guard SHA: its declared v1 compatibility source hashes are external attestation requirements, not observed proof inside that checkpoint. Successor execute requires the exact confirmed question attesting separately frozen actual execution evidence; it also binds the exact observer response and system-process identity proof. The fixed OS role proof removes only one process-name rejection; candidate FD/cwd/root/cgroup/loop associations and every SSH or other writer check remain mandatory. No caller skipPID or unit exemption is accepted. DISPATCHED and any remote claim permit only receipt reads; refusal never creates another successor. Original deployment remains FAILED."));
    }

    private static void eligible(TaskContext task, DeploymentExecutionRecord record, JsonNode root, String childKey,
                                 JsonNode child, DeploymentRemoteLayout layout) {
        if (ProblemCode.valueOf(root.path("failureCode").asText()) == ProblemCode.UNKNOWN
            || !record.identity().taskId().equals(task.id()) || !record.identity().mode().name().equals("ASSISTED")
            || !record.phase().equals("EXECUTING") || record.businessCommitted() || record.cleanupComplete()
            || record.outcome().filter(value -> value != TaskResult.UNKNOWN && value != TaskResult.FAILED).isPresent()
            || !root.path("task").asText().equals(task.id().toString()) || !root.path("application").asText().equals(record.identity().applicationId())
            || !root.path("server").asText().equals(record.identity().server().id())
            || !root.path("planSha256").asText().equals(record.identity().reviewedRevision())
            || !root.path("phase").asText().equals("ALL_PREPARED") || !root.path("verifiedOutcome").asText().equals("FAILED")
            || root.has("attemptState") || root.has("buildReviewState")
            || !child.path("phase").asText().equals("CANDIDATE_CLEANUP_MAY_HAVE_RUN")
            || !child.path("backend").asText().equals("native") || !child.path("engine").asText().equals("none")
            || child.has("operation") || child.has("attemptState") || child.has("previousToken") || child.has("releaseSha256")
            || child.has("environmentRecovery") || child.has("buildReviewState"))
            throw new IllegalArgumentException("Original candidate is outside explicit compensation scope");
        JsonNode index = null;
        for (var part : root.path("components")) if (part.path("key").asText().equals(childKey)) {
            if (index != null) throw new IllegalArgumentException("Compensation child index is duplicated"); index = part;
        }
        if (index == null || !index.path("unitId").asText().equals(child.path("application").asText()))
            throw new IllegalArgumentException("Compensation child index differs");
        DeploymentPublishRecoveryFacts.read(child, record, index.path("unitId").asText(), index.path("planSha256").asText(), layout);
    }

    private static boolean unpublished(DeploymentOriginalPublishStatus status) {
        return status.current().isEmpty() && !status.snapshot() && !status.maintenance() && !status.buildActive()
            && !status.unitPresent() && status.containerPresent().filter(Boolean::booleanValue).isEmpty() && status.retainedCount() == 0;
    }

    private static void bounded(ObjectNode value) {
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > 12000)
            throw new IllegalArgumentException("Compensation checkpoint exceeds bound");
    }

    private static final class Journal {
        final TaskCheckpoint checkpoint;
        final String key;
        TaskCheckpoint.Snapshot snapshot;
        final ObjectNode value;
        Journal(TaskCheckpoint checkpoint, String key) throws ApiException {
            this.checkpoint = checkpoint; this.key = key; snapshot = checkpoint.snapshot();
            try {
                var parsed = JSON.readTree(snapshot.payload());
                if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("Compensation checkpoint is missing");
                var fields = new HashSet<String>(); parsed.fieldNames().forEachRemaining(fields::add);
                if (!(parsed instanceof ObjectNode node) || !node.path("formatVersion").isInt()
                    || !(node.path("formatVersion").intValue() == 1 && fields.equals(FIELDS)
                        || node.path("formatVersion").intValue() == 2 && fields.equals(CURRENT_FIELDS))
                    || !Set.of("VERIFY_PREDECESSOR", "PREPARED", "AWAITING_CONFIRMATION", "CONFIRMED", "DISPATCHED",
                    "SUCCEEDED", "ABSENT", "REFUSED", "DECLINED").contains(node.path("stage").asText()))
                    throw new IllegalArgumentException("Compensation checkpoint differs");
                value = node; binding(); bounded(value);
                for (String field : List.of("childKey", "stage", "observationSha256", "questionSha256", "response", "event"))
                    if (!value.path(field).isTextual()) throw new IllegalArgumentException("Compensation checkpoint text differs");
                for (String field : List.of("observationSha256", "questionSha256"))
                    if (!value.path(field).asText().matches("(?:[0-9a-f]{64})?")) throw new IllegalArgumentException("Compensation checkpoint digest differs");
                if (!legacy() && (!value.path("predecessor").isObject() || !value.path("predecessorResponse").isTextual()
                    || !value.path("guardSha256").isTextual() || !value.path("guardSha256").asText().matches("[0-9a-f]{64}")
                    || !value.path("originalExecutionCreatedAtEpochMillis").isIntegralNumber()
                    || !value.path("originalExecutionCreatedAtEpochMillis").canConvertToLong()
                    || value.path("originalExecutionCreatedAtEpochMillis").longValue() <= 0))
                    throw new IllegalArgumentException("Compensation checkpoint current contract differs");
            } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Compensation checkpoint is invalid", invalid); }
        }
        Binding binding() { return DeploymentCandidateCompensationProgram.decodeBinding(value.path("binding")); }
        String stage() { return value.path("stage").asText(); }
        boolean legacy() { return value.path("formatVersion").intValue() == 1; }
        String canonicalLegacyReference() {
            return legacy() ? "{}" : DeploymentCandidateCompensationProgram.canonical(value.path("predecessor"));
        }
        boolean legacyRefusal() {
            if (!legacy() || !snapshot.released() || snapshot.revision() != 2 || !stage().equals("REFUSED")
                || !value.path("questionSha256").asText().isEmpty() || !value.path("observationSha256").asText().isEmpty()
                || !value.path("event").asText().isEmpty()) return false;
            DeploymentCandidateCompensationProgram.verifyLegacyObservationRefusal(binding(), value.path("response").asText());
            return true;
        }
        ObjectNode reference() {
            if (!legacyRefusal()) throw new IllegalArgumentException("Compensation predecessor is not the sole legacy refusal");
            var reference = JSON.createObjectNode().put("key", key).put("payloadSha256", DeploymentModelCommandIdentity.digest(snapshot.payload()))
                .put("revision", snapshot.revision()).put("operationId", binding().operationId().toString()).put("helperSha256", binding().helperSha256())
                .put("kind", "EXTERNAL_EXECUTION_SOURCE_ATTESTATION_REQUIRED").put("legacyStateMachineRevision", 1);
            reference.putObject("expectedLegacyWriterSources")
                .put("compensationSha256", "71f4cea99b53dd037a56279d5b0a746a58bd88cbf9baae6a75d1c72aa98a447c")
                .put("programSha256", "2aaccc2efecb6a6fb019129ec774363dae92ae9e181f6442cf160407998359e9")
                .put("pythonSha256", "27bccfdf873b82b9fc587c8f674952efc49cfedf88a3109304dddc9916b2b6f1");
            return reference;
        }
        Journal predecessor(ModContext context, TaskContext task, DeploymentExecutionRecord record) throws ApiException {
            if (legacy() || value.path("predecessor").isEmpty()) throw new IllegalArgumentException("Compensation predecessor is missing");
            String originalKey = DeploymentCandidateCompensation.key(binding().candidateId());
            var original = new Journal(context.tasks().findRecoveryCheckpoint(task, originalKey).orElseThrow(), originalKey);
            original.requireOriginal(context, task, record, value.path("originalGraph"), value.path("childKey").asText(),
                value.path("originalChild"), binding().layout());
            if (!DeploymentCandidateCompensationProgram.canonical(original.reference())
                .equals(DeploymentCandidateCompensationProgram.canonical(value.path("predecessor"))))
                throw new IllegalArgumentException("Compensation predecessor tombstone changed");
            return original;
        }
        void requireOriginal(ModContext context, TaskContext task, DeploymentExecutionRecord record, JsonNode root, String childKey,
                             JsonNode child, DeploymentRemoteLayout layout) throws ApiException {
            eligible(task, record, root, childKey, child, layout);
            var binding = binding();
            if (!value.path("childKey").asText().equals(childKey) || !value.path("originalGraph").equals(root)
                || !value.path("originalChild").equals(child) || !binding.taskId().equals(task.id())
                || !binding.applicationId().equals(record.identity().applicationId()) || !binding.unitId().equals(child.path("application").asText())
                || !binding.candidateId().equals(child.path("candidate").asText()) || !binding.planSha256().equals(child.path("planSha256").asText())
                || !binding.ownerManifestSha256().equals(child.path("ownerManifestSha256").asText())
                || !binding.archiveSha256().equals(child.path("archiveSha256").asText()) || !binding.layout().equals(layout))
                throw new IllegalArgumentException("Compensation original checkpoint binding differs");
            if (legacy()) {
                if (!key.equals(DeploymentCandidateCompensation.key(binding.candidateId())) || stage().equals("VERIFY_PREDECESSOR"))
                    throw new IllegalArgumentException("Legacy compensation checkpoint key differs");
                return;
            }
            if (!value.path("guardSha256").asText().equals(DeploymentCandidateCompensationProgram.bodySha256())
                || value.path("originalExecutionCreatedAtEpochMillis").longValue() != record.createdAt().toEpochMilli())
                throw new IllegalArgumentException("Compensation guard or original execution time changed");
            if (value.path("predecessor").isEmpty()) {
                if (!key.equals(DeploymentCandidateCompensation.key(binding.candidateId())) || stage().equals("VERIFY_PREDECESSOR")
                    || !value.path("predecessorResponse").asText().isEmpty())
                    throw new IllegalArgumentException("Compensation base checkpoint differs");
            } else {
                var original = predecessor(context, task, record);
                UUID expected = UUID.nameUUIDFromBytes(("candidate-compensation-observation-v1:" + original.binding().sha256())
                    .getBytes(StandardCharsets.UTF_8));
                if (!key.equals(successorKey(binding.candidateId())) || !binding.operationId().equals(expected)
                    || binding.operationId().equals(original.binding().operationId()))
                    throw new IllegalArgumentException("Compensation unique successor identity differs");
                if (!stage().equals("VERIFY_PREDECESSOR")) {
                    var absent = DeploymentCandidateCompensationProgram.parse(request(Mode.RECEIPT, original.binding(), "", record),
                        value.path("predecessorResponse").asText() + "\n");
                    if (!receiptAbsent(absent)) throw new IllegalArgumentException("Compensation predecessor receipt absence is unconfirmed");
                }
            }
        }
        void advance(String stage) throws ApiException { value.put("stage", stage); bounded(value); snapshot = checkpoint.advance(snapshot, value.toString()); }
        void response(Observation observation) throws ApiException { value.put("response", observation.canonicalResponse()); advance(stage()); }
        boolean release() throws ApiException { checkpoint.close(); snapshot = checkpoint.snapshot(); return snapshot.released(); }
        boolean terminal(String stage) throws ApiException { advance(stage); return release(); }
    }
}
