package gold.debug.windowstolinux.mod.deployment.service.core.delivery;

import java.util.*;

import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.SecretRef;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentUnitCommit;
import gold.debug.windowstolinux.mod.deployment.linux.*;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentPublication;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Executes one neutral managed graph publication with actual health and known-failure rollback. / 以真实健康和已知失败回滚执行一项中立受管图发布。 */
public final class DeploymentGraphPublisher {
    /** Original task event append, implemented by each mode's durable ledger. / 原任务事件追加，由各模式持久账本实现。 */
    @FunctionalInterface
    public interface Ledger {
        /** Persists one exact pre-effect or post-observation boundary. / 持久化一项精确副作用前或观察后边界。
         * @param type fixed event type / 固定事件类别
         * @param action exact component or action ID / 精确组件或动作标识
         * @param detail bounded nonsecret identity / 有界不含秘密身份
         * @throws Exception when durability is unconfirmed / 持久性未确认时 */
        void append(String type, String action, String detail) throws Exception;
    }

    /** Confirmed candidate plus original secret authority, not a deployment planner. / 已确认候选与原秘密权限，并非部署规划器。
     * @param candidateId original task-owned candidate / 原任务所属候选
     * @param artifactSha256 sealed output identity / 封存输出身份
     * @param sourceRevision current protected source revision / 当前受保护源码修订
     * @param toolchainBindingSha256 installed tools identity, zero digest if none / 已装工具身份，无工具时为零摘要
     * @param hasTools whether toolchain binding is active / 工具链绑定是否有效
     * @param credentials exact persisted secret references / 精确已存秘密引用 */
    public record Candidate(String candidateId, String artifactSha256, String sourceRevision,
            String toolchainBindingSha256, boolean hasTools, Map<DeploymentSecretReference, SecretRef> credentials) {
        /** Validates and copies one confirmed candidate. / 校验并复制一项已确认候选。
         * @param candidateId remote candidate / 远端候选
         * @param artifactSha256 sealed artifact / 封存制品
         * @param sourceRevision source revision / 来源修订
         * @param toolchainBindingSha256 tools identity / 工具身份
         * @param hasTools tools flag / 工具标志
         * @param credentials exact secrets / 精确秘密 */
        public Candidate {
            if (candidateId == null || !candidateId.matches("[a-z0-9][a-z0-9-]{0,62}-[0-9a-f]{16}")
                    || !digest(artifactSha256) || !digest(sourceRevision) || !digest(toolchainBindingSha256))
                throw new IllegalArgumentException("Invalid graph candidate identity");
            credentials = Map.copyOf(credentials);
        }
    }

    /** Actual platform capabilities and authenticated connection. / 真实平台能力与已认证连接。 */
    private final ModContext context;

    /** Borrowed authenticated original SSH session. / 借用的原认证 SSH 会话。 */
    private final RemoteSession session;

    /** Exact admitted target server reference. / 精确已准入目标服务器引用。 */
    private final ServerRef server;

    /** Authentic original platform task context. / 真实原平台任务上下文。 */
    private final TaskContext task;

    /** Mode-owned durable publication event sink. / 模式所属持久发布事件端口。 */
    private final Ledger ledger;

    /** Frozen policy guard checked immediately before dispatch. / 发送紧前检查的冻结策略校验器。 */
    private final DeploymentSendGuard beforeSend;

    /** Original real channel per exact unit. / 每个精确单元的原真实通道。 */
    private final Map<String, DeploymentPublicationChannel> channels = new HashMap<>();

    /** Verified original rollback snapshot per component. / 每组件经核实原回滚快照。 */
    private final Map<String, DeploymentRemoteReleaseSnapshot> snapshots = new HashMap<>();

    /** Remote digest evidence for each exact saved secret revision. / 每份精确已存秘密修订的远端摘要依据。 */
    private final Map<String, Map<DeploymentSecretReference, String>> secrets = new HashMap<>();

    /** Components whose old or new runtime may have changed. / 旧运行或新运行可能已更改的组件。 */
    private final Set<String> affected = new HashSet<>();

