package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.query.PageSlice;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSchema;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentPublishChannel;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentRemoteRuntimeObservation;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentApplicationReader.ApplicationFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentApplicationReader.Observation;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentApplicationGraph;

/** Manages only published deployment-owned applications through verified helper observations. / 仅通过已验证helper观测管理部署所属的已发布应用。 */
public final class DeploymentApplicationRunner {
    /** Current provider capabilities used within the original task. / 在原任务内使用的当前提供方能力。 */
    private final ModContext context;

    /** Complete persisted graph and archive reader. / 完整持久图及归档读取器。 */
    private final DeploymentApplicationReader reader;

    /** Durable attempts and acknowledgements for the original action. / 原动作的持久尝试及确认。 */
    private final DeploymentManagementActionLog journal;

    /** Binds the active deployment generation. / 绑定活动部署代次。
     * @param context original deployment Mod context / 原部署Mod上下文 */
    public DeploymentApplicationRunner(ModContext context) {
        this.context = java.util.Objects.requireNonNull(context);
        this.reader = new DeploymentApplicationReader(context.db());
        this.journal = new DeploymentManagementActionLog(context);
    }

    /** Lists saved facts without interpreting them as current remote truth. / 列出保存的事实，不将其解释为当前远端真相。
     * @param cursor continuation / 续页
     * @param limit page size / 页大小
     * @param task original task / 原任务
     * @return saved summaries / 已保存摘要
     * @throws ApiException if evidence is unavailable / 依据不可用时 */
    public PageSlice<DeploymentApplicationSnapshot> list(Optional<String> cursor, int limit, TaskContext task)
        throws ApiException {
        return reader.list(cursor, limit, task);
    }

