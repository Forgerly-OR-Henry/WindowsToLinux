package gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionRecord;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishTemplateCodec;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseCodec;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentContinuation;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoalBudget;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentWorkspaceBinding;

/** Durable agent-only state and action evidence, independent of standard deployment. / 独立于标准部署的持久自主任务状态与动作依据。 */
public final class DeploymentAgentActionLog {
    /** Original owner-bound database. / 原所有者绑定的数据库。 */
    private final ModDatabase database;

    /** Binds the real active Schema. / 绑定真实活动 Schema。
     * @param database owned JDBC capability / 所属 JDBC 能力 */
    public DeploymentAgentActionLog(ModDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Original frozen task identity. / 原冻结任务身份。
     * @param server server profile / 服务器资料
     * @param targetDigest authenticated target digest / 已认证目标摘要
     * @param configurationDigest original model configuration digest / 原模型配置摘要
     * @param approvalMode selected review policy / 所选审核策略
     * @param rememberAuthorization original question repetition / 原问题重复规则 */
    public record Identity(String server, String targetDigest, String configurationDigest, String approvalMode,
                           boolean rememberAuthorization) {
        /** Checks immutable persisted fields. / 校验不可变持久字段。
         * @param server server identity / 服务器身份
         * @param targetDigest target digest / 目标摘要
         * @param configurationDigest model configuration digest / 模型配置摘要
         * @param approvalMode review policy / 审核策略
         * @param rememberAuthorization rememberAuthorization repetition / 授权重复规则 */
        public Identity {
            Objects.requireNonNull(server);
            if (!targetDigest.matches("[0-9a-f]{64}") || !configurationDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid agent identity digest");
            gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentAuthorizationPolicy.requireAllowed(approvalMode, rememberAuthorization);
        }
    }

    /** One retained nonsecret action observation. / 一项保留的不含秘密动作观察。
     * @param id ordered event identity / 有序事件身份
     * @param type controlled event type / 受控事件类型
     * @param action exact action identity / 精确动作身份
     * @param detail bounded digest or status / 有界摘要或状态 */
    public record Event(long id, String type, String action, String detail) {
    }

    /** Original dedicated durable task row. / 原专有持久任务行。
     * @param identity frozen target, configuration and policy / 冻结目标、配置与策略
     * @param state last durable state / 最近持久状态
     * @param executionSemantics persisted recovery-only version / 已存只恢复语义版本 */
    public record Snapshot(Identity identity, String state, int executionSemantics) {
    }

    public static final String DELIVERY_RECOVERY_SETTLED = "DELIVERY_RECOVERY_SETTLED";

    /** A recovery observation binds the original attempt without inventing its missing result. */
    public record OriginalDeliveryProof(long eventId, String action, long planRevision, UUID task,
                                        UUID execution, String server, String application, String target,
                                        String configuration, String layout, String template, String request,
                                        String recovery, String result, String releaseId, String release,
                                        String identity, String events) {
        public OriginalDeliveryProof {
            if (eventId <= 0 || planRevision <= 0 || !action.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid original delivery attempt");
            for (String value : List.of(target, configuration, layout, template, request, recovery, result, release, identity, events))
                if (!value.matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("Invalid original delivery digest");
            UUID.fromString(releaseId);
            Objects.requireNonNull(task);
            Objects.requireNonNull(execution);
            if (server.isBlank() || !application.matches("[a-z0-9][a-z0-9-]{0,62}"))
                throw new IllegalArgumentException("Invalid original delivery owner");
        }

        public String detail() {
            return DeploymentJsonDocument.object().put("formatVersion", 1).put("eventId", eventId).put("ordinal", 1)
                .put("action", action).put("planRevision", planRevision).put("task", task.toString())
                .put("execution", execution.toString()).put("server", server).put("application", application)
                .put("target", target).put("configuration", configuration).put("layout", layout).put("template", template)
                .put("request", request).put("recovery", recovery).put("result", result).put("releaseId", releaseId)
                .put("release", release).put("identity", identity).put("events", events).toString();
        }
    }

    /** Uses the trusted original journal's unique causal interval, never reconstructing an Agent plan. */
    public static Optional<OriginalDeliveryProof> originalDeliveryProof(UUID task, Snapshot saved,
                                                                        DeploymentExecutionRecord formal, DeploymentPublishProposal template, List<Event> all) throws SQLException {
        var proofs = all.stream().filter(e -> e.type().equals(DELIVERY_RECOVERY_SETTLED)).toList();
        long previousId = 0;
        for (var event : all) {
            if (event.id() <= previousId) throw new SQLException("Original event order is invalid");
            previousId = event.id();
        }
        var events = all.stream().filter(e -> !e.type().equals(DELIVERY_RECOVERY_SETTLED)).toList();
        var deliveries = events.stream().filter(e -> e.type().equals("DELIVERY_EXECUTING")).toList();
        var results = events.stream().filter(e -> e.type().equals("DELIVERY_RESULT")).toList();
        if (effectsSettled(events.stream().filter(e -> e.type().startsWith("DELIVERY_")).toList())) {
            if (!proofs.isEmpty()) throw new SQLException("Recovery proof has no original pending delivery");
            return Optional.empty();
        }
        if (deliveries.size() != 1 || !results.isEmpty() || proofs.size() > 1)
            throw new SQLException("Original delivery cannot be uniquely attributed");
        var formalRelease = gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionCodec.release(formal).orElseThrow();
        var delivery = deliveries.getFirst();
        var identity = formal.identity();
        DeploymentAgentWorkspaceBinding.find(task, saved, all);
        if (!Set.of(4, 5).contains(saved.executionSemantics()) || !identity.taskId().equals(task)
            || identity.mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT
            || !identity.server().id().equals(saved.identity().server()) || !formal.phase().equals("FINISHED")
            || !formal.outcome().equals(Optional.of(TaskResult.SUCCEEDED)) || !formal.businessCommitted() || !formal.cleanupComplete()
            || !template.graph().applicationId().equals(identity.applicationId()))
            throw new SQLException("Original successful formal delivery is unconfirmed");
        if (!effectsSettled(events.stream().filter(e -> !e.type().startsWith("DELIVERY_")).toList()))
            throw new SQLException("Another original effect remains unresolved");
        long revision = Long.parseLong(delivery.detail());
        if (!delivery.action().matches("[0-9a-f]{64}") || revision <= 0
            || revision != events.stream().filter(e -> e.id() < delivery.id()
            && Set.of("PLAN", "PATCH_RESULT").contains(e.type())).count())
            throw new SQLException("Original delivery plan revision is unconfirmed");
        if (events.stream().anyMatch(e -> e.id() > delivery.id() && Set.of("SOURCE", "PLAN", "PLAN_APPLICATION", "PATCH_EXECUTING",
            "PATCH_RESULT", "CANDIDATE", "CURRENT_SOURCE", "SEAL_EXECUTING", "SEAL_RESULT", "SEALED_ARTIFACT",
            "ARTIFACT_INVALIDATED", "COMMAND_EXECUTING", "CAPABILITY_EXECUTING").contains(e.type())))
            throw new SQLException("Original delivered build was subsequently changed");
        var source = only(events, "SOURCE", null);
        for (String type : List.of("TARGET_PROFILE", "REMOTE_TARGET")) {
            var header = only(events, type, null);
            if (header.id() >= delivery.id() || !header.action().isEmpty() || !header.detail().matches("[0-9a-f]{64}"))
                throw new SQLException("Original target evidence is unconfirmed");
        }
        var plan = latest(events, "PLAN", null, delivery.id());
        var application = latest(events, "PLAN_APPLICATION", null, plan.id());
        if (source.id() >= plan.id() || !source.action().matches("[0-9a-f]{64}") || !source.detail().matches("[0-9a-f]{64}")
            || !plan.detail().matches("[0-9a-f]{64}") || !application.detail().equals(identity.applicationId()))
            throw new SQLException("Original delivery source or application changed");
        var layout = template.graph().components().getFirst().unit().layout();
        var captured = only(events, "REMOTE_LAYOUT", null);
        var layoutNode = DeploymentJsonDocument.parse(captured.detail());
        DeploymentJsonDocument.fields(layoutNode, Set.of("formatVersion", "task", "server", "layout", "layoutSha256"));
        if (!captured.action().equals(task.toString()) || captured.id() >= delivery.id() || !layoutNode.path("formatVersion").isInt()
            || layoutNode.path("formatVersion").intValue() != 1
            || !DeploymentJsonDocument.string(layoutNode, "task").equals(task.toString())
            || !DeploymentJsonDocument.string(layoutNode, "server").equals(identity.server().id())
            || !DeploymentJsonDocument.string(layoutNode, "layoutSha256").equals(layout.digest())
            || !layoutNode.path("layout").toString().equals(layout.canonical()))
            throw new SQLException("Original delivery layout changed");
        var publication = only(events, "AI_PUBLICATION", identity.applicationId());
        var finished = only(events, "AI_PUBLICATION_FINISHED", identity.applicationId());
        var verified = latest(events, "AI_PUBLICATION_VERIFIED", identity.applicationId(), finished.id());
        var cleaned = latest(events, "AI_PUBLICATION_CLEANED", identity.applicationId(), Long.MAX_VALUE);
        if (delivery.id() >= publication.id() || publication.id() >= verified.id() || verified.id() >= finished.id()
            || finished.id() >= cleaned.id() || !publication.detail().equals("MODEL_GENERATED_COMMANDS")
            || !verified.detail().equals("HEALTHY") || !finished.detail().equals("MODEL_ACCEPTED_VERIFIED_RESULT") || !cleaned.detail().equals("ABSENT"))
            throw new SQLException("Original publication closure is unconfirmed");
        var binding = DeploymentJsonDocument.object().put("task", task.toString()).put("source", source.detail())
            .put("archive", source.action()).put("plan", plan.detail()).put("reviewedRevision", identity.reviewedRevision());
        var components = binding.putArray("components");
        var ids = new HashSet<String>();
        for (var part : template.graph().components()) {
            String id = part.componentId();
            ids.add(id);
            if (!part.unit().server().equals(identity.server()) || !part.unit().layout().equals(layout))
                throw new SQLException("Original component owner or layout changed");
            var unit = template.units().stream().filter(u -> u.unit().equals(part.unit())).findFirst().orElseThrow();
            var candidate = only(events, "CANDIDATE", id);
            var current = latest(events, "CURRENT_SOURCE", id, delivery.id());
            var sealed = latest(events, "SEALED_ARTIFACT", id, delivery.id());
            var tools = only(events, "SEALED_TOOLS", id);
            var workspace = DeploymentAgentWorkspaceBinding.workspace(task, saved, all, part.unit().id(), source.action(), layout);
            if (!gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentManagedUnitResolver.managedId(identity.applicationId(), id).equals(part.unit().id())
                || !workspace.candidateId().equals(candidate.detail()) || !current.detail().matches("[0-9a-f]{64}")
                || !sealed.detail().matches("[0-9a-f]{64}") || current.id() >= sealed.id() || candidate.id() >= sealed.id()
                || tools.id() <= delivery.id() || tools.id() >= publication.id() || !tools.detail().matches("[0-9a-f]{64}:(true|false)"))
                throw new SQLException("Original sealed candidate is unconfirmed");
            var seal = latest(events, "SEAL_EXECUTING", null, sealed.id());
            var sealResult = latest(events, "SEAL_RESULT", seal.action(), sealed.id());
            if (!seal.action().equals(hash(id + ":" + current.detail() + ":seal")) || !seal.detail().equals(current.detail())
                || current.id() >= seal.id() || seal.id() >= sealResult.id() || !sealResult.detail().equals(sealed.detail())
                || events.stream().anyMatch(e -> e.id() > seal.id() && e.id() < delivery.id()
                && (e.type().equals("PLAN") || e.type().equals("PATCH_RESULT")
                || e.type().equals("ARTIFACT_INVALIDATED") && e.action().equals(id))))
                throw new SQLException("Original seal was invalidated");
            requireQuota(events, candidate, task, saved.identity(), source, layout.digest());
            var quota = latest(events, "WORKSPACE_QUOTA", id, delivery.id());
            var selectedQuota = DeploymentAgentPreparationBinding.quotaJson(quota.detail());
            DeploymentAgentPreparationBinding.requireQuotaFields(selectedQuota, false);
            DeploymentAgentPreparationBinding.requireQuotaOwner(selectedQuota, task, saved.identity(), source, layout.digest(), plan.detail());
            String release = hash("agent-release:" + sealed.detail() + ":" + current.detail() + ":"
                + unit.configuration().sha256() + ":" + part.reviewedRuntime() + ":" + part.resources());
            if (!release.equals(unit.release().sha256()))
                throw new SQLException("Original artifact and formal release differ");
            components.addObject().put("component", id).put("unit", part.unit().id()).put("ownership", part.unit().ownershipManifestSha256())
                .put("candidate", candidate.detail()).put("source", current.detail()).put("artifact", sealed.detail())
                .put("tools", tools.detail()).put("configuration", unit.configuration().sha256()).put("release", release);
        }
        for (String type : List.of("CANDIDATE", "CURRENT_SOURCE", "SEALED_ARTIFACT", "SEALED_TOOLS", "WORKSPACE_QUOTA"))
            if (!events.stream().filter(e -> e.type().equals(type)).map(Event::action).collect(java.util.stream.Collectors.toSet()).equals(ids))
                throw new SQLException("Original delivery component set changed");
        var request = DeploymentJsonDocument.parse(formal.requestJson(), 144 * 1_048_576);
        String encoded = DeploymentPublishTemplateCodec.encodeTemplate(template);
        if (!request.path("publicationTemplate").toString().equals(encoded))
            throw new SQLException("Original formal template differs from its typed identity");
        var snapshot = DeploymentReleaseSnapshot.capture(template.graph(), template.units());
        String wholeRelease = DeploymentReleaseCodec.digest(DeploymentReleaseCodec.runtime(snapshot), DeploymentReleaseCodec.health(snapshot));
        if (!formalRelease.sha256().equals(wholeRelease))
            throw new SQLException("Original template and whole release differ");
        var review = binding.putArray("recoveryReview");
        String reviewRevision = saved.identity().configurationDigest();
        long previousReview = 0;
        for (var event : events.stream().filter(e -> e.type().startsWith("RECOVERY_REVIEW_")).toList()) {
            if (!event.action().equals(saved.identity().configurationDigest()))
                throw new SQLException("Original recovery review owner changed");
            String next;
            if (previousReview == 0) {
                if (!event.type().equals("RECOVERY_REVIEW_BOUND"))
                    throw new SQLException("Original recovery review predecessor is absent");
                next = event.detail();
            } else {
                String prefix = "previousEventId=" + previousReview + "\npreviousReviewRevision=" + reviewRevision + "\nreviewRevision=";
                if (!event.type().equals("RECOVERY_REVIEW_REBOUND") || !event.detail().startsWith(prefix))
                    throw new SQLException("Original recovery review chain changed");
                next = event.detail().substring(prefix.length());
            }
            if (!next.matches("[0-9a-f]{64}") || next.equals(reviewRevision))
                throw new SQLException("Original recovery review revision is invalid");
            previousReview = event.id();
            reviewRevision = next;
        }
        binding.put("recoveryReviewRevision", reviewRevision);
        events.stream().filter(e -> e.type().startsWith("RECOVERY_REVIEW_")).forEach(e ->
            review.addObject().put("id", e.id()).put("type", e.type()).put("action", e.action()).put("detail", e.detail()));
        var proof = new OriginalDeliveryProof(delivery.id(), delivery.action(), revision, task, identity.executionId(),
            identity.server().id(), identity.applicationId(), saved.identity().targetDigest(), saved.identity().configurationDigest(),
            layout.digest(), hash(encoded), hash(formal.requestJson()), hash(formal.recoveryJson()), hash(formal.resultJson().orElseThrow()),
            formalRelease.id().toString(), wholeRelease, hash(binding.toString()), eventDigest(events));
        if (!proofs.isEmpty() && (!proofs.getFirst().action().equals(delivery.action()) || !proofs.getFirst().detail().equals(proof.detail())
            || proofs.getFirst().id() <= events.getLast().id()))
            throw new SQLException("Stored recovery proof differs from the exact original binding");
        return Optional.of(proof);
    }

    /** Called inside the archive's original row locks and cleanup transaction only. */
    public static void appendDeliveryProof(Connection connection, UUID task, OriginalDeliveryProof proof) throws SQLException {
        if (connection.getAutoCommit() || !proof.task().equals(task) || proof.detail().length() > 4096)
            throw new SQLException("Original delivery proof requires its cleanup transaction");
        DeploymentAgentEvidenceLimit.require(connection, task, false);
        try (var insert = connection.prepareStatement("INSERT INTO deployment_agent_event(task_id,action_id,event_type,event_detail,created_at) VALUES(?,?,?,?,?)")) {
            insert.setString(1, task.toString());
            insert.setString(2, proof.action());
            insert.setString(3, DELIVERY_RECOVERY_SETTLED);
            insert.setString(4, proof.detail());
            insert.setLong(5, Instant.now().toEpochMilli());
            if (insert.executeUpdate() != 1)
                throw new SQLException("Original delivery proof insertion was unconfirmed");
        }
    }

    private static Event only(List<Event> events, String type, String action) throws SQLException {
        var selected = events.stream().filter(e -> e.type().equals(type) && (action == null || e.action().equals(action))).toList();
        if (selected.size() != 1) throw new SQLException("Original " + type + " evidence is not unique");
        return selected.getFirst();
    }

    private static Event latest(List<Event> events, String type, String action, long before) throws SQLException {
        return events.stream().filter(e -> e.id() < before && e.type().equals(type) && (action == null || e.action().equals(action)))
            .reduce((first, last) -> last).orElseThrow(() -> new SQLException("Original " + type + " evidence is absent"));
    }

    private static void requireQuota(List<Event> events, Event candidate, UUID task, Identity identity, Event source, String layout) throws SQLException {
        var quota = latest(events, "CANDIDATE_QUOTA", candidate.action(), candidate.id());
        var selected = latest(events, "WORKSPACE_QUOTA", candidate.action(), quota.id());
        var plan = latest(events, "PLAN", null, quota.id());
        var data = DeploymentAgentPreparationBinding.quotaJson(quota.detail());
        var declared = DeploymentAgentPreparationBinding.quotaJson(selected.detail());
        DeploymentAgentPreparationBinding.requireQuotaFields(data, true);
        DeploymentAgentPreparationBinding.requireQuotaFields(declared, false);
        DeploymentAgentPreparationBinding.requireQuotaOwner(data, task, identity, source, layout, plan.detail());
        DeploymentAgentPreparationBinding.requireQuotaOwner(declared, task, identity, source, layout, plan.detail());
        for (String key : List.of("task", "server", "source", "configuration", "layout", "plan", "workspaceBytes", "reserveBytes"))
            if (!data.path(key).equals(declared.path(key))) throw new SQLException("Original candidate quota changed");
        if (!data.path("candidate").asText().equals(candidate.detail()) || !data.path("prepareAction").asText().equals(
            gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCandidateCapacityException.binding(task,
                candidate.action(), source.detail(), plan.detail(), data.path("workspaceBytes").longValue())))
            throw new SQLException("Original candidate preparation binding changed");
        var prepare = latest(events, "PREPARE_EXECUTING", data.path("prepareAction").asText(), quota.id());
        var result = latest(events, "PREPARE_RESULT", prepare.action(), candidate.id());
        if (!prepare.detail().equals(candidate.action()) || prepare.id() >= quota.id() || quota.id() >= result.id()
            || !result.detail().matches("[0-9a-f]{64}"))
            throw new SQLException("Original candidate preparation result is unconfirmed");
    }

    /** Shares the unchanged original candidate/quota binding with Agent-only compensation evidence. */
    public static void requireOriginalCandidateQuota(List<Event> events, Event candidate, UUID task, Identity identity,
                                                      Event source, String layout) throws SQLException {
        requireQuota(events, candidate, task, identity, source, layout);
    }

    private static String hash(String value) {
        return gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.hash(value);
    }

    private static String eventDigest(List<Event> events) {
        var node = DeploymentJsonDocument.object().putArray("events");
        events.forEach(e -> node.addObject().put("id", e.id()).put("type", e.type()).put("action", e.action()).put("detail", e.detail()));
        return hash(node.toString());
    }

    private static boolean matchesDeliveryProof(Event event, List<Event> events) {
        try {
            if (event.detail().length() > 4096) return false;
            var node = DeploymentJsonDocument.parse(event.detail());
            DeploymentJsonDocument.fields(node, Set.of("formatVersion", "eventId", "ordinal", "action", "planRevision", "task", "execution",
                "server", "application", "target", "configuration", "layout", "template", "request", "recovery", "result", "releaseId", "release", "identity", "events"));
            if (!node.path("formatVersion").isInt() || node.path("formatVersion").intValue() != 1
                || !node.path("ordinal").isInt() || node.path("ordinal").intValue() != 1
                || !node.path("eventId").isIntegralNumber() || !node.path("eventId").canConvertToLong()
                || !node.path("planRevision").isIntegralNumber() || !node.path("planRevision").canConvertToLong())
                return false;
            var proof = new OriginalDeliveryProof(node.path("eventId").longValue(), DeploymentJsonDocument.string(node, "action"),
                node.path("planRevision").longValue(), DeploymentJsonDocument.uuid(node, "task"), DeploymentJsonDocument.uuid(node, "execution"),
                DeploymentJsonDocument.string(node, "server"), DeploymentJsonDocument.string(node, "application"), DeploymentJsonDocument.string(node, "target"),
                DeploymentJsonDocument.string(node, "configuration"), DeploymentJsonDocument.string(node, "layout"), DeploymentJsonDocument.string(node, "template"),
                DeploymentJsonDocument.string(node, "request"), DeploymentJsonDocument.string(node, "recovery"), DeploymentJsonDocument.string(node, "result"),
                DeploymentJsonDocument.string(node, "releaseId"), DeploymentJsonDocument.string(node, "release"), DeploymentJsonDocument.string(node, "identity"),
                DeploymentJsonDocument.string(node, "events"));
            var prefix = events.stream().filter(e -> e.id() < event.id()).toList();
            var original = only(prefix, "DELIVERY_EXECUTING", null);
            return event.action().equals(proof.action()) && event.detail().equals(proof.detail()) && original.id() == proof.eventId()
                && original.action().equals(proof.action()) && original.detail().equals(Long.toString(proof.planRevision()))
                && prefix.stream().noneMatch(e -> e.type().equals("DELIVERY_RESULT") || e.type().equals(DELIVERY_RECOVERY_SETTLED))
                && eventDigest(prefix).equals(proof.events());
        } catch (Exception invalid) {
            return false;
        }
    }

    /** Permanent admission uses the existing preference primary key and original admission transaction lock. */
    public DeploymentAgentContinuation.Ticket reserveSuccessor(TaskContext task, DeploymentAgentContinuation.Intent intent) throws Exception {
        if (!task.id().equals(intent.predecessor())) throw new SQLException("Foreign successor reservation");
        return continuationTransaction(task, connection -> {
            lockAgent(connection, task.id());
            var previous = ticket(connection, task.id(), true);
            if (previous.isPresent()) {
                if (!previous.orElseThrow().intent().equals(intent))
                    throw new SQLException("Original successor intent is frozen");
                return previous.orElseThrow();
            }
            var saved = find(connection, task.id()).orElseThrow();
            var events = events(connection, task.id());
            var accepted = events.stream().filter(e -> e.type().equals("AI_PUBLICATION_FAILED_ACCEPTED")).toList();
            if (accepted.size() != 1 || !accepted.getFirst().detail().equals(intent.acceptedFailure())
                || !accepted.getFirst().action().equals(intent.application())
                || !saved.identity().server().equals(intent.server()) || !saved.identity().targetDigest().equals(intent.target())
                || !saved.identity().configurationDigest().equals(intent.configuration()))
                throw new SQLException("Successor lacks the exact current failure acceptance");
            var current = root(connection, intent.root(), true);
            if (current.isEmpty()) {
                if (!intent.root().equals(task.id()) || intent.ordinal() != 2)
                    throw new SQLException("Original goal root is absent");
                insertPreference(connection, DeploymentAgentContinuation.ROOT_PREFIX + intent.root(),
                    DeploymentAgentContinuation.encode(new DeploymentAgentContinuation.Root(intent.root(), task.id(), 1)));
            } else if (!current.orElseThrow().activeLeaf().equals(task.id()) || current.orElseThrow().ordinal() + 1 != intent.ordinal())
                throw new SQLException("Only the current goal leaf may reserve a successor");
            var value = new DeploymentAgentContinuation.Ticket(intent, "RESERVED", null, null);
            insertPreference(connection, DeploymentAgentContinuation.TICKET_PREFIX + task.id(), DeploymentAgentContinuation.encode(value));
            append(connection, task.id(), "CONTINUATION_RESERVED", intent.ticket().toString(), intent.sha256());
            return value;
        });
    }

    /** The original callback reaches READY only after remote, source, references and formal failure close. */
    public DeploymentAgentContinuation.Ticket readySuccessor(TaskContext task, DeploymentAgentGoalBudget.Snapshot allowance) throws Exception {
        return continuationTransaction(task, connection -> {
            lockAgent(connection, task.id());
            var value = ticket(connection, task.id(), true).orElseThrow();
            requireFailedClosed(connection, value.intent());
            if (value.phase().equals("READY")) {
                if (!value.settledAllowance().equals(allowance))
                    throw new SQLException("Final original allowance changed");
                return value;
            }
            if (!value.phase().equals("RESERVED")) throw new SQLException("Successor is no longer reserved");
            var next = new DeploymentAgentContinuation.Ticket(value.intent(), "READY", null, allowance);
            replacePreference(connection, DeploymentAgentContinuation.TICKET_PREFIX + task.id(), value, next);
            append(connection, task.id(), "CONTINUATION_READY", value.intent().ticket().toString(),
                gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.hash(DeploymentAgentContinuation.encode(allowance)));
            return next;
        });
    }

    /** Called first by the authentic child; no source, connection or model can precede this claim. */
    public DeploymentAgentContinuation.Claim claimSuccessor(TaskContext child, DeploymentAgentContinuation.Intent intent,
                                                            gold.debug.windowstolinux.appapi.task.TaskService tasks) throws Exception {
        var archived = tasks.findArchived(intent.predecessor(), child).orElseThrow(() -> new SQLException("Original platform archive is absent"));
        if (!archived.id().equals(intent.predecessor()) || !archived.type().equals("deployment-agent")
            || archived.state() != gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED
            || archived.outcome().orElse(null) != TaskResult.FAILED || !archived.blockers().isEmpty())
            throw new SQLException("Original task is not an actually completed FAILED deployment");
        return continuationTransaction(child, connection -> {
            lockAgent(connection, intent.predecessor());
            var value = ticket(connection, intent.predecessor(), true).orElseThrow();
            if (!value.intent().equals(intent)) throw new SQLException("Frozen successor context changed");
            var current = root(connection, intent.root(), true).orElseThrow();
            if (value.phase().equals("CLAIMED")) {
                if (!child.id().equals(value.child()) || !current.activeLeaf().equals(child.id())
                    || current.ordinal() != intent.ordinal())
                    throw new SQLException("Successor already belongs to another child");
                return new DeploymentAgentContinuation.Claim(value, false);
            }
            if (!value.phase().equals("READY") || !current.activeLeaf().equals(intent.predecessor())
                || current.ordinal() + 1 != intent.ordinal() || child.id().equals(intent.predecessor()) || child.id().equals(intent.operation()))
                throw new SQLException("Successor ticket or current goal leaf is not eligible");
            requireFailedClosed(connection, intent);
            var claimed = new DeploymentAgentContinuation.Ticket(intent, "CLAIMED", child.id(), value.settledAllowance());
            replacePreference(connection, DeploymentAgentContinuation.TICKET_PREFIX + intent.predecessor(), value, claimed);
            replacePreference(connection, DeploymentAgentContinuation.ROOT_PREFIX + intent.root(), current,
                new DeploymentAgentContinuation.Root(intent.root(), child.id(), intent.ordinal()));
            append(connection, intent.predecessor(), "CONTINUATION_CLAIMED", intent.ticket().toString(), child.id().toString());
            return new DeploymentAgentContinuation.Claim(claimed, true);
        });
    }

    public Optional<DeploymentAgentContinuation.Ticket> successor(TaskContext actual, UUID predecessor) throws Exception {
        try (var connection = database.openConnection(actual)) {
            return ticket(connection, predecessor, false);
        }
    }

    public void blockSuccessor(TaskContext actual, UUID predecessor) throws Exception {
        continuationTransaction(actual, connection -> {
            var value = ticket(connection, predecessor, true).orElseThrow();
            if (!value.phase().equals("BLOCKED"))
                replacePreference(connection, DeploymentAgentContinuation.TICKET_PREFIX + predecessor,
                    value, new DeploymentAgentContinuation.Ticket(value.intent(), "BLOCKED", value.child(), value.settledAllowance()));
            return null;
        });
    }

    private static void requireFailedClosed(Connection connection, DeploymentAgentContinuation.Intent intent) throws Exception {
        var saved = find(connection, intent.predecessor()).orElseThrow();
        var events = events(connection, intent.predecessor());
        if (!saved.state().equals("FAILED") || !effectsSettled(events)
            || events.stream().filter(e -> e.type().equals("AI_PUBLICATION_FAILED_ACCEPTED") && e.detail().equals(intent.acceptedFailure())
            && e.action().equals(intent.application())).count() != 1
            || events.stream().filter(e -> e.type().equals("AI_PUBLICATION_FAILED_CLEANED") && e.action().equals(intent.application())
            && e.detail().equals("ABSENT")).count() != 1)
            throw new SQLException("Original failed mutation or cleanup remains unconfirmed");
        var failure = DeploymentJsonDocument.parse(intent.acceptedFailure());
        var execution = gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore.read(connection,
            UUID.fromString(DeploymentJsonDocument.string(failure, "execution")), true).orElseThrow();
        if (!execution.identity().taskId().equals(intent.predecessor()) || !execution.identity().applicationId().equals(intent.application())
            || !execution.phase().equals("FINISHED") || !execution.outcome().equals(Optional.of(TaskResult.FAILED))
            || execution.businessCommitted() || !execution.cleanupComplete()
            || !gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.hash(execution.identity().toString())
            .equals(DeploymentJsonDocument.string(failure, "executionIdentity")))
            throw new SQLException("Original formal failure and released references are unconfirmed");
    }

    @FunctionalInterface
    private interface ContinuationTransaction<T> {
        T run(Connection connection) throws Exception;
    }

    private <T> T continuationTransaction(TaskContext task, ContinuationTransaction<T> work) throws Exception {
        try (var connection = database.openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                try (var guard = connection.prepareStatement("MERGE INTO preference(preference_key,preference_value) KEY(preference_key) VALUES('deployment.agent.admission.v2','2')")) {
                    guard.executeUpdate();
                }
                T value = work.run(connection);
                connection.commit();
                return value;
            } catch (Exception failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    private static void lockAgent(Connection connection, UUID task) throws SQLException {
        try (var lock = connection.prepareStatement("SELECT task_id FROM deployment_agent_task WHERE task_id=? FOR UPDATE")) {
            lock.setString(1, task.toString());
            try (var rows = lock.executeQuery()) {
                if (!rows.next()) throw new SQLException("Original Agent ledger is absent");
            }
        }
    }

    private static Optional<String> preference(Connection connection, String key, boolean lock) throws SQLException {
        try (var query = connection.prepareStatement("SELECT preference_value FROM preference WHERE preference_key=?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, key);
            try (var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        }
    }

    private static Optional<DeploymentAgentContinuation.Ticket> ticket(Connection connection, UUID task, boolean lock) throws Exception {
        var value = preference(connection, DeploymentAgentContinuation.TICKET_PREFIX + task, lock);
        return value.isEmpty() ? Optional.empty() : Optional.of(DeploymentAgentContinuation.decode(value.orElseThrow(), DeploymentAgentContinuation.Ticket.class));
    }

    private static Optional<DeploymentAgentContinuation.Root> root(Connection connection, UUID task, boolean lock) throws Exception {
        var value = preference(connection, DeploymentAgentContinuation.ROOT_PREFIX + task, lock);
        return value.isEmpty() ? Optional.empty() : Optional.of(DeploymentAgentContinuation.decode(value.orElseThrow(), DeploymentAgentContinuation.Root.class));
    }

    private static void insertPreference(Connection connection, String key, String value) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO preference(preference_key,preference_value) VALUES(?,?)")) {
            insert.setString(1, key);
            insert.setString(2, value);
            if (insert.executeUpdate() != 1) throw new SQLException("Successor reservation was not retained");
        }
    }

    private static void replacePreference(Connection connection, String key, Object before, Object after) throws SQLException {
        try (var update = connection.prepareStatement("UPDATE preference SET preference_value=? WHERE preference_key=? AND preference_value=?")) {
            update.setString(1, DeploymentAgentContinuation.encode(after));
            update.setString(2, key);
            update.setString(3, DeploymentAgentContinuation.encode(before));
            if (update.executeUpdate() != 1) throw new SQLException("Successor admission changed concurrently");
        }
    }

    private static void append(Connection connection, UUID task, String type, String action, String detail) throws SQLException {
        DeploymentAgentEvidenceLimit.require(connection, task, false);
        if (detail.length() > 4096) throw new SQLException("Successor evidence exceeds the existing event limit");
        try (var insert = connection.prepareStatement("INSERT INTO deployment_agent_event(task_id,action_id,event_type,event_detail,created_at) VALUES(?,?,?,?,?)")) {
            insert.setString(1, task.toString());
            insert.setString(2, action);
            insert.setString(3, type);
            insert.setString(4, detail);
            insert.setLong(5, Instant.now().toEpochMilli());
            if (insert.executeUpdate() != 1) throw new SQLException("Successor evidence was not retained");
        }
    }

    /** Persists identity before any model invocation or remote effect. / 在模型调用或远端影响之前持久化身份。
     * @param task original task / 原任务
     * @param identity reviewed identity / 已审阅身份
     * @throws Exception on unavailable persistence / 持久化不可用时 */
    public void create(TaskContext task, Identity identity) throws Exception {
        create(task, identity, Optional.empty());
    }

    /** Serializes original admission and its precise non-secret endpoint proof. / 串行执行原准入及其精确非秘密端点证明。
     * @param task authentic original task / 真实原任务
     * @param identity original target and policy / 原目标及策略
     * @param endpoint exact canonical endpoint, absent when unconfirmed / 精确规范端点，未确认时为空
     * @throws Exception if another unresolved original task or persistence blocks admission / 其他未结原任务或持久性阻止准入时 */
    public void create(TaskContext task, Identity identity, Optional<String> endpoint) throws Exception {
        create(task, identity, endpoint, Optional.empty());
    }

    public void create(TaskContext task, Identity identity, Optional<String> endpoint,
                       Optional<DeploymentAgentContinuation.Intent> successor) throws Exception {
        if (endpoint.isPresent() && !endpoint.orElseThrow().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid canonical agent endpoint");
        try (Connection connection = database.openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.prepareStatement("MERGE INTO preference(preference_key,preference_value) KEY(preference_key) VALUES(?,?)")) {
                    lock.setString(1, "deployment.agent.admission.v2");
                    lock.setString(2, "2");
                    lock.executeUpdate();
                }
                requireAvailable(connection, task, identity, endpoint);
                if (successor.isPresent()) {
                    var intent = successor.orElseThrow();
                    var ticket = ticket(connection, intent.predecessor(), true).orElseThrow();
                    var root = root(connection, intent.root(), true).orElseThrow();
                    if (!ticket.intent().equals(intent) || !ticket.phase().equals("CLAIMED") || !task.id().equals(ticket.child())
                        || !root.activeLeaf().equals(task.id()) || root.ordinal() != intent.ordinal()
                        || !identity.server().equals(intent.server()) || !identity.targetDigest().equals(intent.target())
                        || !identity.configurationDigest().equals(intent.configuration())
                        || !endpoint.equals(Optional.of(intent.physicalTarget())))
                        throw new SQLException("Successor has no exact permanent claim");
                }
                insert(connection, task, identity, successor.isPresent() ? 5 : 4);
                if (successor.isPresent())
                    append(connection, task.id(), DeploymentAgentWorkspaceBinding.EVENT, task.id().toString(),
                        successor.orElseThrow().binding().detail(task.id(), identity.server()));
                try (var policy = connection.prepareStatement("INSERT INTO deployment_agent_event(task_id,action_id,event_type,event_detail,created_at) VALUES(?,'rememberAuthorization.v1','REMEMBER_AUTHORIZATION',?,?)")) {
                    policy.setString(1, task.id().toString());
                    policy.setString(2, Boolean.toString(identity.rememberAuthorization()));
                    policy.setLong(3, Instant.now().toEpochMilli());
                    policy.executeUpdate();
                }
                if (endpoint.isPresent()) {
                    try (var target = connection.prepareStatement("INSERT INTO deployment_agent_event(task_id,action_id,event_type,event_detail,created_at) VALUES(?,'','REMOTE_TARGET',?,?)")) {
                        target.setString(1, task.id().toString());
                        target.setString(2, endpoint.orElseThrow());
                        target.setLong(3, Instant.now().toEpochMilli());
                        target.executeUpdate();
                    }
                }
                connection.commit();
            } catch (Exception failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    /** Inserts original identity under the existing serialized admission transaction. / 在既有串行准入事务下插入原身份。
     * @param connection original transaction / 原事务
     * @param task authentic original task / 真实原任务
     * @param identity immutable original metadata / 不可变原元数据
     * @throws SQLException if the original insert cannot be confirmed / 原插入无法确认时 */
    private static void insert(Connection connection, TaskContext task, Identity identity, int semantics) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO deployment_agent_task("
            + "task_id,server_profile_id,target_digest,configuration_digest,automation_mode,approval_mode,"
            + "deployment_skill_id,approval_skill_id,execution_semantics_version,task_state,started_at,updated_at) "
            + "VALUES(?,?,?,?,'AGENT',?,?,'deployment-approval-1.0',?,'RUNNING',?,?)")) {
            long now = Instant.now().toEpochMilli();
            statement.setString(1, task.id().toString());
            statement.setString(2, identity.server());
            statement.setString(3, identity.targetDigest());
            statement.setString(4, identity.configurationDigest());
            statement.setString(5, identity.approvalMode());
            statement.setString(6, gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol.SKILL);
            statement.setInt(7, semantics);
            statement.setLong(8, now);
            statement.setLong(9, now);
            statement.executeUpdate();
        }
    }

    /** Refuses unresolved effects even when a terminal label or another profile would hide them. / 即使终态标签或另一资料会隐藏未结影响也拒绝准入。
     * @param connection original serialized transaction / 原串行事务
     * @param task actual new original task / 实际新原任务
     * @param identity actual selected profile / 实际所选资料
     * @param endpoint actual canonical endpoint / 实际规范端点
     * @throws SQLException if retained evidence cannot be read completely / 留存依据无法完整读取时
     * @throws ApiException if original unresolved ownership blocks admission / 原未结归属阻止准入时 */
    private static void requireAvailable(Connection connection, TaskContext task, Identity identity,
                                         Optional<String> endpoint) throws SQLException, ApiException {
        try (var query = connection.prepareStatement("SELECT task_id,server_profile_id,task_state FROM deployment_agent_task ORDER BY started_at LIMIT 10001");
             var rows = query.executeQuery()) {
            int count = 0;
            while (rows.next()) {
                if (++count > 10000) throw blocked(task, task.id(), "DEPLOYMENT_AGENT_TARGET_EVIDENCE_LIMIT");
                UUID original = UUID.fromString(rows.getString(1));
                if (original.equals(task.id()))
                    throw blocked(task, original, "DEPLOYMENT_AGENT_ORIGINAL_ALREADY_EXISTS");
                var events = events(connection, original);
                boolean terminal = Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(rows.getString(3));
                if (terminal && effectsSettled(events)) continue;
                var targets = events.stream().filter(event -> event.type().equals("REMOTE_TARGET")).map(Event::detail).distinct().toList();
                boolean known = targets.size() == 1 && targets.getFirst().matches("[0-9a-f]{64}");
                if (identity.server().equals(rows.getString(2)) || !known || endpoint.isEmpty()
                    || targets.getFirst().equals(endpoint.orElseThrow()))
                    throw blocked(task, original, known ? "DEPLOYMENT_AGENT_TARGET_BUSY" : "DEPLOYMENT_AGENT_TARGET_UNCONFIRMED");
            }
        }
    }

    /** Reads all bounded original events in the same admission transaction. / 在同一准入事务中读取全部有界原事件。
     * @param connection original serialized transaction / 原串行事务
     * @param original selected original identity / 所选原身份
     * @return complete ordered events / 完整有序事件
     * @throws SQLException if evidence exceeds its bound or is unavailable / 依据超界或不可用时 */
    public static List<Event> events(Connection connection, UUID original) throws SQLException {
        var result = new ArrayList<Event>();
        try (var query = connection.prepareStatement("SELECT event_id,event_type,action_id,event_detail FROM deployment_agent_event WHERE task_id=? ORDER BY event_id LIMIT 10001")) {
            query.setString(1, original.toString());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == 10000) throw new SQLException("Original agent evidence exceeds its bound");
                    result.add(new Event(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4)));
                }
            }
        }
        return List.copyOf(result);
    }

    /** Identifies the original blocker without exposing private connection or cause details. / 标识原阻塞者，不暴露私有连接或原因细节。
     * @param task actual rejected task / 实际被拒任务
     * @param original unresolved original UUID / 未结原UUID
     * @param event public classified reason / 公开分类原因
     * @return original-bound public rejection / 绑定原身份的公开拒绝 */
    private static ApiException blocked(TaskContext task, UUID original, String event) {
        return new ApiException(new Problem(ProblemCode.ACCESS_DENIED, event, "Reconcile the unresolved original agent task before starting this target",
            Map.of("task", task.id().toString(), "originalTask", original.toString()), Optional.of(original)));
    }

    /** Appends action intent before a side effect or a verified result afterward. / 副作用前追加动作意图，之后追加经核实结果。
     * @param task original task / 原任务
     * @param type controlled event type / 受控事件类型
     * @param action action binding / 动作绑定
     * @param detail nonsecret digest or bounded status / 不含秘密的摘要或有界状态
     * @throws Exception on failed persistence / 持久化失败时 */
    public void event(TaskContext task, String type, String action, String detail) throws Exception {
        if (!type.matches("[A-Z_]{1,48}") || action.length() > 128 || detail.length() > 4096
            || detail.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid agent event");
        try (Connection connection = database.openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.prepareStatement("SELECT task_id FROM deployment_agent_task WHERE task_id=? FOR UPDATE")) {
                    lock.setString(1, task.id().toString());
                    try (var rows = lock.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Original Agent task is absent");
                    }
                }
                DeploymentAgentEvidenceLimit.require(connection, task.id(), Set.of("PAUSED", "RESUMED",
                    "MODEL_REFRESH_REJECTED", "INSPECT_REQUESTED").contains(type));
                try (var statement = connection.prepareStatement("INSERT INTO deployment_agent_event("
                    + "task_id,action_id,event_type,event_detail,created_at) VALUES(?,?,?,?,?)")) {
                    statement.setString(1, task.id().toString());
                    statement.setString(2, action);
                    statement.setString(3, type);
                    statement.setString(4, detail);
                    statement.setLong(5, Instant.now().toEpochMilli());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    /** Commits an observed original absence only while the entire task and event prefix remain unchanged. */
    public void preparationAbsent(TaskContext task, DeploymentAgentPreparationAbsence.Captured captured, String proof) throws Exception {
        if (!task.id().equals(captured.task())) throw new SQLException("Foreign preparation observation");
        try (var connection = database.openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                DeploymentAgentEvidenceLimit.require(connection, task.id(), false);
                if (!find(connection, task.id()).orElseThrow().equals(captured.snapshot()))
                    throw new SQLException("Original preparation identity changed");
                try (var formal = connection.prepareStatement("SELECT execution_id FROM deployment_execution WHERE platform_task_id=?")) {
                    formal.setObject(1, task.id());
                    try (var rows = formal.executeQuery()) {
                        if (rows.next()) throw new SQLException("Original preparation acquired formal execution");
                    }
                }
                var current = events(connection, task.id());
                var receipt = current.stream().filter(event -> event.type().equals(DeploymentAgentPreparationAbsence.EVENT)).toList();
                if (receipt.size() == 1 && current.size() == captured.events().size() + 1
                    && current.subList(0, captured.events().size()).equals(captured.events()) && receipt.getFirst().equals(current.getLast())
                    && receipt.getFirst().detail().equals(proof)
                    && DeploymentAgentPreparationAbsence.matches(task.id(), captured.snapshot().identity(), captured.layout(), receipt.getFirst(), current)) {
                    connection.commit();
                    return;
                }
                if (!current.equals(captured.events())) throw new SQLException("Original preparation evidence changed");
                long last = current.getLast().id();
                var proposed = new Event(Math.addExact(last, 1), DeploymentAgentPreparationAbsence.EVENT, captured.preparation().action(), proof);
                if (!DeploymentAgentPreparationAbsence.matches(task.id(), captured.snapshot().identity(), captured.layout(), proposed, current))
                    throw new SQLException("Original preparation absence proof is invalid");
                try (var statement = connection.prepareStatement("INSERT INTO deployment_agent_event(task_id,action_id,event_type,event_detail,created_at) VALUES(?,?,?,?,?)")) {
                    statement.setString(1, task.id().toString());
                    statement.setString(2, captured.preparation().action());
                    statement.setString(3, DeploymentAgentPreparationAbsence.EVENT);
                    statement.setString(4, proof);
                    statement.setLong(5, Instant.now().toEpochMilli());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    /** Appends one compensated preparation while locking its original task and complete evidence prefix. */
    public void preparationCompensated(TaskContext task, DeploymentAgentPreparationCompensation.Captured captured, String proof) throws Exception {
        if (!task.id().equals(captured.task())) throw new SQLException("Foreign preparation compensation");
        try (var connection = database.openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                DeploymentAgentEvidenceLimit.require(connection, task.id(), false);
                if (!find(connection, task.id()).orElseThrow().equals(captured.snapshot()))
                    throw new SQLException("Original preparation identity changed");
                DeploymentAgentPreparationCompensation.requireNoBusinessReference(connection, captured);
                var current = events(connection, task.id());
                if (current.size() == captured.events().size() + 1 && current.subList(0, captured.events().size()).equals(captured.events())
                    && current.getLast().detail().equals(proof)
                    && DeploymentAgentPreparationCompensation.matches(task.id(), captured.snapshot().identity(), captured.layout(), current.getLast(), current)) {
                    connection.commit();
                    return;
                }
                if (!current.equals(captured.events())) throw new SQLException("Original preparation evidence changed");
                var proposed = new Event(Math.addExact(current.getLast().id(), 1), DeploymentAgentPreparationCompensation.EVENT,
                    captured.preparation().action(), proof);
                if (!DeploymentAgentPreparationCompensation.matches(task.id(), captured.snapshot().identity(), captured.layout(), proposed, current))
                    throw new SQLException("Original preparation compensation proof is invalid");
                try (var statement = connection.prepareStatement("INSERT INTO deployment_agent_event(task_id,action_id,event_type,event_detail,created_at) VALUES(?,?,?,?,?)")) {
                    statement.setString(1, task.id().toString());
                    statement.setString(2, captured.preparation().action());
                    statement.setString(3, DeploymentAgentPreparationCompensation.EVENT);
                    statement.setString(4, proof);
                    statement.setLong(5, Instant.now().toEpochMilli());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    /** Atomically retains the complete candidate-set proof after the platform checkpoint is durable. */
    public void preparationsCompensated(TaskContext task, DeploymentAgentMultiPreparationCompensation.Captured captured,
                                        DeploymentAgentMultiPreparationCompensation.Progress progress, String proof) throws Exception {
        if (!task.id().equals(captured.task()) || !proof.equals(progress.proof()))
            throw new SQLException("Foreign preparation set compensation");
        try (var connection = database.openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                DeploymentAgentEvidenceLimit.require(connection, task.id(), false);
                if (!find(connection, task.id()).orElseThrow().equals(captured.saved()))
                    throw new SQLException("Original preparation set identity changed");
                DeploymentAgentMultiPreparationCompensation.requireNoBusinessReference(connection, captured);
                var current = events(connection, task.id());
                progress.verify();
                if (!proof.equals(progress.proof())) throw new SQLException("Original cleanup checkpoint changed");
                if (current.size() == captured.events().size() + 1 && current.subList(0, captured.events().size()).equals(captured.events())
                    && current.getLast().detail().equals(proof) && DeploymentAgentMultiPreparationCompensation.matches(current.getLast(), current)) {
                    connection.commit();
                    return;
                }
                if (!current.equals(captured.events()))
                    throw new SQLException("Original preparation set evidence changed");
                var proposed = new Event(Math.addExact(current.getLast().id(), 1), DeploymentAgentMultiPreparationCompensation.EVENT,
                    captured.pending().preparation().action(), proof);
                var proposedEvents = new ArrayList<>(current);
                proposedEvents.add(proposed);
                if (!DeploymentAgentMultiPreparationCompensation.matches(proposed, proposedEvents))
                    throw new SQLException("Original preparation set proof is invalid");
                try (var statement = connection.prepareStatement("INSERT INTO deployment_agent_event(task_id,action_id,event_type,event_detail,created_at) VALUES(?,?,?,?,?)")) {
                    statement.setString(1, task.id().toString());
                    statement.setString(2, proposed.action());
                    statement.setString(3, proposed.type());
                    statement.setString(4, proof);
                    statement.setLong(5, Instant.now().toEpochMilli());
                    statement.executeUpdate();
                }
                progress.verify();
                connection.commit();
            } catch (Exception failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    /** Changes task state and records matching evidence in one transaction. / 在同一事务中改变任务状态并记录相应依据。
     * @param task original task / 原任务
     * @param state known lifecycle state / 已知生命周期状态
     * @throws Exception on uncertain write / 写入不明时 */
    public void state(TaskContext task, String state) throws Exception {
        if (!Set.of("RUNNING", "WAITING", "FAILED", "CANCELLED", "SUCCEEDED", "UNKNOWN", "INTERRUPTED").contains(state))
            throw new IllegalArgumentException("Invalid agent state");
        try (Connection connection = database.openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                DeploymentAgentEvidenceLimit.require(connection, task.id(), state.equals("WAITING"));
                long now = Instant.now().toEpochMilli();
                try (var update = connection.prepareStatement(
                    "UPDATE deployment_agent_task SET task_state=?,updated_at=? WHERE task_id=?")) {
                    update.setString(1, state);
                    update.setLong(2, now);
                    update.setString(3, task.id().toString());
                    if (update.executeUpdate() != 1)
                        throw new SQLException("Agent task missing");
                }
                try (var event = connection.prepareStatement("INSERT INTO deployment_agent_event("
                    + "task_id,action_id,event_type,event_detail,created_at) VALUES(?,'','STATE',?,?)")) {
                    event.setString(1, task.id().toString());
                    event.setString(2, state);
                    event.setLong(3, now);
                    event.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    /** Replaces only a verified paused route and records old and new identities atomically. / 仅替换已核实暂停路由并原子记录旧新身份。
     * @param task authentic original worker / 真实原工作线程
     * @param previous originally adopted digest / 原采用摘要
     * @param selected explicitly reviewed replacement / 明确审阅替换
     * @throws Exception if original state, identity or commit differs / 原状态、身份或提交不符时 */
    public void replaceConfiguration(TaskContext task, String previous, String selected) throws Exception {
        if (!previous.matches("[0-9a-f]{64}") || !selected.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid agent model revision");
        try (var connection = database.openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                DeploymentAgentEvidenceLimit.require(connection, task.id(), true);
                try (var update = connection.prepareStatement("UPDATE deployment_agent_task SET configuration_digest=?,"
                    + "updated_at=? WHERE task_id=? AND configuration_digest=? AND task_state='WAITING'")) {
                    update.setString(1, selected);
                    update.setLong(2, Instant.now().toEpochMilli());
                    update.setString(3, task.id().toString());
                    update.setString(4, previous);
                    if (update.executeUpdate() != 1) throw new SQLException("Original paused configuration changed");
                }
                try (var event = connection.prepareStatement("INSERT INTO deployment_agent_event("
                    + "task_id,action_id,event_type,event_detail,created_at) VALUES(?,?,'CONFIGURATION_REPLACED',?,?)")) {
                    event.setString(1, task.id().toString());
                    event.setString(2, previous);
                    event.setString(3, selected);
                    event.setLong(4, Instant.now().toEpochMilli());
                    event.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    /** Reads original ordered action evidence without reconstructing execution closures. / 读取原有序动作依据，不重建执行闭包。
     * @param task original task or recovery / 原任务或恢复
     * @return chronological evidence / 按时间排序的依据
     * @throws Exception on missing or unavailable evidence / 依据缺失或不可用时 */
    public List<Event> events(TaskContext task) throws Exception {
        return events(task, task.id());
    }

    /** Reads a selected owned business stream using the actual query task. / 使用实际查询任务读取所选所属业务事件流。
     * @param actual actual authenticated query task / 实际已认证查询任务
     * @param selected selected original UUID / 所选原UUID
     * @return bounded original ordered evidence / 有界原有序依据
     * @throws Exception if owned evidence is unavailable / 所属依据不可用时 */
    public List<Event> events(TaskContext actual, UUID selected) throws Exception {
        var events = new ArrayList<Event>();
        try (Connection connection = database.openConnection(actual);
             var query = connection.prepareStatement("SELECT event_id,event_type,action_id,event_detail "
                 + "FROM deployment_agent_event WHERE task_id=? ORDER BY event_id")) {
            query.setString(1, selected.toString());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (events.size() == 10000)
                        throw new SQLException("Agent event history exceeds recovery budget");
                    events.add(new Event(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4)));
                }
            }
        }
        return List.copyOf(events);
    }

    /** Reads the original identity and state without recreating task work. / 读取原身份与状态而不重建任务工作。
     * @param task original execution or recovery context / 原执行或恢复上下文
     * @return existing row or absence / 既有行或不存在
     * @throws Exception if retained evidence is unavailable / 留存依据不可用时 */
    public Optional<Snapshot> find(TaskContext task) throws Exception {
        return find(task, task.id());
    }

    /** Reads selected owned task evidence without impersonating its ended context. / 不冒充已结束上下文地读取所选所属任务依据。
     * @param actual actual query or original task / 实际查询或原任务
     * @param selected exact owned original UUID / 精确所属原UUID
     * @return existing immutable evidence or absence / 既有不可变依据或不存在
     * @throws Exception if original semantics or persistence differs / 原语义或持久化不符时 */
    public Optional<Snapshot> find(TaskContext actual, UUID selected) throws Exception {
        try (Connection connection = database.openConnection(actual)) {
            return find(connection, selected);
        }
    }

    public static Optional<Snapshot> find(Connection connection, UUID selected) throws SQLException {
        try (var query = connection.prepareStatement("SELECT server_profile_id,target_digest,configuration_digest,"
            + "approval_mode,task_state,deployment_skill_id,approval_skill_id,execution_semantics_version "
            + "FROM deployment_agent_task WHERE task_id=?")) {
            query.setString(1, selected.toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    return Optional.empty();
                boolean current = gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol.SKILL.equals(rows.getString(6)) && Set.of(4, 5).contains(rows.getInt(8));
                if (!current || !"deployment-approval-1.0".equals(rows.getString(7)))
                    throw new SQLException("Agent persisted semantics changed");
                var snapshot = new Snapshot(
                    new Identity(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rememberAuthorization(events(connection, selected))),
                    rows.getString(5), rows.getInt(8));
                if (rows.next())
                    throw new SQLException("Duplicate agent task");
                if (snapshot.executionSemantics() == 5) {
                    try {
                        var binding = DeploymentAgentWorkspaceBinding.find(selected, snapshot, events(connection, selected)).orElseThrow();
                        var claimed = ticket(connection, binding.predecessor(), false).orElseThrow();
                        var root = root(connection, binding.root(), false).orElseThrow();
                        if (!Set.of("CLAIMED", "BLOCKED").contains(claimed.phase()) || !selected.equals(claimed.child())
                            || !binding.equals(claimed.intent().binding()) || !root.root().equals(binding.root())
                            || root.ordinal() < claimed.intent().ordinal())
                            throw new SQLException("Successor event differs from its permanent original claim");
                    } catch (SQLException invalid) {
                        throw invalid;
                    } catch (Exception invalid) {
                        throw new SQLException("Successor claim binding is unavailable", invalid);
                    }
                }
                return Optional.of(snapshot);
            }
        }
    }

    /** Requires matching completion for every possibly started external effect. / 要求每项可能已开始的外部影响都有匹配的完成依据。
     * @param events original event stream / 原事件流
     * @return true only if no started effect lacks a trusted result / 仅无未结清的已启动影响时为真 */
    public static boolean effectsSettled(List<Event> events) {
        return effectsSettled(events, events);
    }

    public static boolean preparationsSettled(List<Event> events) {
        return effectsSettled(events.stream().filter(event -> event.type().startsWith("PREPARE_")).toList(), events);
    }

    private static boolean effectsSettled(List<Event> events, List<Event> complete) {
        var pending = new HashMap<List<String>, Integer>();
        for (var event : events) {
            if (Set.of("PREPARE_EXECUTING", "COMMAND_EXECUTING", "PATCH_EXECUTING", "SEAL_EXECUTING",
                "DELIVERY_EXECUTING", "CAPABILITY_EXECUTING").contains(event.type()))
                pending.merge(List.of(event.type().substring(0, event.type().length() - "_EXECUTING".length()), event.action()), 1, Integer::sum);
            if (Set.of("PREPARE_RESULT", "COMMAND_RESULT", "PATCH_RESULT", "SEAL_RESULT", "DELIVERY_RESULT", "CAPABILITY_RESULT")
                .contains(event.type()))
                pending.computeIfPresent(List.of(event.type().substring(0, event.type().length() - "_RESULT".length()), event.action()),
                    (key, count) -> count == 1 ? null : count - 1);
            if (event.type().equals(DELIVERY_RECOVERY_SETTLED)) {
                if (!matchesDeliveryProof(event, complete)) return false;
                var key = List.of("DELIVERY", event.action());
                if (!Objects.equals(pending.get(key), 1)) return false;
                pending.remove(key);
            }
            if (event.type().equals(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationCompensation.DELIVERY_SETTLED)) {
                if (!gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationCompensation.matchesDeliveryProof(event, complete))
                    return false;
                var key = List.of("DELIVERY", event.action());
                if (!Objects.equals(pending.get(key), 1)) return false;
                pending.remove(key);
            }
            if (Set.of("PREPARE_NOT_SENT", "COMMAND_NOT_SENT", "PATCH_NOT_SENT", "CAPABILITY_NOT_SENT").contains(event.type()) && event.detail().equals("ALTERNATIVE_REQUIRED")
                || Set.of("COMMAND_NOT_SENT", "CAPABILITY_NOT_SENT").contains(event.type()) && event.detail().equals("REVIEW_STOPPED_NOT_SENT")
                || event.type().equals("PREPARE_NOT_SENT") && event.detail().equals("INSTALL_REVIEW_STOPPED_NOT_SENT"))
                pending.computeIfPresent(List.of(event.type().substring(0, event.type().length() - "_NOT_SENT".length()), event.action()),
                    (key, count) -> count == 1 ? null : count - 1);
            if (event.type().equals(gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCandidateCapacityException.EVENT)) {
                if (!gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCandidateCapacityException.matches(event, complete))
                    return false;
                pending.computeIfPresent(List.of("PREPARE", event.action()), (key, count) -> count == 1 ? null : count - 1);
            }
            if (event.type().equals(DeploymentAgentPreparationAbsence.EVENT) && DeploymentAgentPreparationAbsence.matches(event, complete))
                pending.computeIfPresent(List.of("PREPARE", event.action()), (key, count) -> count == 1 ? null : count - 1);
            if (event.type().equals(DeploymentAgentMultiPreparationCompensation.EVENT)) {
                if (!DeploymentAgentMultiPreparationCompensation.matches(event, complete)) return false;
                var key = List.of("PREPARE", event.action());
                if (!Objects.equals(pending.get(key), 1)) return false;
                pending.remove(key);
            }
            if (event.type().equals(DeploymentAgentPreparationCompensation.EVENT)) {
                if (!DeploymentAgentPreparationCompensation.matches(event, complete)) return false;
                pending.computeIfPresent(List.of("PREPARE", event.action()), (key, count) -> count == 1 ? null : count - 1);
            }
        }
        return pending.isEmpty();
    }

    /** Reads exactly one original policy fact, never borrowing current preferences. / 精确读取一个原策略事实，绝不借用当前偏好。
     * @param events complete original event stream / 完整原事件流
     * @return original captured repetition strategy / 原已抓取重复策略
     * @throws SQLException if the original fact is absent, duplicated or malformed / 原事实缺失、重复或损坏时 */
    private static boolean rememberAuthorization(List<Event> events) throws SQLException {
        var values = events.stream().filter(event -> event.type().equals("REMEMBER_AUTHORIZATION")).toList();
        if (values.size() != 1 || !values.getFirst().action().equals("rememberAuthorization.v1")
            || !Set.of("true", "false").contains(values.getFirst().detail()))
            throw new SQLException("Original agent rememberAuthorization policy is unconfirmed");
        return Boolean.parseBoolean(values.getFirst().detail());
    }
}
