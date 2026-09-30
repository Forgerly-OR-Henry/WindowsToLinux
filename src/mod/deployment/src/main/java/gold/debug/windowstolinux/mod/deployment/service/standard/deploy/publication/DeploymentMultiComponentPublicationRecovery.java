package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.linux.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentApplicationGraph;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentWorkload;

/** Explicitly reconciles one whole original graph without rebuilding, republishing or inventing source authority. / 显式核对一份原整图，不重构建、重发布或伪造来源权限。 */
final class DeploymentMultiComponentPublicationRecovery {
    /** Same original Mod activation as the registered reference recovery. / 与已登记引用恢复相同的原Mod启用。 */
    private final ModContext context;

    /** Binds original host capabilities. / 绑定原宿主能力。
     * @param context original activation / 原启用 */
    DeploymentMultiComponentPublicationRecovery(ModContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /** Reconciles only indexed original components and one full formal proposal. / 仅核对索引原组件及一份完整正式提案。
     * @param task authentic original recovery context / 真实原恢复上下文
     * @param record retained whole execution / 留存整图执行
     * @param root exact original graph journal / 精确原图账本
     * @return known whole result or retained unknown / 已知整图结果或保留未知 */
    OperationResult<DeploymentPublicationProposal> reconcile(TaskContext task, DeploymentExecutionRecord record,
            DeploymentGraphPublicationJournal root) {
        try {
            if (!confirmBuildReviews(task, record, root))
                return unknown("DEPLOYMENT_GRAPH_BUILD_REVIEW_UNCONFIRMED");
            var identity = record.identity();
            var rootFacts = root.facts();
            if (!task.id().equals(identity.taskId())
                    || !identity.applicationId().equals(rootFacts.path("application").asText())
                    || !identity.server().id().equals(rootFacts.path("server").asText())
                    || !identity.reviewedRevision().equals(rootFacts.path("planSha256").asText()))
                return unknown("DEPLOYMENT_GRAPH_ORIGINAL_IDENTITY_CHANGED");
            var proposal = new DeploymentExecutionRepository(context.db()).publicationTemplate(record);
            var components = components(task, record, root, proposal);
            if (components.isEmpty())
                return unknown("DEPLOYMENT_GRAPH_COMPONENT_EVIDENCE_MISSING");
            var known = result(root, proposal);
            if (root.released())
                return local(task, record, root, components, known);
            if (java.util.Set.of("REMOTE_CLEANUP_VERIFIED", "SESSION_CLOSED", "CLEANUP_VERIFIED")
                    .contains(rootFacts.path("phase").asText()))
                return local(task, record, root, components, known);
            if (!context.linux().pendingTransfers(task).isEmpty())
                return unknown("DEPLOYMENT_GRAPH_TRANSFER_PENDING");
            OperationResult<DeploymentPublicationProposal> verified;
            try (RemoteSession session = context.linux().connect(identity.server(), task)) {
                var observations = observe(task, components, session);
                if (observations.isEmpty())
                    return unknown("DEPLOYMENT_GRAPH_REMOTE_STATUS_UNCONFIRMED");
                verified = known.outcome() == Outcome.UNKNOWN
                        ? prove(task, record, root, proposal, components, observations, session)
                        : known;
                if (verified.outcome() == Outcome.FAILED && root.facts().path("phase").asText().equals("ROLLING_BACK"))
                    observations = observe(task, components, session);
                if (verified.outcome() == Outcome.UNKNOWN || observations.isEmpty()
                        || !businessMatches(task, record, root, proposal, components, observations, session, verified))
                    return unknown("DEPLOYMENT_GRAPH_BUSINESS_STATE_CHANGED");
                if (known.outcome() == Outcome.UNKNOWN && !root.verified(verified.outcome(), verified.problem()))
                    return unknown("DEPLOYMENT_GRAPH_RESULT_PERSISTENCE_UNCONFIRMED");
                if (!finishRemote(task, root, components, observations, session,
                        verified.outcome() == Outcome.SUCCEEDED))
                    return verified;
            }
            for (var component : components.values())
                if (!component.journal().sessionClosed())
                    return verified;
            if (!root.mark("SESSION_CLOSED"))
                return verified;
            return local(task, record, root, components, verified);
        } catch (Exception | LinkageError | AssertionError unavailable) {
            return unknown("DEPLOYMENT_GRAPH_RECOVERY_UNCONFIRMED");
        }
    }

    /** Confirms retained child pointers before the root identity is read, without resuming build work. / 读取根身份前确认留存子指针，不恢复构建工作。
     * @param task authentic original recovery / 真实原恢复
     * @param record original formal execution / 原正式执行
     * @param root original graph checkpoint / 原图检查点
     * @return whether every exact pointer is confirmed / 每个精确指针是否确认
     * @throws Exception if original durable evidence is unavailable / 原持久依据不可用时 */
    private boolean confirmBuildReviews(TaskContext task, DeploymentExecutionRecord record,
            DeploymentGraphPublicationJournal root) throws Exception {
        for (var index : root.indexes()) {
            var child = DeploymentPublicationCleanupJournal.reopen(context, task, index.key());
            if (child.isEmpty()
                    || !DeploymentPublicationAttemptRecovery.child(context, task, record, child.orElseThrow())
                    || !DeploymentAssistedReviewRecovery.child(context, task, record, child.orElseThrow()))
                return false;
        }
        return DeploymentPublicationAttemptRecovery.root(context, task, record, root)
                && DeploymentAssistedReviewRecovery.root(context, task, record, root);
    }

    /** One exact indexed checkpoint and its original runtime facts. / 一项精确索引检查点及原运行事实。
     * @param index immutable root index / 不变根索引
     * @param journal original component occupation / 原组件占用
     * @param facts validated original owner and phase / 已校验原归属及阶段 */
    private record Component(DeploymentGraphPublicationJournal.ComponentIndex index,
            DeploymentPublicationCleanupJournal journal, DeploymentPublicationRecoveryFacts facts) {
    }

    /** Reopens all original components and compares them to the complete saved graph when present. / 重开全部原组件，并在完整已存图存在时逐项比对。
     * @param task authentic task / 真实任务
     * @param record original execution / 原执行
     * @param root original index / 原索引
     * @param proposal complete proposal if already sealed / 已封存的完整提案
     * @return exact ordered components or absence / 精确有序组件或不存在
     * @throws ApiException if checkpoint access fails / 检查点访问失败时 */
    private Map<String, Component> components(TaskContext task, DeploymentExecutionRecord record,
            DeploymentGraphPublicationJournal root, Optional<DeploymentPublicationProposal> proposal)
            throws ApiException {
        var result = new LinkedHashMap<String, Component>();
        Map<String, DeploymentApplicationGraph.Component> published = new LinkedHashMap<>();
        if (proposal.isPresent())
            for (var value : proposal.orElseThrow().graph().components())
                published.put(value.componentId(), value);
        for (var index : root.indexes()) {
            var opened = DeploymentPublicationCleanupJournal.reopen(context, task, index.key());
            if (opened.isEmpty())
                return Map.of();
            var journal = opened.orElseThrow();
            var facts = DeploymentPublicationRecoveryFacts.read(journal.facts(), record, index.unitId(),
                    index.planSha256());
            if (!facts.unit().id().equals(index.unitId()) || result.containsKey(index.componentId()))
                return Map.of();
            if (proposal.isPresent()) {
                var value = published.get(index.componentId());
                if (value == null || !value.unit().equals(facts.unit()))
                    return Map.of();
                var release = proposal.orElseThrow().units().stream()
                        .filter(unit -> unit.unit().id().equals(index.unitId())).findFirst();
                if (release.isEmpty() || facts.release().isPresent()
                        && !facts.release().orElseThrow().equals(release.orElseThrow().release().sha256()))
                    return Map.of();
            }
            result.put(index.componentId(), new Component(index, journal, facts));
        }
        return proposal.isPresent() && published.size() != result.size()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(result);
    }

    /** Returns independently persisted whole-graph business facts without requiring new remote access. / 不要求新远端访问地返回独立持久整图业务事实。
     * @param root original root / 原根
     * @param proposal original full proposal / 原完整提案
     * @return known result or uncertainty / 已知结果或未知 */
    private static OperationResult<DeploymentPublicationProposal> result(DeploymentGraphPublicationJournal root,
            Optional<DeploymentPublicationProposal> proposal) {
        var facts = root.facts();
        String outcome = facts.path("verifiedOutcome").asText("");
        if (outcome.equals("SUCCEEDED"))
            return proposal.isPresent()
                    ? new OperationResult<>(Outcome.SUCCEEDED, proposal, Optional.empty())
                    : unknown("DEPLOYMENT_GRAPH_VERIFIED_PROPOSAL_MISSING");
        if (outcome.equals("FAILED") || outcome.equals("CANCELLED")) {
            try {
                var problem = new Problem(ProblemCode.valueOf(facts.path("failureCode").asText()),
                        facts.path("failureEvent").asText(), "Original whole-graph operation failed", Map.of(),
                        Optional.empty());
                return new OperationResult<>(Outcome.valueOf(outcome), Optional.empty(), Optional.of(problem));
            } catch (IllegalArgumentException invalid) {
                return unknown("DEPLOYMENT_GRAPH_VERIFIED_FAILURE_INVALID");
            }
        }
        return unknown("DEPLOYMENT_GRAPH_BUSINESS_NOT_VERIFIED");
    }

    /** Reads every original helper status on the same recovered session without remote writes. / 在同一恢复会话只读全部原helper状态。
     * @param task original task / 原任务
     * @param components exact indexed components / 精确索引组件
     * @param session borrowed recovery session / 借用恢复会话
     * @return complete observations or absence / 完整观察或不存在
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    private Map<String, DeploymentOriginalPublicationStatus> observe(TaskContext task,
            Map<String, Component> components, RemoteSession session) throws ApiException {
        var observed = new LinkedHashMap<String, DeploymentOriginalPublicationStatus>();
        for (var index : components.values()) {
            var facts = index.facts();
            var read = facts.operation().isEmpty()
                    ? channel(session, task, index).originalStatus(facts.unit(), facts.candidate(), facts.release(),
                            facts.backend(), facts.engine())
                    : channel(session, task, index).originalStatus(facts.unit(), facts.candidate(), facts.release(),
                            facts.backend(), facts.engine(), facts.operation().orElseThrow());
            if (read.outcome() != Outcome.SUCCEEDED || read.value().isEmpty())
                return Map.of();
            observed.put(index.index().componentId(), read.value().orElseThrow());
        }
        return Map.copyOf(observed);
    }

    /** Proves full published health or full original rollback; mixed or missing evidence remains unknown. / 仅证明整图发布健康或整图原状恢复；混合及缺失依据仍未知。
     * @param task original task / 原任务
     * @param record original execution / 原执行
     * @param root original graph facts / 原图事实
     * @param proposal complete business graph / 完整业务图
     * @param components exact indexed components / 精确索引组件
     * @param statuses original remote observations / 原远端观察
     * @param session borrowed session / 借用会话
     * @return newly verified result or uncertainty / 新核实结果或未知
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    private OperationResult<DeploymentPublicationProposal> prove(TaskContext task, DeploymentExecutionRecord record,
            DeploymentGraphPublicationJournal root, Optional<DeploymentPublicationProposal> proposal,
            Map<String, Component> components, Map<String, DeploymentOriginalPublicationStatus> statuses,
            RemoteSession session) throws ApiException {
        String phase = root.facts().path("phase").asText();
        if (phase.equals("PUBLISHING") && proposal.isPresent() && components.values().stream().allMatch(
                component -> component.facts().release().isPresent() && publishAttempted(component.facts().phase()))
                && healthy(task, proposal.orElseThrow(), components, statuses, session))
            return new OperationResult<>(Outcome.SUCCEEDED, proposal, Optional.empty());
        if (phase.equals("PUBLISHING") && proposal.isPresent()
                && components.values().stream().anyMatch(component -> publishAttempted(component.facts().phase()))
                && components.values().stream().anyMatch(component -> !publishAttempted(component.facts().phase())))
            return restorePartial(task, root, proposal.orElseThrow(), components, statuses, session);
        if (phase.equals("ROLLING_BACK")) {
            var failure = components.values().stream().map(component -> component.facts().failure())
                    .flatMap(Optional::stream).findFirst();
            if (failure.isEmpty() || !restored(task, components, statuses, session))
                return unknown("DEPLOYMENT_GRAPH_ROLLBACK_NOT_PROVEN");
            return new OperationResult<>(Outcome.FAILED, Optional.empty(), failure);
        }
        if (java.util.Set.of("PREPARING", "ALL_PREPARED", "ALL_BUILT", "INPUTS_STAGED", "SNAPSHOTS_VERIFIED")
                .contains(phase) && prepublishUnchanged(task, components, statuses, session, false))
            return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_GRAPH_PUBLISH_NOT_STARTED",
                            "Original whole-graph publish did not begin", Map.of(), Optional.empty())));
        return unknown("DEPLOYMENT_GRAPH_PREPUBLISH_NOT_VERIFIED");
    }

    /** Restores a partially published original graph only after all affected and untouched states are independently known. / 仅全部已影响及未影响状态均获独立确认后恢复原部分发布图。
     * @param task original recovery task / 原恢复任务
     * @param root original graph journal / 原图账本
     * @param proposal exact formal graph / 精确正式图
     * @param components original indexed checkpoints / 原索引检查点
     * @param statuses complete original observations / 完整原观察
     * @param session original recovery session / 原恢复会话
     * @return confirmed failed restoration or retained uncertainty / 已确认失败恢复或保留未知
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    private OperationResult<DeploymentPublicationProposal> restorePartial(TaskContext task,
            DeploymentGraphPublicationJournal root, DeploymentPublicationProposal proposal,
            Map<String, Component> components, Map<String, DeploymentOriginalPublicationStatus> statuses,
            RemoteSession session) throws ApiException {
        var affected = new java.util.ArrayList<DeploymentApplicationGraph.Component>();
        for (var value : proposal.graph().components()) {
            var component = components.get(value.componentId());
            if (publishAttempted(component.facts().phase())) {
                if (!java.util.Set.of("PUBLISH_MAY_HAVE_RUN", "PUBLISHED_VERIFIED").contains(component.facts().phase())
                        || component.facts().snapshot().isEmpty()
                        || !componentHealthy(task, value, component, statuses.get(value.componentId()), session))
                    return unknown("DEPLOYMENT_GRAPH_PARTIAL_PUBLISH_UNCONFIRMED");
                affected.add(value);
            } else if (!prepublishUnchanged(task, Map.of(value.componentId(), component),
                    Map.of(value.componentId(), statuses.get(value.componentId())), session, false))
                return unknown("DEPLOYMENT_GRAPH_PARTIAL_PREVIOUS_UNCONFIRMED");
        }
        var failure = new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_GRAPH_PARTIAL_PUBLICATION",
                "Original graph publication stopped before every component was published", Map.of(), Optional.empty());
        if (!root.mark("ROLLING_BACK"))
            return unknown("DEPLOYMENT_GRAPH_ROLLBACK_BOUNDARY_UNCONFIRMED");
        java.util.Collections.reverse(affected);
        for (var value : affected) {
            var component = components.get(value.componentId());
            if (!component.journal().rollbackStarting(failure))
                return unknown("DEPLOYMENT_GRAPH_ROLLBACK_EVIDENCE_UNCONFIRMED");
            var rollback = channel(session, task, component).rollback(component.facts().unit(),
                    component.facts().release().orElseThrow(), component.facts().snapshot().orElseThrow(),
                    value.reviewedRuntime());
            if (rollback.outcome() != Outcome.SUCCEEDED || !component.journal().mark("ROLLBACK_VERIFIED"))
                return unknown("DEPLOYMENT_GRAPH_ROLLBACK_UNCONFIRMED");
        }
        var refreshed = observe(task, components, session);
        if (refreshed.isEmpty() || !restored(task, components, refreshed, session))
            return unknown("DEPLOYMENT_GRAPH_ROLLBACK_NOT_PROVEN");
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(failure));
    }

    /** Proves that this task never began publication and every original snapshot still describes the old state. / 证明本任务从未开始发布且各原快照仍描述原状态。
     * @param task original recovery context / 原恢复上下文
     * @param components indexed original components / 索引原组件
     * @param statuses exact remote observations / 精确远端观察
     * @param session borrowed original session / 借用原会话
     * @param previouslyVerified whether the root already proved nonpublication before cleanup began / 根是否已在清理前证明未发布
     * @return true only when all prepublish effects can be cleaned without replay / 仅全部发布前副作用可不重放清理时为真
     * @throws ApiException if a read-only observation is refused / 只读观察被拒绝时 */
    private boolean prepublishUnchanged(TaskContext task, Map<String, Component> components,
            Map<String, DeploymentOriginalPublicationStatus> statuses, RemoteSession session,
            boolean previouslyVerified) throws ApiException {
        for (var entry : components.entrySet()) {
            var facts = entry.getValue().facts();
            var status = statuses.get(entry.getKey());
            boolean originalPhase = java.util.Set
                    .of("CANDIDATE_MAY_EXIST", "SNAPSHOT_MAY_HAVE_RUN", "SNAPSHOT_VERIFIED").contains(facts.phase());
            boolean cleanupPhase = previouslyVerified && java.util.Set.of("SNAPSHOT_DISCARD_MAY_HAVE_RUN",
                    "SNAPSHOT_DISCARDED", "MAINTENANCE_ENDED", "CANDIDATE_CLEANUP_MAY_HAVE_RUN", "CANDIDATE_CLEANED",
                    "REMOTE_CLEANUP_VERIFIED", "SESSION_CLOSED").contains(facts.phase());
            if ((!originalPhase && !cleanupPhase) || status.buildActive() || status.maintenance())
                return false;
            if (facts.snapshot().isEmpty()) {
                if (status.snapshot())
                    return false;
                continue;
            }
            var snapshot = facts.snapshot().orElseThrow();
            if (snapshot.token().isEmpty()) {
                if (status.snapshot() || status.current().isPresent() || status.unitPresent()
                        || status.containerPresent().filter(Boolean::booleanValue).isPresent())
                    return false;
            } else {
                if ((!cleanupPhase && !status.snapshot()) || !status.current().equals(snapshot.previousDigest())
                        || !status.currentKind().equals(snapshot.previousKind()))
                    return false;
                var observed = channel(session, task, entry.getValue()).observeRestored(facts.unit());
                if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty()
                        || observed.value().orElseThrow().state() == DeploymentRemoteRuntimeObservation.StateType.ERROR
                        || snapshot.previousRunning() != (observed.value().orElseThrow()
                                .state() == DeploymentRemoteRuntimeObservation.StateType.RUNNING))
                    return false;
            }
        }
        return true;
    }