    /** Binds a neutral graph to the original task and durable mode ledger. / 将中立图绑定至原任务和模式持久账本。
     * @param context active Mod / 活动 Mod
     * @param session original authenticated SSH / 原认证 SSH
     * @param server exact target / 精确目标
     * @param task original task / 原任务
     * @param ledger mode-owned original durable events / 模式持有的原持久事件 */
    public DeploymentGraphPublisher(ModContext context, RemoteSession session, ServerRef server, TaskContext task,
            Ledger ledger) {
        this(context, session, server, task, ledger, DeploymentSendGuard.NONE);
    }

    /** Binds an original dispatch guard to every managed publication operation. / 为每项受管发布动作绑定原发送校验。
     * @param context active Mod / 活动 Mod
     * @param session original authenticated SSH / 原认证 SSH
     * @param server exact target / 精确目标
     * @param task original task / 原任务
     * @param ledger durable mode events / 模式持久事件
     * @param beforeSend caller-owned frozen binding / 调用方持有的冻结绑定 */
    public DeploymentGraphPublisher(ModContext context, RemoteSession session, ServerRef server, TaskContext task,
            Ledger ledger, DeploymentSendGuard beforeSend) {
        this.context = Objects.requireNonNull(context);
        this.session = Objects.requireNonNull(session);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        this.ledger = Objects.requireNonNull(ledger);
        this.beforeSend = Objects.requireNonNull(beforeSend);
    }