    /** Inspects every current unit and the original application health gate. / 检查所有当前单元及原整应用健康门槛。
     * @param application exact application / 精确应用
     * @param task original task / 原任务
     * @return refreshed status or explicit uncertainty / 刷新状态或明确未知
     * @throws ApiException if platform access fails / 平台访问失败时 */
    public OperationResult<DeploymentApplicationSnapshot> inspect(DeploymentApplicationId application,
                                                                  TaskContext task) throws ApiException {
        ApplicationFacts data = reader.find(application, task);
        var observations = observe(data, task);
        if (observations.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(observations);
        var states = observations.value().orElseThrow().components();
        persist(data, states, task);
        var state = overall(states);
        var original = data.snapshot();
        return success(new DeploymentApplicationSnapshot(original.application(), original.displayName(),
            original.revisionSha256(), original.ownershipSha256(), state, Instant.now(), original.runtime(),
            original.bindings(), original.healthPolicySha256(),
            ApplicationAutostartState.combine(states.values().stream().map(Observation::autostart).toList())));
    }

    /** Applies one action after revisiting exact release, ownership, dependencies and health. / 重核精确发布、归属、依赖与健康后执行一次动作。
     * @param application exact application / 精确应用
     * @param revision expected complete release / 预期完整发布
     * @param action explicit action / 明确动作
     * @param task original task / 原任务
     * @return verified before and after / 已验证前后状态
     * @throws ApiException if platform access fails / 平台访问失败时 */
    public OperationResult<DeploymentLifecycleResult> lifecycle(DeploymentApplicationId application, String revision,
                                                                DeploymentLifecycleAction action, TaskContext task) throws ApiException {
        var prior = journal.find(task);
        if (prior.isPresent() && (!prior.orElseThrow().applicationId().equals(application.id())
            || !prior.orElseThrow().server().equals(application.server())
            || !prior.orElseThrow().currentRevision().equals(revision)
            || !prior.orElseThrow().action().equals("LIFECYCLE_" + action.name())))
            return lifecycleRefusal(prior, "DEPLOYMENT_LIFECYCLE_ORIGINAL_INTENT_CHANGED");
        Optional<RemoteTarget> originalTarget = Optional.empty();
        if (prior.isPresent()) {
            try {
                originalTarget = Optional.of(DeploymentManagementActionLog.target(prior.orElseThrow(), task));
            } catch (ApiException incomplete) {
                return unknown("DEPLOYMENT_LIFECYCLE_ORIGINAL_TARGET_UNCONFIRMED");
            }
        }
        var data = reader.find(application, task);
        var incompatible = lifecycleIncompatibility(data, revision);
        if (incompatible.isPresent())
            return lifecycleRefusal(prior, incompatible.orElseThrow());
        var before = observe(data, task, originalTarget);
        if (before.outcome() != TaskResult.SUCCEEDED) {
            if (prior.isPresent() && Set.of("EFFECTING", "UNKNOWN").contains(prior.orElseThrow().phase()))
                return unknown("DEPLOYMENT_LIFECYCLE_ORIGINAL_OBSERVATION_UNCONFIRMED");
            return withoutValue(before);
        }
        var states = before.value().orElseThrow().components();
        var initial = overall(states);
        if (prior.isPresent() && !prior.orElseThrow().phase().equals("REVIEWED")) {
            var original = prior.orElseThrow();
            return reconcileLifecycle(data, action, initial, states, original, task);
        }
        if (initial == ApplicationRunState.UNKNOWN)
            return failure(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_APPLICATION_STATE_UNVERIFIED");
        if (action == DeploymentLifecycleAction.START && initial != ApplicationRunState.STOPPED)
            return failure(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_APPLICATION_NOT_STOPPED");
        var graph = data.graph();
        var ordered = ordered(graph);
        if (task.cancelToken().requested())
            return cancelled();
        ObjectNode facts = DeploymentManagementActionLog.facts().put("before", initial.name());
        DeploymentManagementActionLog.target(facts, before.value().orElseThrow().target());
        var parts = facts.putObject("components");
        for (var component : graph.components())
            parts.putObject(component.componentId()).put("before", states.get(component.unit().id()).state().name())
                .put("beforeAutostart", states.get(component.unit().id()).autostart().name())
                .put("attempted", false).put("verified", false);
        var operation = journal.begin(application.id(), application.server(), "LIFECYCLE_" + action.name(), revision,
            Optional.empty(), facts, task);
        if (!operation.phase().equals("REVIEWED"))
            return reconcileLifecycle(data, action, initial, states, operation, task);
        markUnknown(data, task);
        var dispatchTarget = DeploymentManagementActionLog.target(operation, task);
        try (var session = context.linux().connect(dispatchTarget, task)) {
            if (!dispatchTarget.fingerprint().equals(Optional.of(session.facts().fingerprint()))) {
                journal.advance(operation, "UNKNOWN", DeploymentManagementActionLog.copy(operation)
                    .put("originalTargetUnconfirmed", true), task);
                return unknown("DEPLOYMENT_LIFECYCLE_ORIGINAL_TARGET_UNCONFIRMED");
            }
            var helper = new gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelper(session,
                context.interaction(), application.server(), task, revision).probe();
            if (helper.outcome() != TaskResult.SUCCEEDED)
                return withoutValue(helper);
            var channel = new DeploymentPublishChannel(session, context.interaction(), application.server(), task,
                revision);
            var identity = new DeploymentHandoffChannel(session, context.interaction(), application.server(), task,
                revision);
            if (action == DeploymentLifecycleAction.RESTART) {
                var stopped = run(channel, identity, data, ordered.reversed(), "stop", task, operation);
                if (stopped.outcome() != TaskResult.SUCCEEDED)
                    return withoutValue(stopped);
                operation = journal.find(task).orElseThrow();
                var started = run(channel, identity, data, ordered, "start", task, operation);
                if (started.outcome() != TaskResult.SUCCEEDED)
                    return withoutValue(started);
            } else {
                var sequence = action == DeploymentLifecycleAction.STOP ? ordered.reversed() : ordered;
                var effected = run(channel, identity, data, sequence, action.verb(),
                    task, operation);
                if (effected.outcome() != TaskResult.SUCCEEDED)
                    return withoutValue(effected);
            }
        }
        return finishLifecycle(data, action, initial, task);
    }

    /** Checks the current complete graph before opening any lifecycle connection. / 打开任何生命周期连接前核对当前完整图。
     * @param data exact current complete publication / 精确当前完整发布
     * @param revision fixed originally selected revision / 固定原所选修订
     * @return bounded refusal identity or absence / 有界拒绝身份或无值 */
    private static Optional<String> lifecycleIncompatibility(ApplicationFacts data, String revision) {
        if (data.graph().components().stream().anyMatch(value -> !value.runtime().workload().reviewed()
            || !value.runtime().workload().equals(value.reviewedRuntime().workload())))
            return Optional.of("DEPLOYMENT_APPLICATION_DECLARATION_UNREVIEWED");
        if (!data.snapshot().revisionSha256().equals(revision))
            return Optional.of("DEPLOYMENT_APPLICATION_REVISION_CHANGED");
        if (data.graph().components().stream().noneMatch(value -> value.runtime().workload().supportsLifecycle()))
            return Optional.of("DEPLOYMENT_APPLICATION_LIFECYCLE_NOT_APPLICABLE");
        return Optional.empty();
    }

    /** Keeps prior attempted effects unknown while rejecting genuinely new requests as known failures. / 保留既有已尝试效果的未知，同时将真实新请求拒绝为已知失败。
     * @param prior original task's authenticated durable operation or absence / 原任务认证持久操作或无值
     * @param event exact incompatibility discovered before connection / 连接前发现的精确不兼容
     * @return preserved original uncertainty or known pre-effect refusal / 保留原未知或已知效果前拒绝 */
    private static OperationResult<DeploymentLifecycleResult> lifecycleRefusal(
        Optional<DeploymentManagementActionLog.Entry> prior, String event) {
        return prior.filter(value -> !value.phase().equals("REVIEWED")).isPresent()
            ? unknown(event) : failure(ProblemCode.INCOMPATIBLE, event);
    }

    /** Concludes only a fully verified original lifecycle after all provider sessions close. / 全部提供方会话关闭后仅结束完整已核实原生命周期。
     * @param data complete original release / 完整原发布
     * @param action fixed original lifecycle action / 固定原生命周期动作
     * @param initial complete original before state / 完整原前态
     * @param task authenticated original task / 已认证原任务
     * @return verified original result or retained uncertainty / 已核实原结果或留存未知
     * @throws ApiException if original evidence cannot be confirmed / 原依据无法确认时 */
    private OperationResult<DeploymentLifecycleResult> finishLifecycle(ApplicationFacts data,
                                                                       DeploymentLifecycleAction action, ApplicationRunState initial, TaskContext task) throws ApiException {
        var originalOperation = journal.find(task).orElseThrow();
        var after = observe(data, task, Optional.of(DeploymentManagementActionLog.target(originalOperation, task)));
        if (after.outcome() != TaskResult.SUCCEEDED) {
            markUnknown(data, task);
            var current = journal.find(task).orElseThrow();
            journal.advance(current, "UNKNOWN",
                DeploymentManagementActionLog.copy(current).put("finalObservationOutcome", after.outcome().name()),
                task);
            return unknown("DEPLOYMENT_LIFECYCLE_FINAL_OBSERVATION_UNCONFIRMED");
        }
        var finalStates = after.value().orElseThrow().components();
        persist(data, finalStates, task);
        var finalState = overall(finalStates);
        var current = journal.find(task).orElseThrow();
        if (!matchesGoal(data, action, finalStates, current)) {
            journal.advance(current, "UNKNOWN",
                DeploymentManagementActionLog.copy(current).put("final", finalState.name()), task);
            return unknown("DEPLOYMENT_APPLICATION_FINAL_STATE");
        }
        journal.advance(current, "FINISHED", DeploymentManagementActionLog.copy(current).put("final", finalState.name()),
            task);
        return success(new DeploymentLifecycleResult(data.snapshot().application(), initial, finalState,
            action.autostart() ? "Verified original automatic-startup target and unchanged component runtime observations"
                : "Verified original helper ownership, runtime and application health"));
    }

    /** Observes a prior original-task action without sending it again. / 只读观察原任务既有动作，不再次发送。
     * @param data complete reviewed release / 完整已审阅发布
     * @param action original fixed action / 原固定动作
     * @param observed complete current state / 完整当前状态
     * @param states current component states / 当前组件状态
     * @param operation original durable attempts / 原持久尝试
     * @param task authenticated original task / 已认证原任务
     * @return verified original result or uncertainty / 已核实原结果或未知
     * @throws ApiException if persisted evidence cannot be confirmed / 持久依据无法确认时 */
    private OperationResult<DeploymentLifecycleResult> reconcileLifecycle(ApplicationFacts data,
                                                                          DeploymentLifecycleAction action, ApplicationRunState observed, Map<String, Observation> states,
                                                                          DeploymentManagementActionLog.Entry operation, TaskContext task) throws ApiException {
        if (!operation.phase().equals("EFFECTING") && !operation.phase().equals("UNKNOWN")
            && !operation.phase().equals("FINISHED"))
            return unknown("DEPLOYMENT_LIFECYCLE_PHASE_UNVERIFIED");
        if (observed == ApplicationRunState.UNKNOWN)
            return unknown("DEPLOYMENT_LIFECYCLE_OBSERVATION_UNKNOWN");
        if (!matchesGoal(data, action, states, operation))
            return unknown("DEPLOYMENT_LIFECYCLE_PARTIAL_STATE");
        var parts = operation.facts().path("components");
        for (var component : data.graph().components()) {
            if (!component.runtime().workload().supportsLifecycle())
                continue;
            var part = parts.path(component.componentId());
            if (!part.path("attempted").asBoolean(false)
                || action == DeploymentLifecycleAction.RESTART && !part.path("verified").asBoolean(false)
                || !part.path("verb").asText().equals(action == DeploymentLifecycleAction.RESTART ? "start" : action.verb()))
                return unknown("DEPLOYMENT_LIFECYCLE_COMPONENT_UNVERIFIED");
        }
        if (!operation.phase().equals("FINISHED")) {
            persist(data, states, task);
            journal.advance(operation, "FINISHED", DeploymentManagementActionLog.copy(operation).put("final", observed.name())
                .put("reconciledReadOnly", true), task);
        }
        return success(new DeploymentLifecycleResult(data.snapshot().application(),
            ApplicationRunState.valueOf(operation.facts().path("before").asText()), observed,
            action.autostart() ? "Original automatic-startup target state verified by read-only current identity and component observations"
                : "Original action target state verified by read-only current identity and component health"));
    }

    /** Checks every applicable target and installed job without inferring command history. / 核对每个适用目标与已安装任务，不推断命令历史。
     * @param data complete original release / 完整原发布
     * @param action original reviewed action / 原已审阅动作
     * @param states exact current component observations / 精确当前组件观察
     * @param operation original durable intent and before facts / 原持久意图与前态事实
     * @return whether all original target states are established / 是否确认所有原目标状态 */
    private static boolean matchesGoal(ApplicationFacts data, DeploymentLifecycleAction action, Map<String, Observation> states,
                                       DeploymentManagementActionLog.Entry operation) {
        for (var component : data.graph().components()) {
            var state = states.get(component.unit().id());
            if (state == null)
                return false;
            if (component.runtime().workload().mode() == gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentWorkload.ExecutionMode.ON_DEMAND) {
                if (state.state() != ApplicationRunState.INSTALLED || state.autostart() != ApplicationAutostartState.NOT_APPLICABLE)
                    return false;
            } else if (action.autostart()) {
                var expected = action == DeploymentLifecycleAction.ENABLE_AUTOSTART ? ApplicationAutostartState.ENABLED
                    : ApplicationAutostartState.DISABLED;
                if (state.autostart() != expected || !state.state().name().equals(operation.facts().path("components")
                    .path(component.componentId()).path("before").asText()))
                    return false;
            } else if (state.state() != (action == DeploymentLifecycleAction.STOP ? ApplicationRunState.STOPPED : ApplicationRunState.RUNNING))
                return false;
        }
        return true;
    }

    /** Dispatches reviewed components in one dependency-safe phase. / 在一个依赖安全阶段发送已审阅组件。
     * @param channel approved lifecycle channel / 已审批生命周期频道
     * @param identity current release observer / 当前发布观察器
     * @param data complete original release / 完整原发布
     * @param sequence dependency-ordered components / 按依赖排序的组件
     * @param verb original start or stop / 原启动或停止
     * @param task authenticated original task / 已认证原任务
     * @param entry original durable operation / 原持久操作
     * @return acknowledged phase or retained uncertainty / 已确认阶段或留存未知
     * @throws ApiException if original evidence cannot be persisted / 原依据无法持久化时 */
    private OperationResult<Void> run(DeploymentPublishChannel channel, DeploymentHandoffChannel identity,
                                      ApplicationFacts data, List<DeploymentApplicationGraph.Part> sequence, String verb, TaskContext task,
                                      DeploymentManagementActionLog.Entry entry) throws ApiException {
        for (var component : sequence) {
            if (!component.runtime().workload().supportsLifecycle())
                continue;
            if (task.cancelToken().requested()) {
                journal.advance(entry, "UNKNOWN", DeploymentManagementActionLog.copy(entry).put("interrupted", true),
                    task);
                return unknown("DEPLOYMENT_LIFECYCLE_INTERRUPTED");
            }
            for (var reviewed : data.graph().components()) {
                var binding = data.release().bindings().stream()
                    .filter(value -> value.unitId().equals(reviewed.unit().id())).findFirst().orElseThrow();
                var verified = identity.current(reviewed, binding.releaseSha256());
                if (verified.outcome() != TaskResult.SUCCEEDED) {
                    journal.advance(entry, "UNKNOWN",
                        DeploymentManagementActionLog.copy(entry).put("currentReleaseUnconfirmed", true), task);
                    return unknown("DEPLOYMENT_LIFECYCLE_CURRENT_RELEASE_CHANGED");
                }
            }
            var facts = DeploymentManagementActionLog.copy(entry);
            facts.withObject("components").withObject(component.componentId()).put("attempted", true).put("verb", verb)
                .put("verified", false);
            entry = journal.advance(entry, "EFFECTING", facts, task);
            var result = channel.lifecycle(component.unit(), component.reviewedRuntime(), verb,
                data.release().bindings().stream().filter(value -> value.unitId().equals(component.unit().id()))
                    .findFirst().orElseThrow().releaseSha256());
            if (result.outcome() != TaskResult.SUCCEEDED) {
                journal.advance(entry, "UNKNOWN",
                    DeploymentManagementActionLog.copy(entry).put("effectOutcome", result.outcome().name()), task);
                return unknown("DEPLOYMENT_LIFECYCLE_PARTIAL_OR_UNCONFIRMED");
            }
            facts = DeploymentManagementActionLog.copy(entry);
            facts.withObject("components").withObject(component.componentId()).put("verified", true);
            entry = journal.advance(entry, "EFFECTING", facts, task);
        }
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Observes all units in one authenticated session and checks whole-application health. / 在一份认证会话中观察所有单元并检查整应用健康。
     * @param data complete original release / 完整原发布
     * @param task authenticated original task / 已认证原任务
     * @return complete component observations or failure / 完整组件观察或失败
     * @throws ApiException if session or authorization fails / 会话或授权失败时 */
    private OperationResult<LiveObservation> observe(ApplicationFacts data, TaskContext task)
        throws ApiException {
        return observe(data, task, Optional.empty());
    }

    /** Uses a fixed original endpoint and wire key before authentication and every observation. / 认证及每次观察前使用固定原端点与wire密钥。
     * @param data complete original release / 完整原发布
     * @param task authentic current original task / 真实当前原任务
     * @param expected fixed original target or an initial current expectation / 固定原目标或初次当前预期
     * @return exact component observations and authenticated target / 精确组件观察与已认证目标
     * @throws ApiException if initial authority or provider access fails / 初次权限或提供方访问失败时 */
    private OperationResult<LiveObservation> observe(ApplicationFacts data, TaskContext task, Optional<RemoteTarget> expected)
        throws ApiException {
        var states = new HashMap<String, Observation>();
        var graph = data.graph();
        var target = expected.isPresent() ? expected.orElseThrow() : currentTarget(data.snapshot().application().server(), task);
        if (!target.server().equals(data.snapshot().application().server()) || !target.taskId().equals(task.id()))
            return unknown("DEPLOYMENT_LIFECYCLE_ORIGINAL_TARGET_UNCONFIRMED");
        try (var session = context.linux().connect(target, task)) {
            if (target.fingerprint().filter(value -> !value.equals(session.facts().fingerprint())).isPresent())
                return unknown("DEPLOYMENT_LIFECYCLE_ORIGINAL_TARGET_UNCONFIRMED");
            var authenticated = new RemoteTarget(target.server(), task.id(), target.host(), target.port(), target.username(),
                Optional.of(session.facts().fingerprint()));
            var helper = new gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelper(session,
                context.interaction(), data.snapshot().application().server(), task,
                data.snapshot().revisionSha256()).probe();
            if (helper.outcome() != TaskResult.SUCCEEDED)
                return withoutValue(helper);
            var channel = new DeploymentPublishChannel(session, context.interaction(),
                data.snapshot().application().server(), task, data.snapshot().revisionSha256());
            var identity = new DeploymentHandoffChannel(session, context.interaction(),
                data.snapshot().application().server(), task, data.snapshot().revisionSha256());
            for (var component : graph.components()) {
                var binding = data.release().bindings().stream()
                    .filter(value -> value.unitId().equals(component.unit().id())).findFirst().orElseThrow();
                var verified = identity.current(component, binding.releaseSha256());
                if (verified.outcome() != TaskResult.SUCCEEDED)
                    return withoutValue(verified);
                var observed = channel.observe(component.unit(), component.reviewedRuntime());
                if (observed.outcome() != TaskResult.SUCCEEDED || observed.value().isEmpty())
                    return withoutValue(observed);
                var state = switch (observed.value().orElseThrow().state()) {
                    case RUNNING -> ApplicationRunState.RUNNING;
                    case STOPPED -> ApplicationRunState.STOPPED;
                    case INSTALLED -> ApplicationRunState.INSTALLED;
                    case ERROR -> ApplicationRunState.DEGRADED;
                };
                states.put(component.unit().id(), new Observation(state, observed.value().orElseThrow().autostart()));
            }
            for (var component : graph.components()) {
                if (states.get(component.unit().id()).state() != ApplicationRunState.RUNNING)
                    continue;
                var checked = channel.healthCheck(component.unit(), component.reviewedRuntime().healthCheck());
                if (checked.outcome() == TaskResult.UNKNOWN || checked.outcome() == TaskResult.CANCELLED)
                    return withoutValue(checked);
                if (checked.outcome() != TaskResult.SUCCEEDED)
                    states.put(component.unit().id(), new Observation(ApplicationRunState.DEGRADED, states.get(component.unit().id()).autostart()));
            }
            if (overall(states) == ApplicationRunState.RUNNING) {
                var health = graph.components().stream()
                    .filter(component -> component.componentId().equals(graph.healthComponentId())).findFirst()
                    .orElseThrow();
                var checked = channel.healthCheck(health.unit(), graph.healthCheck());
                if (checked.outcome() == TaskResult.UNKNOWN || checked.outcome() == TaskResult.CANCELLED)
                    return withoutValue(checked);
                if (checked.outcome() != TaskResult.SUCCEEDED)
                    states.put(health.unit().id(), new Observation(ApplicationRunState.DEGRADED, states.get(health.unit().id()).autostart()));
            }
            return success(new LiveObservation(Map.copyOf(states), authenticated));
        } catch (ApiException failure) {
            if (expected.isPresent())
                return unknown("DEPLOYMENT_LIFECYCLE_ORIGINAL_TARGET_UNCONFIRMED");
            throw failure;
        }
    }

    /** Copies current admitted metadata into an initial fixed expectation without credentials. / 不含凭据地将当前准入元数据复制为初次固定预期。
     * @param server exact saved identity / 精确保存身份
     * @param task authentic current task / 真实当前任务
     * @return exact initial endpoint and previously trusted wire key / 精确初次端点与先前受信wire密钥
     * @throws ApiException if original metadata is unavailable / 原元数据不可用时 */
    private RemoteTarget currentTarget(ServerId server, TaskContext task) throws ApiException {
        var info = context.servers().get(server, task);
        return new RemoteTarget(server, task.id(), info.host(), info.port(), info.username(), info.trustedFingerprint());
    }

    /** Keeps per-call observations and authenticated identity outside shared runner fields. / 将每次调用观察及认证身份保持在共享runner字段之外。
     * @param components exact current component facts / 精确当前组件事实
     * @param target actual authenticated original target / 实际已认证原目标 */
    private record LiveObservation(Map<String, Observation> components, RemoteTarget target) {
    }

    /** Topologically orders components so start and restart follow dependencies. / 按拓扑排序组件，使启动和重启遵循依赖。
     * @param graph complete reviewed graph / 完整已审阅图
     * @return deterministic dependency order / 确定的依赖顺序 */
    private static List<DeploymentApplicationGraph.Part> ordered(DeploymentApplicationGraph graph) {
        var result = new ArrayList<DeploymentApplicationGraph.Part>();
        Set<String> done = new HashSet<>();
        while (result.size() < graph.components().size()) {
            int before = result.size();
            for (var component : graph.components().stream()
                .sorted(Comparator.comparing(DeploymentApplicationGraph.Part::componentId)).toList())
                if (!done.contains(component.componentId()) && done.containsAll(component.dependencies())) {
                    result.add(component);
                    done.add(component.componentId());
                }
            if (before == result.size())
                throw new IllegalStateException("Reviewed graph contains a cycle");
        }
        return result;
    }

    /** Persists the completed observations only if the reviewed release remains active. / 仅在已审阅发布仍活动时持久化完整观察。
     * @param data original reviewed release / 原已审阅发布
     * @param states complete component observations / 完整组件观察
     * @param task authenticated original task / 已认证原任务
     * @throws ApiException if the original release or commit differs / 原发布或提交不符时 */
    private void persist(ApplicationFacts data, Map<String, Observation> states, TaskContext task)
        throws ApiException {
        writeState(data, states, task);
    }

    /** Blocks replay after an effect whose final observation was unavailable. / 副作用最终观察不可用后阻止重放。
     * @param data original reviewed release / 原已审阅发布
     * @param task authenticated original task / 已认证原任务
     * @throws ApiException if uncertainty cannot be persisted / 未知无法持久化时 */
    private void markUnknown(ApplicationFacts data, TaskContext task) throws ApiException {
        var unknown = new HashMap<String, Observation>();
        for (var component : data.graph().components())
            unknown.put(component.unit().id(), new Observation(ApplicationRunState.UNKNOWN, ApplicationAutostartState.UNKNOWN));
        writeState(data, unknown, task);
    }

    /** Applies one bounded owned-state transaction, preserving uncertainty on commit failure. / 执行一次有界所属状态事务，提交失败保留未知。
     * @param data original reviewed release / 原已审阅发布
     * @param states complete component states / 完整组件状态
     * @param task authenticated original task / 已认证原任务
     * @throws ApiException if active identity or commit cannot be confirmed / 活动身份或提交无法确认时 */
    private void writeState(ApplicationFacts data, Map<String, Observation> states, TaskContext task)
        throws ApiException {
        DeploymentSchema.requireIdentity(context.db().identity());
        try (var connection = context.db().openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                try (var check = connection.prepareStatement(
                    "SELECT release_sha256 FROM deployment_release WHERE application_id=? AND state='ACTIVE' FOR UPDATE")) {
                    check.setString(1, data.snapshot().application().id());
                    try (var rows = check.executeQuery()) {
                        if (!rows.next() || !data.snapshot().revisionSha256().equals(rows.getString(1)) || rows.next())
                            throw new SQLException("Reviewed active release changed");
                    }
                }
                try (var update = connection.prepareStatement(
                    "UPDATE deployment_unit SET runtime_state=?,autostart_state=?,is_ownership_verified=?,observation_evidence=?,observed_at=? WHERE deployment_unit_id=? AND current_release_sha256=? AND ownership_manifest_sha256=? AND server_profile_id=?")) {
                    for (var component : data.graph().components()) {
                        var state = states.get(component.unit().id());
                        update.setString(1, state.state().name());
                        update.setString(2, state.autostart().name());
                        update.setInt(3, state.state() == ApplicationRunState.UNKNOWN ? 0 : 1);
                        update.setString(4, "deployment-helper-observation-v1");
                        update.setLong(5, Instant.now().toEpochMilli());
                        update.setString(6, component.unit().id());
                        var binding = data.release().bindings().stream()
                            .filter(value -> value.unitId().equals(component.unit().id())).findFirst()
                            .orElseThrow();
                        update.setString(7, binding.releaseSha256());
                        update.setString(8, component.unit().ownershipManifestSha256());
                        update.setString(9, component.unit().server().id());
                        if (update.executeUpdate() != 1)
                            throw new SQLException("Current release changed");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    throw problem(ProblemCode.UNKNOWN, "DEPLOYMENT_OBSERVATION_ROLLBACK_UNKNOWN");
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw problem(ProblemCode.UNKNOWN, "DEPLOYMENT_OBSERVATION_COMMIT_UNKNOWN");
        }
    }

    /** Combines exact component states. / 合并精确组件状态。
     * @param states complete component states / 完整组件状态
     * @return conservative whole-application state / 保守整应用状态 */
    private static ApplicationRunState overall(Map<String, Observation> states) {
        return DeploymentApplicationReader.overall(states);
    }

    /** Returns success with a copied payload. / 返回带副本载荷的成功结果。
     * @param <T> result type / 结果类型
     * @param value verified payload / 已核实载荷
     * @return successful result / 成功结果 */
    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    /** Preserves an upstream outcome without inventing evidence. / 保留上游结果且不捏造依据。
     * @param <T> result type / 结果类型
     * @param source original upstream result / 原上游结果
     * @return same outcome without a value / 无值的相同结论 */
    private static <T> OperationResult<T> withoutValue(OperationResult<?> source) {
        return new OperationResult<>(source.outcome(), Optional.empty(), source.problem());
    }

    /** Describes a confirmed refusal. / 描述已确认拒绝。
     * @param <T> result type / 结果类型
     * @param code precise refusal classification / 精确拒绝分类
     * @param event stable failure identity / 稳定失败身份
     * @return known failure / 已知失败 */
    private static <T> OperationResult<T> failure(ProblemCode code, String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(
            new Problem(code, event, "Managed application action was not accepted", Map.of(), Optional.empty())));
    }

    /** Leaves an attempted effect for read-only reconciliation. / 留待尝试过的副作用进行只读核对。
     * @param <T> result type / 结果类型
     * @param event stable uncertainty identity / 稳定未知身份
     * @return retained unknown result / 留存未知结果 */
    private static <T> OperationResult<T> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Lifecycle effect requires original-task reconciliation", Map.of(), Optional.empty())));
    }

    /** Describes task cancellation before another effect. / 描述下一副作用前的任务取消。
     * @param <T> result type / 结果类型
     * @return known cancellation / 已知取消 */
    private static <T> OperationResult<T> cancelled() {
        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.of(new Problem(ProblemCode.CANCELLED,
            "DEPLOYMENT_LIFECYCLE_CANCELLED", "Task cancelled", Map.of(), Optional.empty())));
    }

    /** Describes an unconfirmed local evidence transaction. / 描述未确认的本地依据事务。
     * @param code precise uncertainty classification / 精确未知分类
     * @param event stable evidence failure / 稳定依据失败
     * @return original evidence exception / 原依据异常 */
    private static ApiException problem(ProblemCode code, String event) {
        return new ApiException(
            new Problem(code, event, "Deployment state requires reconciliation", Map.of(), Optional.empty()));
    }
}