    /** Requires every component's persisted publish boundary before attributing equal content to this task. / 将相同内容归于本任务前要求各组件已持久发布边界。
     * @param phase original component phase / 原组件阶段
     * @return whether this component might have published / 此组件是否可能已发布 */
    private static boolean publishAttempted(String phase) {
        return java.util.Set.of("PUBLISH_MAY_HAVE_RUN", "PUBLISHED_VERIFIED", "ROLLBACK_MAY_HAVE_RUN",
                "ROLLBACK_VERIFIED", "MAINTENANCE_END_MAY_HAVE_RUN", "MAINTENANCE_ENDED", "RETAIN_MAY_HAVE_RUN",
                "RETAIN_VERIFIED", "CANDIDATE_CLEANUP_MAY_HAVE_RUN", "CANDIDATE_CLEANED", "REMOTE_CLEANUP_VERIFIED",
                "SESSION_CLOSED", "CLEANUP_VERIFIED").contains(phase);
    }

    /** Checks all exact current releases, their component health, and independent whole-application gate. / 核对全部精确现行发布、组件健康及独立整应用门禁。
     * @param task original task / 原任务
     * @param proposal full original graph / 完整原图
     * @param components retained original components / 留存原组件
     * @param statuses current remote states / 当前远端状态
     * @param session borrowed session / 借用会话
     * @return true only when the whole graph is verified / 仅整图核实时为真
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    private boolean healthy(TaskContext task, DeploymentPublicationProposal proposal, Map<String, Component> components,
            Map<String, DeploymentOriginalPublicationStatus> statuses, RemoteSession session) throws ApiException {
        for (var value : proposal.graph().components()) {
            var component = components.get(value.componentId());
            if (!componentHealthy(task, value, component, statuses.get(value.componentId()), session))
                return false;
        }
        var owner = proposal.graph().components().stream()
                .filter(component -> component.componentId().equals(proposal.graph().healthComponentId())).findFirst()
                .orElseThrow();
        return channel(session, task, components.get(owner.componentId()))
                .healthCheck(owner.unit(), proposal.graph().healthCheck()).outcome() == Outcome.SUCCEEDED;
    }

    /** Independently verifies one exact attempted release and its reviewed runtime health. / 独立核实一项精确尝试发布及其已审阅运行健康。
     * @param task original task / 原任务
     * @param value formal component / 正式组件
     * @param component original checkpoint / 原检查点
     * @param status original exact observation / 原精确观察
     * @param session recovery session / 恢复会话
     * @return whether this component is independently healthy / 本组件是否独立健康
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    private boolean componentHealthy(TaskContext task, DeploymentApplicationGraph.Component value, Component component,
            DeploymentOriginalPublicationStatus status, RemoteSession session) throws ApiException {
        var facts = component.facts();
        if (status.buildActive() || facts.release().isEmpty() || !status.current().equals(facts.release()))
            return false;
        var channel = channel(session, task, component);
        var observed = channel.observe(facts.unit(), value.reviewedRuntime());
        var expected = value.reviewedRuntime().workload().mode() == DeploymentWorkload.ExecutionMode.ON_DEMAND
                ? DeploymentRemoteRuntimeObservation.StateType.INSTALLED
                : DeploymentRemoteRuntimeObservation.StateType.RUNNING;
        if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty()
                || observed.value().orElseThrow().state() != expected
                || channel.healthCheck(facts.unit(), value.reviewedRuntime().healthCheck())
                        .outcome() != Outcome.SUCCEEDED)
            return false;
        return true;
    }

    /** Requires every old release and its prior runtime state, including first-deployment absence. / 要求全部旧发布及先前运行状态，包括首次部署的确证不存在。
     * @param task original task / 原任务
     * @param components original component facts / 原组件事实
     * @param statuses complete helper observations / 完整helper观察
     * @param session borrowed session / 借用会话
     * @return whether the entire original graph was restored / 原整图是否已恢复
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    private boolean restored(TaskContext task, Map<String, Component> components,
            Map<String, DeploymentOriginalPublicationStatus> statuses, RemoteSession session) throws ApiException {
        for (var entry : components.entrySet()) {
            var facts = entry.getValue().facts();
            var status = statuses.get(entry.getKey());
            if (status.buildActive() || facts.snapshot().isEmpty()
                    || status.snapshot()
                            && (publishAttempted(facts.phase()) || java.util.Set
                                    .of("STOP_MAY_HAVE_RUN", "STOP_VERIFIED", "ROLLBACK_MAY_HAVE_RUN",
                                            "ROLLBACK_VERIFIED", "SNAPSHOT_DISCARD_MAY_HAVE_RUN")
                                    .contains(facts.phase())))
                return false;
            var snapshot = facts.snapshot().orElseThrow();
            if (snapshot.token().isEmpty()) {
                if (status.current().isPresent() || status.unitPresent()
                        || status.containerPresent().filter(Boolean::booleanValue).isPresent())
                    return false;
            } else {
                if (!status.current().equals(snapshot.previousDigest())
                        || !status.currentKind().equals(snapshot.previousKind()))
                    return false;
                var observed = channel(session, task, entry.getValue()).observeRestored(facts.unit());
                if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty()
                        || observed.value().orElseThrow().state() == DeploymentRemoteRuntimeObservation.StateType.ERROR
                        || snapshot.previousRunning() != (observed.value().orElseThrow()
                                .state() == DeploymentRemoteRuntimeObservation.StateType.RUNNING))
                    return false;
            }
        }
        return true;
    }

    /** Revalidates retained known business facts before any additional cleanup write. / 任何后续清理写入前重新校验已留存已知业务事实。
     * @param task original task / 原任务
     * @param record original execution / 原执行
     * @param root original graph result and phase / 原整图结果及阶段
     * @param proposal complete proposal / 完整提案
     * @param components original components / 原组件
     * @param statuses remote observations / 远端观察
     * @param session borrowed session / 借用会话
     * @param known original known result / 原已知结果
     * @return whether the exact business state still matches / 精确业务状态是否仍匹配
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    private boolean businessMatches(TaskContext task, DeploymentExecutionRecord record,
            DeploymentGraphPublicationJournal root, Optional<DeploymentPublicationProposal> proposal,
            Map<String, Component> components, Map<String, DeploymentOriginalPublicationStatus> statuses,
            RemoteSession session, OperationResult<DeploymentPublicationProposal> known) throws ApiException {
        if (known.outcome() == Outcome.SUCCEEDED)
            return proposal.isPresent() && healthy(task, proposal.orElseThrow(), components, statuses, session);
        if (known.outcome() == Outcome.FAILED
                && root.facts().path("failureEvent").asText().equals("DEPLOYMENT_GRAPH_PUBLISH_NOT_STARTED"))
            return prepublishUnchanged(task, components, statuses, session, true);
        if (known.outcome() == Outcome.FAILED && proposal.isPresent())
            return restored(task, components, statuses, session);
        return known.outcome() == Outcome.FAILED || known.outcome() == Outcome.CANCELLED;
    }

    /** Runs only still-needed original cleanup and never resends an unconfirmed previous dispatch. / 仅运行仍需的原清理，绝不重发此前未确认发送。
     * @param task original task / 原任务
     * @param root graph occupation / 图占用
     * @param components original indexed components / 原索引组件
     * @param statuses complete initial observations / 完整初始观察
     * @param session borrowed session / 借用会话
     * @param successful whether releases must be retained / 是否须留存发布
     * @return true only after all remote cleanup is confirmed / 仅全部远端清理确认后为真
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    private boolean finishRemote(TaskContext task, DeploymentGraphPublicationJournal root,
            Map<String, Component> components, Map<String, DeploymentOriginalPublicationStatus> statuses,
            RemoteSession session, boolean successful) throws ApiException {
        for (var entry : components.entrySet()) {
            var component = entry.getValue();
            var journal = component.journal();
            var facts = component.facts();
            var status = statuses.get(entry.getKey());
            var channel = channel(session, task, component);
            String phase = journal.facts().path("phase").asText();
            if (cleanupOrder(phase) >= cleanupOrder("REMOTE_CLEANUP_VERIFIED"))
                continue;
            if (!successful && status.snapshot()) {
                if (publishAttempted(phase) || phase.equals("SNAPSHOT_DISCARD_MAY_HAVE_RUN")
                        || !DeploymentPublicationCleanupAttempt.run(journal, "SNAPSHOT_DISCARD_MAY_HAVE_RUN",
                                () -> facts.operation().isEmpty()
                                        ? channel.discardSnapshot(facts.unit())
                                        : channel.discardSnapshot(facts.unit(), facts.operation().orElseThrow())))
                    return false;
                status = reread(channel, facts);
                if (status == null || status.snapshot() || !journal.mark("SNAPSHOT_DISCARDED"))
                    return false;
                phase = journal.facts().path("phase").asText();
            }
            if (status.maintenance()) {
                if (cleanupOrder(phase) >= cleanupOrder("MAINTENANCE_END_MAY_HAVE_RUN") || facts.release().isEmpty()
                        || !DeploymentPublicationCleanupAttempt.run(journal, "MAINTENANCE_END_MAY_HAVE_RUN",
                                () -> channel.endMaintenance(facts.unit(), facts.release().orElseThrow())))
                    return false;
                status = reread(channel, facts);
                if (status == null || status.maintenance())
                    return false;
            }
            if (cleanupOrder(phase) <= cleanupOrder("MAINTENANCE_END_MAY_HAVE_RUN")
                    && !journal.mark("MAINTENANCE_ENDED"))
                return false;
            phase = journal.facts().path("phase").asText();
            if (successful && status.retainedCount() > 3) {
                if (cleanupOrder(phase) >= cleanupOrder("RETAIN_MAY_HAVE_RUN") || !DeploymentPublicationCleanupAttempt
                        .run(journal, "RETAIN_MAY_HAVE_RUN", () -> channel.retain(facts.unit())))
                    return false;
                status = reread(channel, facts);
                if (status == null || status.retainedCount() > 3)
                    return false;
            }
            if (successful && cleanupOrder(phase) <= cleanupOrder("RETAIN_MAY_HAVE_RUN")
                    && !journal.mark("RETAIN_VERIFIED"))
                return false;
            phase = journal.facts().path("phase").asText();
            if (status.candidate()) {
                if (cleanupOrder(phase) >= cleanupOrder("CANDIDATE_CLEANUP_MAY_HAVE_RUN")
                        || !DeploymentPublicationCleanupAttempt.run(journal, "CANDIDATE_CLEANUP_MAY_HAVE_RUN",
                                () -> channel.cleanupCandidate(facts.unit(), facts.candidate())))
                    return false;
                status = reread(channel, facts);
                if (status == null || status.candidate())
                    return false;
            }
            if (status.maintenance() || status.buildActive()
                    || cleanupOrder(phase) <= cleanupOrder("CANDIDATE_CLEANUP_MAY_HAVE_RUN")
                            && !journal.mark("CANDIDATE_CLEANED")
                    || !journal.remoteClean())
                return false;
        }
        return root.mark("REMOTE_CLEANUP_VERIFIED");
    }

    /** Orders confirmed cleanup steps without replacing an uncertain later step. / 排序已确认清理步骤且不替换较晚未确认步骤。
     * @param phase durable component phase / 持久组件阶段
     * @return cleanup position / 清理序位 */
    private static int cleanupOrder(String phase) {
        return switch (phase) {
            case "MAINTENANCE_END_MAY_HAVE_RUN" -> 1;
            case "MAINTENANCE_ENDED" -> 2;
            case "RETAIN_MAY_HAVE_RUN" -> 3;
            case "RETAIN_VERIFIED" -> 4;
            case "CANDIDATE_CLEANUP_MAY_HAVE_RUN" -> 5;
            case "CANDIDATE_CLEANED" -> 6;
            case "REMOTE_CLEANUP_VERIFIED", "SESSION_CLOSED", "CLEANUP_VERIFIED" -> 7;
            default -> 0;
        };
    }