    /** Stages, snapshots, publishes and verifies one whole graph without committing business rows. / 暂存、快照、发布和核实整图而不提交业务行。
     * @param proposal exact complete business proposal / 精确完整业务提案
     * @param candidates one confirmed candidate per component / 每组件一份已确认候选
     * @return actual remote outcome and complete proposal / 真实远端结果及完整提案 */
    public OperationResult<DeploymentPublication> publish(DeploymentPublication proposal,
            Map<String, Candidate> candidates) {
        try {
            var graph = proposal.graph();
            var commits = byComponent(proposal, candidates);
            var order = topological(graph);
            for (String id : order) {
                var unit = component(graph, id).unit();
                channels.put(id, new DeploymentPublicationChannel(session, context.interaction(), server, task,
                        candidates.get(id).artifactSha256(), beforeSend));
                if (!unit.server().equals(server))
                    throw new IllegalArgumentException("Graph target changed");
            }
            ledger.append("GRAPH_PROPOSAL", graph.applicationId(), identity(proposal, candidates));
            for (String id : order) {
                var staged = stage(id, commits.get(id), candidates.get(id));
                if (staged.outcome() != Outcome.SUCCEEDED)
                    return convert(staged);
            }
            for (String id : reversed(order)) {
                var unit = component(graph, id).unit();
                ledger.append("SNAPSHOT_EXECUTING", id, unit.ownershipManifestSha256());
                var saved = channels.get(id).snapshot(unit, component(graph, id).reviewedRuntime());
                if (saved.outcome() != Outcome.SUCCEEDED || saved.value().isEmpty())
                    return unknown("DEPLOYMENT_GRAPH_SNAPSHOT_UNCONFIRMED");
                snapshots.put(id, saved.value().orElseThrow());
                ledger.append("SNAPSHOT_RESULT", id, snapshotDetail(saved.value().orElseThrow()));
            }
            for (String id : reversed(order)) {
                var snapshot = snapshots.get(id);
                if (snapshot.token().isEmpty() || !snapshot.previousRunning())
                    continue;
                if (cancelled())
                    return unknown("DEPLOYMENT_GRAPH_CANCELLED_AFTER_SNAPSHOT");
                affected.add(id);
                ledger.append("STOP_EXECUTING", id, snapshotDetail(snapshot));
                var stopped = channels.get(id).stopPrevious(component(graph, id).unit(), snapshot);
                if (stopped.outcome() == Outcome.FAILED)
                    return rollback(proposal, candidates, order, stopped.problem().orElseThrow());
                if (stopped.outcome() != Outcome.SUCCEEDED)
                    return unknown("DEPLOYMENT_GRAPH_STOP_UNCONFIRMED");
                ledger.append("STOP_RESULT", id, "STOPPED");
            }
            for (String id : order) {
                if (cancelled())
                    return unknown("DEPLOYMENT_GRAPH_CANCELLED_BEFORE_PUBLISH");
                var component = component(graph, id);
                var commit = commits.get(id);
                var candidate = candidates.get(id);
                affected.add(id);
                ledger.append("PUBLISH_EXECUTING", id, commit.release().sha256());
                var published = channels.get(id).publish(commit.unit(), null, component.reviewedRuntime(),
                        component.resources(), commit.configuration().sha256(), secrets.get(id),
                        candidate.candidateId(), commit.release().sha256(), candidate.toolchainBindingSha256(),
                        candidate.hasTools());
                if (published.outcome() == Outcome.FAILED)
                    return rollback(proposal, candidates, order, published.problem().orElseThrow());
                if (published.outcome() != Outcome.SUCCEEDED)
                    return unknown("DEPLOYMENT_GRAPH_PUBLISH_UNCONFIRMED");
                ledger.append("PUBLISH_RESULT", id, commit.release().sha256());
                var healthy = health(component);
                if (healthy.outcome() == Outcome.FAILED)
                    return rollback(proposal, candidates, order, healthy.problem().orElseThrow());
                if (healthy.outcome() != Outcome.SUCCEEDED)
                    return unknown("DEPLOYMENT_GRAPH_HEALTH_UNCONFIRMED");
            }
            for (String id : order) {
                var healthy = health(component(graph, id));
                if (healthy.outcome() == Outcome.FAILED)
                    return rollback(proposal, candidates, order, healthy.problem().orElseThrow());
                if (healthy.outcome() != Outcome.SUCCEEDED)
                    return unknown("DEPLOYMENT_GRAPH_RECHECK_UNCONFIRMED");
            }
            var owner = component(graph, graph.healthComponentId());
            var whole = channels.get(graph.healthComponentId()).healthCheck(owner.unit(), graph.healthCheck());
            if (whole.outcome() == Outcome.FAILED)
                return rollback(proposal, candidates, order, whole.problem().orElseThrow());
            if (whole.outcome() != Outcome.SUCCEEDED)
                return unknown("DEPLOYMENT_GRAPH_WHOLE_HEALTH_UNCONFIRMED");
            ledger.append("GRAPH_HEALTH_RESULT", graph.applicationId(), "HEALTHY");
            return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(proposal), Optional.empty());
        } catch (Exception uncertain) {
            return unknown("DEPLOYMENT_GRAPH_EFFECT_UNCONFIRMED");
        }
    }

    /** Releases only a verified success or rollback's original remote candidates and markers. / 仅释放已核实成功或回滚的原远端候选与标记。
     * @param proposal complete original proposal / 完整原提案
     * @param candidates exact original candidates / 精确原候选
     * @param successful whether new releases were fully verified / 新发布是否完整核实
     * @return confirmed cleanup or retained uncertainty / 已确认收尾或留存未知 */
    public OperationResult<Void> cleanup(DeploymentPublication proposal, Map<String, Candidate> candidates,
            boolean successful) {
        try {
            var order = topological(proposal.graph());
            var observed = observeOriginal(proposal, candidates);
            if (observed.outcome() != Outcome.SUCCEEDED)
                return unknownVoid("DEPLOYMENT_GRAPH_CLEANUP_STATUS_UNCONFIRMED");
            for (String id : order) {
                var component = component(proposal.graph(), id);
                var channel = channels.computeIfAbsent(id, ignored -> new DeploymentPublicationChannel(session,
                        context.interaction(), server, task, candidates.get(id).artifactSha256(), beforeSend));
                var status = observed.value().orElseThrow().get(id);
                var commit = proposal.units().stream().filter(value -> value.unit().equals(component.unit()))
                        .findFirst().orElseThrow();
                if (status.maintenance()) {
                    ledger.append("MAINTENANCE_END_EXECUTING", id, commit.release().sha256());
                    if (channel.endMaintenance(component.unit(), commit.release().sha256())
                            .outcome() != Outcome.SUCCEEDED)
                        return unknownVoid("DEPLOYMENT_GRAPH_MAINTENANCE_UNCONFIRMED");
                    ledger.append("MAINTENANCE_END_RESULT", id, commit.release().sha256());
                }
                if (status.snapshot()) {
                    ledger.append("SNAPSHOT_DISCARD_EXECUTING", id, "original");
                    if (channel.discardSnapshot(component.unit()).outcome() != Outcome.SUCCEEDED)
                        return unknownVoid("DEPLOYMENT_GRAPH_SNAPSHOT_DISCARD_UNCONFIRMED");
                    ledger.append("SNAPSHOT_DISCARD_RESULT", id, "discarded");
                }
                if (successful && status.retainedCount() > 3) {
                    ledger.append("RETAIN_EXECUTING", id, commit.release().sha256());
                    if (channel.retain(component.unit()).outcome() != Outcome.SUCCEEDED)
                        return unknownVoid("DEPLOYMENT_GRAPH_RETAIN_UNCONFIRMED");
                    ledger.append("RETAIN_RESULT", id, "retained");
                }
                if (status.candidate()) {
                    ledger.append("CANDIDATE_CLEANUP_EXECUTING", id, candidates.get(id).candidateId());
                    if (channel.cleanupCandidate(component.unit(), candidates.get(id).candidateId())
                            .outcome() != Outcome.SUCCEEDED)
                        return unknownVoid("DEPLOYMENT_GRAPH_CANDIDATE_CLEANUP_UNCONFIRMED");
                    ledger.append("CANDIDATE_CLEANUP_RESULT", id, candidates.get(id).candidateId());
                }
            }
            ledger.append("GRAPH_REMOTE_CLEANUP_RESULT", proposal.graph().applicationId(), "CLEAN");
            return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
        } catch (Exception unavailable) {
            return unknownVoid("DEPLOYMENT_GRAPH_CLEANUP_UNCONFIRMED");
        }
    }

    /** Reads exact original remote publication state without replaying a build, stop or publish. / 不重放构建、停止或发布，只读原远端精确发布状态。
     * @param proposal durably retained original graph / 持久留存原图
     * @param candidates original candidate identities / 原候选身份
     * @return all component statuses or retained uncertainty / 全部组件状态或留存未知 */
    public OperationResult<Map<String, DeploymentOriginalPublicationStatus>> observeOriginal(
            DeploymentPublication proposal, Map<String, Candidate> candidates) {
        try {
            var values = new LinkedHashMap<String, DeploymentOriginalPublicationStatus>();
            for (var component : proposal.graph().components()) {
                String id = component.componentId();
                var candidate = candidates.get(id);
                var commit = proposal.units().stream().filter(value -> value.unit().equals(component.unit()))
                        .findFirst().orElseThrow();
                var channel = channels.computeIfAbsent(id, ignored -> new DeploymentPublicationChannel(session,
                        context.interaction(), server, task, candidate.artifactSha256(), beforeSend));
                String backend = component.reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container
                        ? "container"
                        : "native";
                String engine = component.reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container container
                        ? container.engine().name().toLowerCase(Locale.ROOT)
                        : "none";
                var status = channel.originalStatus(component.unit(), candidate.candidateId(),
                        Optional.of(commit.release().sha256()), backend, engine);
                if (status.outcome() != Outcome.SUCCEEDED || status.value().isEmpty())
                    return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                            Optional.of(problem(ProblemCode.UNKNOWN, "DEPLOYMENT_GRAPH_STATUS_UNCONFIRMED")));
                values.put(id, status.value().orElseThrow());
            }
            return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(Map.copyOf(values)), Optional.empty());
        } catch (Exception unavailable) {
            return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                    Optional.of(problem(ProblemCode.UNKNOWN, "DEPLOYMENT_GRAPH_STATUS_UNAVAILABLE")));
        }
    }

    /** Rechecks all current releases and the independent whole-application health gate. / 复核全部当前发布和独立整应用健康门。
     * @param proposal exact persisted proposal / 精确持久提案
     * @param candidates original candidate identities / 原候选身份
     * @return known healthy or retained uncertainty / 已知健康或留存未知 */
    public OperationResult<Void> verifyPublished(DeploymentPublication proposal, Map<String, Candidate> candidates) {
        try {
            var statuses = observeOriginal(proposal, candidates);
            if (statuses.outcome() != Outcome.SUCCEEDED)
                return unknownVoid("DEPLOYMENT_GRAPH_STATUS_UNCONFIRMED");
            for (var component : proposal.graph().components()) {
                String id = component.componentId();
                var status = statuses.value().orElseThrow().get(id);
                var commit = proposal.units().stream().filter(value -> value.unit().equals(component.unit()))
                        .findFirst().orElseThrow();
                if (!status.current().equals(Optional.of(commit.release().sha256())) || status.buildActive())
                    return unknownVoid("DEPLOYMENT_GRAPH_CURRENT_CHANGED");
                var checked = health(component);
                if (checked.outcome() != Outcome.SUCCEEDED)
                    return unknownVoid("DEPLOYMENT_GRAPH_HEALTH_UNCONFIRMED");
            }
            var owner = component(proposal.graph(), proposal.graph().healthComponentId());
            var whole = channels.get(owner.componentId()).healthCheck(owner.unit(), proposal.graph().healthCheck());
            return whole.outcome() == Outcome.SUCCEEDED
                    ? new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty())
                    : unknownVoid("DEPLOYMENT_GRAPH_WHOLE_HEALTH_UNCONFIRMED");
        } catch (Exception unavailable) {
            return unknownVoid("DEPLOYMENT_GRAPH_HEALTH_UNAVAILABLE");
        }
    }

    /** Rechecks each old release's actual running state after an original rollback. / 原回滚后复核每个旧发布的实际运行状态。
     * @param proposal retained original graph / 留存原图
     * @param previousRunning exact original snapshot state per existing unit / 每个既有单元的精确原快照状态
     * @return success only if all observed states match / 仅全部观察状态匹配时成功 */
    public OperationResult<Void> verifyRestoredState(DeploymentPublication proposal,
            Map<String, Boolean> previousRunning) {
        try {
            for (var component : proposal.graph().components()) {
                Boolean expected = previousRunning.get(component.componentId());
                if (expected == null)
                    continue;
                var channel = channels.get(component.componentId());
                if (channel == null)
                    return unknownVoid("DEPLOYMENT_GRAPH_RESTORED_CHANNEL_MISSING");
                var observed = channel.observeRestored(component.unit());
                if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty()
                        || observed.value().orElseThrow()
                                .state() != (expected
                                        ? DeploymentRemoteRuntimeObservation.StateType.RUNNING
                                        : DeploymentRemoteRuntimeObservation.StateType.STOPPED))
                    return unknownVoid("DEPLOYMENT_GRAPH_OLD_RUNTIME_UNCONFIRMED");
            }
            return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
        } catch (Exception unavailable) {
            return unknownVoid("DEPLOYMENT_GRAPH_OLD_RUNTIME_UNAVAILABLE");
        }
    }

    /** Stages exact immutable config and saved secret revisions. / 暂存精确不可变配置与已存秘密修订。
     * @param id component / 组件
     * @param commit complete publication row / 完整发布行
     * @param candidate confirmed source / 已确认来源
     * @return known stage outcome / 已知暂存结果
     * @throws Exception on unavailable evidence / 依据不可用时 */
    private OperationResult<Void> stage(String id, DeploymentUnitCommit commit, Candidate candidate) throws Exception {
        var channel = channels.get(id);
        for (String format : List.of("systemd", "container")) {
            byte[] bytes = DeploymentPublicationArguments.configuration(commit.configuration(), format);
            try {
                ledger.append("STAGE_EXECUTING", id, format + ":" + commit.configuration().sha256());
                var staged = channel.stageConfiguration(commit.unit(), commit.configuration().sha256(), format, bytes);
                if (staged.outcome() != Outcome.SUCCEEDED)
                    return unknownVoid("DEPLOYMENT_GRAPH_CONFIG_STAGE_UNCONFIRMED");
                ledger.append("STAGE_RESULT", id, format + ":" + commit.configuration().sha256());
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
        var digests = new HashMap<DeploymentSecretReference, String>();
        for (var revision : commit.secrets()) {
            SecretRef ref = candidate.credentials().get(revision);
            if (ref == null)
                throw new IllegalArgumentException("Missing exact secret authority");
            ledger.append("SECRET_STAGE_EXECUTING", id, revision.identifier() + ":" + revision.revision());
            var staged = context.secrets().use(ref, ref.purpose(), task, plaintext -> channel.stageSecret(commit.unit(),
                    revision.identifier(), revision.revision(), plaintext));
            if (staged.outcome() != Outcome.SUCCEEDED)
                return unknownVoid("DEPLOYMENT_GRAPH_SECRET_STAGE_UNCONFIRMED");
            digests.put(revision, staged.value().orElseThrow());
            ledger.append("SECRET_STAGE_RESULT", id, revision.identifier() + ":" + revision.revision());
        }
        secrets.put(id, Map.copyOf(digests));
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Checks actual component health and runtime state. / 核查组件真实健康和运行状态。
     * @param component reviewed component / 已审组件
     * @return classified health / 分类健康
     * @throws Exception on unavailable channel / 通道不可用时 */
    private OperationResult<Void> health(DeploymentApplicationGraph.Component component) throws Exception {
        var channel = channels.get(component.componentId());
        var check = channel.healthCheck(component.unit(), component.reviewedRuntime().healthCheck());
        if (check.outcome() != Outcome.SUCCEEDED)
            return check;
        var current = channel.observe(component.unit(), component.reviewedRuntime());
        if (current.outcome() != Outcome.SUCCEEDED || current.value().isEmpty())
            return unknownVoid("DEPLOYMENT_GRAPH_RUNTIME_UNCONFIRMED");
        var expected = component.reviewedRuntime().workload().mode() == DeploymentWorkload.ExecutionMode.ON_DEMAND
                ? DeploymentRemoteRuntimeObservation.StateType.INSTALLED
                : DeploymentRemoteRuntimeObservation.StateType.RUNNING;
        if (current.value().orElseThrow().state() != expected)
            return failure("DEPLOYMENT_GRAPH_RUNTIME_UNHEALTHY");
        ledger.append("HEALTH_RESULT", component.componentId(), expected.name());
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Restores all possibly touched units only after a confirmed failure. / 仅在已确认失败后恢复全部可能触及的单元。
     * @param proposal complete graph / 完整图
     * @param candidates exact candidates / 精确候选
     * @param order topological order / 拓扑顺序
     * @param reason original known failure / 原已知失败
     * @return failed only after full verified rollback / 仅完整核实回滚后为失败
     * @throws Exception on unavailable evidence / 依据不可用时 */
    private OperationResult<DeploymentPublication> rollback(DeploymentPublication proposal,
            Map<String, Candidate> candidates, List<String> order, Problem reason) throws Exception {
        for (String id : reversed(order)) {
            if (!affected.contains(id))
                continue;
            var component = component(proposal.graph(), id);
            var commit = proposal.units().stream().filter(value -> value.unit().equals(component.unit())).findFirst()
                    .orElseThrow();
            ledger.append("ROLLBACK_EXECUTING", id, commit.release().sha256());
            var restored = channels.get(id).rollback(component.unit(), commit.release().sha256(), snapshots.get(id),
                    component.reviewedRuntime());
            if (restored.outcome() != Outcome.SUCCEEDED)
                return unknown("DEPLOYMENT_GRAPH_ROLLBACK_UNCONFIRMED");
            var snapshot = snapshots.get(id);
            if (snapshot.token().isPresent()) {
                var observation = channels.get(id).observeRestored(component.unit());
                if (observation.outcome() != Outcome.SUCCEEDED || observation.value().isEmpty()
                        || observation.value().orElseThrow()
                                .state() == DeploymentRemoteRuntimeObservation.StateType.ERROR
                        || snapshot.previousRunning() != (observation.value().orElseThrow()
                                .state() == DeploymentRemoteRuntimeObservation.StateType.RUNNING))
                    return unknown("DEPLOYMENT_GRAPH_RESTORED_STATE_UNCONFIRMED");
            }
            ledger.append("ROLLBACK_RESULT", id, snapshotDetail(snapshot));
        }
        ledger.append("GRAPH_ROLLBACK_RESULT", proposal.graph().applicationId(), "RESTORED");
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(reason));
    }

    /** Pairs exact graph components with complete commit and candidate sets. / 将精确图组件与完整提交及候选集合配对。
     * @param proposal complete graph / 完整图
     * @param candidates exact candidates / 精确候选
     * @return component-to-commit map / 组件至提交映射 */
    private static Map<String, DeploymentUnitCommit> byComponent(DeploymentPublication proposal,
            Map<String, Candidate> candidates) {
        var commits = new HashMap<String, DeploymentUnitCommit>();
        for (var component : proposal.graph().components()) {
            var commit = proposal.units().stream().filter(value -> value.unit().equals(component.unit())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Missing graph commit"));
            if (!component.unit().equals(commit.unit()) || !candidates.containsKey(component.componentId())
                    || !candidates.get(component.componentId()).credentials().keySet()
                            .equals(Set.copyOf(commit.secrets())))
                throw new IllegalArgumentException("Graph publication inputs differ");
            commits.put(component.componentId(), commit);
        }
        if (!commits.keySet().equals(candidates.keySet()))
            throw new IllegalArgumentException("Extra graph candidate");
        return Map.copyOf(commits);
    }

    /** Returns exact acyclic topological order. / 返回精确无环拓扑顺序。
     * @param graph checked graph / 已校验图
     * @return publish order / 发布顺序 */
    private static List<String> topological(DeploymentApplicationGraph graph) {
        var order = new ArrayList<String>();
        var known = new HashSet<String>();
        while (order.size() < graph.components().size()) {
            int before = order.size();
            for (var component : graph.components())
                if (!known.contains(component.componentId()) && known.containsAll(component.dependencies())) {
                    known.add(component.componentId());
                    order.add(component.componentId());
                }
            if (order.size() == before)
                throw new IllegalArgumentException("Graph dependency cycle");
        }
        return List.copyOf(order);
    }

    /** Finds one declared component. / 查找一项已声明组件。
     * @param graph complete graph / 完整图
     * @param id component ID / 组件标识
     * @return exact component / 精确组件 */
    private static DeploymentApplicationGraph.Component component(DeploymentApplicationGraph graph, String id) {
        return graph.components().stream().filter(value -> value.componentId().equals(id)).findFirst().orElseThrow();
    }

    /** Reverses a stable topological order. / 反转稳定拓扑顺序。
     * @param order original order / 原顺序
     * @return immutable reverse / 不可变逆序 */
    private static List<String> reversed(List<String> order) {
        var reversed = new ArrayList<>(order);
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /** Persists bounded snapshot facts for original recovery. / 为原恢复持久保存有界快照事实。
     * @param snapshot actual helper snapshot / 实际 helper 快照
     * @return stable scalar encoding / 稳定标量编码 */
    private static String snapshotDetail(DeploymentRemoteReleaseSnapshot snapshot) {
        return snapshot.token().orElse("none") + ":" + (snapshot.previousRunning() ? "1" : "0") + ":"
                + snapshot.previousDigest().orElse("none") + ":" + snapshot.previousKind();
    }

    /** Binds current nonsecret publication identities. / 绑定当前不含秘密发布身份。
     * @param proposal complete proposal / 完整提案
     * @param candidates candidates / 候选
     * @return bounded digest / 有界摘要 */
    private static String identity(DeploymentPublication proposal, Map<String, Candidate> candidates) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(proposal.graph().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(proposal.units().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(new TreeMap<>(candidates).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** Checks original cooperative cancellation. / 检查原协作取消。
     * @return request state / 请求状态 */
    private boolean cancelled() {
        return task.cancellation().requested() || Thread.currentThread().isInterrupted();
    }

    /** Checks a lowercase SHA-256 scalar. / 检查小写 SHA-256 标量。
     * @param value candidate scalar / 候选标量
     * @return whether valid / 是否有效 */
    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /** Preserves classified stage result without fabricating a proposal. / 保留分类暂存结果而不虚构提案。
     * @param result original result / 原结果
     * @return no-value result / 无值结果 */
    private static OperationResult<DeploymentPublication> convert(OperationResult<?> result) {
        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
    }

    /** Reports one known failure. / 报告一项已知失败。
     * @param event stable event / 稳定事件
     * @return failed result / 失败结果 */
    private static OperationResult<Void> failure(String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                Optional.of(problem(ProblemCode.INCOMPATIBLE, event)));
    }

    /** Reports a void effect still unconfirmed. / 报告仍未确认的无值副作用。
     * @param event stable event / 稳定事件
     * @return unknown result / 未知结果 */
    private static OperationResult<Void> unknownVoid(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Reports unknown graph effects without automatic replay. / 报告未知图副作用且不自动重放。
     * @param event stable event / 稳定事件
     * @return unknown result / 未知结果 */
    private static OperationResult<DeploymentPublication> unknown(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Builds one nonsecret public problem. / 构造一项不含秘密公开问题。
     * @param code category / 分类
     * @param event stable event / 稳定事件
     * @return problem / 问题 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Managed graph needs original-task reconciliation", Map.of(), Optional.empty());
    }
}