    /** Rechecks one exact component after a newly approved cleanup command. / 新获批准清理命令后复查一项精确组件。
     * @param channel original channel / 原通道
     * @param facts original component / 原组件
     * @return complete status or null / 完整状态或空
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    private static DeploymentOriginalPublicationStatus reread(DeploymentPublicationChannel channel,
            DeploymentPublicationRecoveryFacts facts) throws ApiException {
        var read = facts.operation().isEmpty()
                ? channel.originalStatus(facts.unit(), facts.candidate(), facts.release(), facts.backend(),
                        facts.engine())
                : channel.originalStatus(facts.unit(), facts.candidate(), facts.release(), facts.backend(),
                        facts.engine(), facts.operation().orElseThrow());
        return read.outcome() == Outcome.SUCCEEDED ? read.value().orElse(null) : null;
    }

    /** Finishes local original-source and checkpoint cleanup without reopening remote state. / 不重开远端状态地完成本地原来源及检查点清理。
     * @param task original recovery task / 原恢复任务
     * @param record original execution / 原执行
     * @param root graph occupation / 图占用
     * @param components indexed component occupations / 索引组件占用
     * @param known independently proved result / 独立已证结果
     * @return known result while actual resources govern final task state / 已知结果，由实际资源决定最终任务状态
     * @throws ApiException if local cleanup is unavailable / 本地清理不可用时 */
    private OperationResult<DeploymentPublicationProposal> local(TaskContext task, DeploymentExecutionRecord record,
            DeploymentGraphPublicationJournal root, Map<String, Component> components,
            OperationResult<DeploymentPublicationProposal> known) throws ApiException {
        if (known.outcome() == Outcome.UNKNOWN || !context.linux().pendingTransfers(task).isEmpty())
            return unknown("DEPLOYMENT_GRAPH_LOCAL_CLEANUP_NOT_PROVEN");
        context.source().closeTaskResources(task);
        if (!root.released()) {
            for (var component : components.values())
                if (!component.journal().released() && !component.journal().sessionClosed())
                    return known;
            if (!root.mark("SESSION_CLOSED"))
                return known;
            for (var component : components.values())
                if (!component.journal().released() && !component.journal().release())
                    return known;
            root.release();
        }
        return known;
    }

    /** Creates the reviewed channel for this exact component on one borrowed session. / 在同一借用会话为此精确组件创建审阅通道。
     * @param session borrowed original connection / 借用原连接
     * @param task original task / 原任务
     * @param component indexed original component / 索引原组件
     * @return fixed helper channel / 固定helper通道 */
    private DeploymentPublicationChannel channel(RemoteSession session, TaskContext task, Component component) {
        return new DeploymentPublicationChannel(session, context.interaction(), component.facts().unit().server(), task,
                component.facts().plan());
    }

    /** Reports bounded uncertainty without guessing a remote or business state. / 不猜测远端或业务状态地报告有界未知。
     * @param event stable event / 稳定事件
     * @return explicit unknown / 显式未知 */
    private static OperationResult<DeploymentPublicationProposal> unknown(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                event, "Original graph evidence remains unconfirmed", Map.of(), Optional.empty())));
    }
}
