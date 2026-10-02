package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;

import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationRef;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.linux.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Switches complete retained application releases under original-task evidence. / 以原任务依据切换完整保留应用发布。 */
public final class DeploymentRetainedReleases {
    /** Active provider capabilities bound to the original task. / 绑定原任务的活动提供方能力。 */
    private final ModContext context;

    /** Canonical immutable releases and atomic history transition. / 规范不可变发布及原子历史转换。 */
    private final DeploymentReleaseRepository releases;

    /** Original snapshot, activation and compensation acknowledgements. / 原快照、激活及补偿确认。 */
    private final DeploymentManagementJournal journal;

    /** Binds the original deployment generation. / 绑定原部署代次。
     * @param context active context / 活动上下文 */
    public DeploymentRetainedReleases(ModContext context) {
        this.context = Objects.requireNonNull(context);
        releases = new DeploymentReleaseRepository(context.db());
        journal = new DeploymentManagementJournal(context);
    }

    /** Lists immutable histories after verifying the current application. / 核实当前应用后列出不可变历史。
     * @param application exact application / 精确应用
     * @param task original task / 原任务
     * @return retained histories / 保留历史
     * @throws ApiException if evidence cannot be read / 无法读取依据时 */
    public List<DeploymentReleaseRecord> list(DeploymentApplicationRef application, TaskContext task)
        throws ApiException {
        new DeploymentApplicationReader(context.db()).find(application, task);
        try {
            return releases.list(task, application.id());
        } catch (SQLException invalid) {
            throw problem("DEPLOYMENT_RETAINED_HISTORY_INVALID");
        }
    }

    /** Switches once or reconciles the original switch without repeating activation. / 切换一次或不重发激活地核对原切换。
     * @param application exact application / 精确应用
     * @param expected reviewed current release / 已审阅当前发布
     * @param selectedId exact retained history / 精确保留历史
     * @param task original task / 原任务
     * @return verified complete release or uncertainty / 已核实完整发布或未知
     * @throws ApiException if original authorization or evidence fails / 原授权或依据失败时 */
    public OperationResult<String> switchRelease(DeploymentApplicationRef application, String expected, UUID selectedId,
                                                 TaskContext task) throws ApiException {
        context.tasks().inspect(task);
        context.servers().get(application.server(), task);
        DeploymentManagementJournal.Entry operation;
        DeploymentReleaseRecord source;
        DeploymentReleaseRecord target;
        try {
            var reviewed = reviewOperation(application, expected, selectedId, task);
            if (reviewed.outcome() != Outcome.SUCCEEDED)
                return copy(reviewed);
            var transition = reviewed.value().orElseThrow();
            operation = transition.operation();
            source = transition.source();
            target = transition.target();
            var from = releases.snapshot(source);
            var to = releases.snapshot(target);
            requireSameUnits(from, to);
            var publications = publications(target, to, task);
            try (var session = context.linux().connect(application.server(), task)) {
                var helper = new DeploymentManagedHelper(session, context.interaction(), application.server(), task,
                    target.identity().sha256()).probe();
                if (helper.outcome() != Outcome.SUCCEEDED)
                    return copy(helper);
                var channel = new DeploymentHandoffChannel(session, context.interaction(), application.server(), task,
                    target.identity().sha256());
                var publication = new DeploymentPublicationChannel(session, context.interaction(), application.server(),
                    task, target.identity().sha256());
                if (operation.facts().path("compensationAttempted").asBoolean())
                    return verifyRollback(operation, from, to, channel, publication, task);
                if (operation.phase().equals("REVIEWED")) {
                    var snapshots = snapshotSource(operation, from, to, channel, publication, task);
                    if (snapshots.outcome() != Outcome.SUCCEEDED)
                        return copy(snapshots);
                    operation = snapshots.value().orElseThrow();
                    var activated = activateTarget(operation, from, to, channel, task);
                    if (activated.outcome() != Outcome.SUCCEEDED)
                        return copy(activated);
                    operation = activated.value().orElseThrow();
                }
                var verified = verifyTarget(operation, source, target, from, to, channel, publication, task);
                if (verified.outcome() != Outcome.SUCCEEDED)
                    return copy(verified);
                if (operation.phase().equals("FINISHED"))
                    return target.state() == DeploymentReleaseRecord.DeploymentReleaseState.ACTIVE
                        ? success(target.identity().sha256())
                        : unknown("DEPLOYMENT_RETAINED_FORMAL_CURRENT_CHANGED");
                return finishTransition(operation, expected, target, from, to, publications, channel, task);
            }
        } catch (SQLException | NoSuchElementException | IllegalArgumentException invalid) {
            var prior = journal.find(task);
            if (prior.isEmpty() || prior.orElseThrow().phase().equals("REVIEWED"))
                return failed("DEPLOYMENT_RETAINED_EVIDENCE_CHANGED");
            return uncertain(prior.orElseThrow(), task, "DEPLOYMENT_RETAINED_EVIDENCE_UNCONFIRMED");
        } catch (ApiException failure) {
            var prior = journal.find(task);
            if (prior.isPresent() && !prior.orElseThrow().phase().equals("REVIEWED"))
                return uncertain(prior.orElseThrow(), task, "DEPLOYMENT_RETAINED_CALL_UNCONFIRMED");
            throw failure;
        }
    }

    /** Original reviewed history transition. / 原已审阅历史转换。
     * @param operation authenticated original intent / 已认证原意图
     * @param source immutable original active release / 不可变原活动发布
     * @param target immutable selected retained release / 不可变所选保留发布 */
    private record Transition(DeploymentManagementJournal.Entry operation, DeploymentReleaseRecord source,
                              DeploymentReleaseRecord target) {
    }

    /** Reuses the original intent or records a complete conflict-free review. / 复用原意图或记录完整无冲突审阅。
     * @param application exact application / 精确应用
     * @param expected original current digest / 原当前摘要
     * @param selectedId immutable target identity / 不可变目标身份
     * @param task authenticated original task / 已认证原任务
     * @return original transition or known refusal / 原转换或已知拒绝
     * @throws SQLException if immutable release evidence differs / 不可变发布依据不符时
     * @throws ApiException if original authorization or journal fails / 原授权或日志失败时 */
    private OperationResult<Transition> reviewOperation(DeploymentApplicationRef application, String expected,
                                                        UUID selectedId, TaskContext task) throws SQLException, ApiException {
        DeploymentManagementJournal.Entry operation;
        DeploymentReleaseRecord source;
        DeploymentReleaseRecord target;
        target = releases.find(task, application.id(), selectedId).orElseThrow();
        var prior = journal.find(task);
        if (prior.isPresent()) {
            operation = prior.orElseThrow();
            if (!operation.action().equals("HISTORY_SWITCH") || !operation.applicationId().equals(application.id())
                || !operation.server().equals(application.server()) || !operation.currentRevision().equals(expected)
                || !operation.facts().path("selectedId").asText().equals(selectedId.toString())
                || !operation.targetRevision().equals(Optional.of(target.identity().sha256())))
                return failed("DEPLOYMENT_RETAINED_INTENT_CHANGED");
            source = releases.find(task, application.id(), UUID.fromString(operation.facts().path("sourceId").asText()))
                .orElseThrow();
            if (operation.phase().equals("FINISHED")
                && operation.facts().path("terminalOutcome").asText().equals("FAILED"))
                return failed(operation.facts().path("failureEvent").asText());
        } else {
            var current = new DeploymentApplicationReader(context.db()).find(application, task);
            if (!current.snapshot().revisionSha256().equals(expected)
                || target.state() != DeploymentReleaseRecord.DeploymentReleaseState.RETIRED)
                return failed("DEPLOYMENT_RETAINED_REVIEW_CHANGED");
            source = releases.list(task, application.id()).stream()
                .filter(value -> value.state() == DeploymentReleaseRecord.DeploymentReleaseState.ACTIVE).findFirst()
                .orElseThrow();
            var from = releases.snapshot(source);
            var to = releases.snapshot(target);
            requireSameUnits(from, to);
            var facts = DeploymentManagementJournal.facts().put("sourceId", source.identity().id().toString())
                .put("selectedId", selectedId.toString());
            var parts = facts.putObject("components");
            for (var component : to.graph().components())
                parts.putObject(component.componentId()).put("snapshotAttempted", false).put("activateAttempted", false)
                    .put("finishAttempted", false).put("finished", false);
            operation = journal.begin(application.id(), application.server(), "HISTORY_SWITCH", expected,
                Optional.of(target.identity().sha256()), facts, task);
        }
        return success(new Transition(operation, source, target));
    }

    /** Captures every original state before taking any locked source snapshot. / 获取任何加锁源快照前捕获全部原状态。
     * @param operation original reviewed operation / 原已审阅操作
     * @param from complete original source / 完整原源
     * @param to complete selected target / 完整所选目标
     * @param channel original retained helper channel / 原保留helper频道
     * @param publication original runtime observer / 原运行观察器
     * @param task authenticated original task / 已认证原任务
     * @return acknowledged snapshots or uncertainty / 已确认快照或未知
     * @throws ApiException if original evidence or approval fails / 原依据或审批失败时 */
    private OperationResult<DeploymentManagementJournal.Entry> snapshotSource(
        DeploymentManagementJournal.Entry operation, DeploymentReleaseSnapshot from, DeploymentReleaseSnapshot to,
        DeploymentHandoffChannel channel, DeploymentPublicationChannel publication, TaskContext task)
        throws ApiException {
        var originalStates = DeploymentManagementJournal.copy(operation);
        for (var component : ordered(from.graph())) {
            var current = channel.current(component, binding(from, component.componentId()).releaseSha256());
            if (current.outcome() != Outcome.SUCCEEDED)
                return copy(current);
            var observed = publication.observe(component.unit(), component.reviewedRuntime());
            if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty())
                return copy(observed);
            var state = observed.value().orElseThrow().state();
            if (state == DeploymentRemoteRuntimeObservation.StateType.ERROR)
                return failed("DEPLOYMENT_RETAINED_SOURCE_STATE_FAILED");
            originalStates.withObject("components").withObject(component.componentId()).put("beforeState",
                state.name());
        }
        operation = journal.advance(operation, "REVIEWED", originalStates, task);
        for (var component : ordered(from.graph()).reversed()) {
            if (!changed(from, to, component.componentId()))
                continue;
            for (var original : from.graph().components()) {
                var current = channel.current(original, binding(from, original.componentId()).releaseSha256());
                if (current.outcome() != Outcome.SUCCEEDED) {
                    if (operation.phase().equals("REVIEWED"))
                        return copy(current);
                    return copy(uncertain(operation, task, "DEPLOYMENT_RETAINED_SOURCE_CURRENT_CHANGED"));
                }
            }
            var facts = DeploymentManagementJournal.copy(operation);
            facts.withObject("components").withObject(component.componentId()).put("snapshotAttempted", true);
            operation = journal.advance(operation, "EFFECTING", facts, task);
            String kind = component.reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container
                ? "container"
                : "deployment";
            var snapshot = channel.execute("snapshot-retained",
                List.of(component.unit().id(), component.unit().ownershipManifestSha256(), task.id().toString(),
                    binding(from, component.componentId()).releaseSha256(), kind),
                new byte[0], Duration.ofMinutes(2), 4096);
            String running = operation.facts().path("components").path(component.componentId()).path("beforeState")
                .asText().equals("RUNNING") ? "1" : "0";
            if (!DeploymentHandoffChannel.receipt(snapshot,
                "SNAPSHOT_TOKEN=" + task.id() + "\nPREVIOUS=1\nPREVIOUS_RUNNING=" + running + "\nPREVIOUS_DIGEST="
                    + binding(from, component.componentId()).releaseSha256() + "\nPREVIOUS_KIND=" + kind
                    + "\n"))
                return copy(uncertain(operation, task, "DEPLOYMENT_RETAINED_SNAPSHOT_UNKNOWN"));
        }
        return success(operation);
    }

    /** Activates changed target components once under their original snapshots. / 在原快照下仅激活一次已变目标组件。
     * @param operation original snapshot acknowledgements / 原快照确认
     * @param from complete original source / 完整原源
     * @param to complete selected target / 完整所选目标
     * @param channel original retained helper channel / 原保留helper频道
     * @param task authenticated original task / 已认证原任务
     * @return acknowledged activation or uncertainty / 已确认激活或未知
     * @throws ApiException if original evidence or approval fails / 原依据或审批失败时 */
    private OperationResult<DeploymentManagementJournal.Entry> activateTarget(
        DeploymentManagementJournal.Entry operation, DeploymentReleaseSnapshot from, DeploymentReleaseSnapshot to,
        DeploymentHandoffChannel channel, TaskContext task) throws ApiException {
        for (var component : ordered(to.graph())) {
            if (!changed(from, to, component.componentId()))
                continue;
            if (task.cancelToken().requested())
                return copy(uncertain(operation, task, "DEPLOYMENT_RETAINED_CANCELLED_AFTER_SNAPSHOT"));
            var facts = DeploymentManagementJournal.copy(operation);
            facts.withObject("components").withObject(component.componentId()).put("activateAttempted", true);
            operation = journal.advance(operation, "EFFECTING", facts, task);
            var args = arguments(from, to, component, task);
            args.add(task.id().toString());
            var activated = channel.execute("activate-retained", args, new byte[0], Duration.ofMinutes(30), 1024);
            if (!DeploymentHandoffChannel.receipt(activated, "SWITCHED=1\n"))
                return copy(uncertain(operation, task, "DEPLOYMENT_RETAINED_ACTIVATION_UNKNOWN"));
        }
        return success(operation);
    }

    /** Verifies full target identity, retained evidence and original health gates. / 核实完整目标身份、保留依据及原健康门槛。
     * @param operation original attempted transition / 原已尝试转换
     * @param source original formal source state / 原正式源状态
     * @param target original formal target state / 原正式目标状态
     * @param from complete original source / 完整原源
     * @param to complete selected target / 完整所选目标
     * @param channel original retained helper channel / 原保留helper频道
     * @param publication original runtime observer / 原运行观察器
     * @param task authenticated original task / 已认证原任务
     * @return verified target or compensated failure or uncertainty / 已核实目标、已补偿失败或未知
     * @throws ApiException if original evidence or approval fails / 原依据或审批失败时 */
    private OperationResult<Void> verifyTarget(DeploymentManagementJournal.Entry operation,
                                               DeploymentReleaseRecord source, DeploymentReleaseRecord target, DeploymentReleaseSnapshot from,
                                               DeploymentReleaseSnapshot to, DeploymentHandoffChannel channel, DeploymentPublicationChannel publication,
                                               TaskContext task) throws ApiException {
        boolean allRunning = true;
        for (var component : ordered(to.graph())) {
            if (channel.current(component, binding(to, component.componentId()).releaseSha256())
                .outcome() != Outcome.SUCCEEDED)
                return copy(uncertain(operation, task, "DEPLOYMENT_RETAINED_TARGET_CURRENT_UNKNOWN"));
            if (changed(from, to, component.componentId())) {
                if (!operation.facts().path("components").path(component.componentId()).path("activateAttempted")
                    .asBoolean(false))
                    return copy(uncertain(operation, task, "DEPLOYMENT_RETAINED_COMPONENT_NOT_ATTEMPTED"));
                var status = status(channel, from, to, component, task);
                if (status.isEmpty() || !status.orElseThrow().current().equals("target"))
                    return copy(uncertain(operation, task, "DEPLOYMENT_RETAINED_CURRENT_UNKNOWN"));
            }
            var observed = publication.observe(component.unit(), component.reviewedRuntime());
            if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty())
                return copy(uncertain(operation, task, "DEPLOYMENT_RETAINED_RUNTIME_UNKNOWN"));
            String expectedState = targetState(operation, component);
            if (!observed.value().orElseThrow().state().name().equals(expectedState)) {
                if (source.state() == DeploymentReleaseRecord.DeploymentReleaseState.ACTIVE
                    && target.state() == DeploymentReleaseRecord.DeploymentReleaseState.RETIRED)
                    return copy(rollback(operation, from, to, channel, publication, task,
                        "DEPLOYMENT_RETAINED_RUNTIME_FAILED"));
                return copy(uncertain(operation, task, "DEPLOYMENT_RETAINED_COMMITTED_RUNTIME_UNKNOWN"));
            }
            if (!expectedState.equals("RUNNING")) {
                allRunning = false;
                continue;
            }
            var health = publication.healthCheck(component.unit(), component.reviewedRuntime().healthCheck());
            if (health.outcome() == Outcome.FAILED
                && source.state() == DeploymentReleaseRecord.DeploymentReleaseState.ACTIVE
                && target.state() == DeploymentReleaseRecord.DeploymentReleaseState.RETIRED)
                return copy(
                    rollback(operation, from, to, channel, publication, task, "DEPLOYMENT_RETAINED_HEALTH_FAILED"));
            if (health.outcome() != Outcome.SUCCEEDED)
                return copy(uncertain(operation, task, "DEPLOYMENT_RETAINED_HEALTH_FAILED"));
        }
        if (!allRunning)
            return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
        var healthOwner = to.graph().components().stream()
            .filter(value -> value.componentId().equals(to.graph().healthComponentId())).findFirst().orElseThrow();
        var wholeHealth = publication.healthCheck(healthOwner.unit(), to.graph().healthCheck());
        if (wholeHealth.outcome() == Outcome.FAILED
            && source.state() == DeploymentReleaseRecord.DeploymentReleaseState.ACTIVE
            && target.state() == DeploymentReleaseRecord.DeploymentReleaseState.RETIRED)
            return copy(rollback(operation, from, to, channel, publication, task,
                "DEPLOYMENT_RETAINED_APPLICATION_HEALTH_FAILED"));
        if (wholeHealth.outcome() != Outcome.SUCCEEDED)
            return copy(uncertain(operation, task, "DEPLOYMENT_RETAINED_APPLICATION_HEALTH_FAILED"));
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Commits a verified history transition and confirms original snapshot cleanup. / 提交已核实历史转换并确认原快照清理。
     * @param operation original verified operation / 原已核实操作
     * @param expected original formal current digest / 原正式当前摘要
     * @param target immutable selected release / 不可变所选发布
     * @param from complete original source / 完整原源
     * @param to complete selected target / 完整所选目标
     * @param publications complete original target configurations / 完整原目标配置
     * @param channel original retained helper channel / 原保留helper频道
     * @param task authenticated original task / 已认证原任务
     * @return complete verified transition or uncertainty / 完整已核实转换或未知
     * @throws SQLException if the original formal transition conflicts / 原正式转换冲突时
     * @throws ApiException if original cleanup cannot be confirmed / 原清理无法确认时 */
    private OperationResult<String> finishTransition(DeploymentManagementJournal.Entry operation, String expected,
                                                     DeploymentReleaseRecord target, DeploymentReleaseSnapshot from, DeploymentReleaseSnapshot to,
                                                     List<DeploymentUnitCommit> publications, DeploymentHandoffChannel channel, TaskContext task)
        throws SQLException, ApiException {
        releases.activateRetained(task, expected, target, publications, Instant.now());
        for (var component : ordered(to.graph())) {
            if (!changed(from, to, component.componentId()))
                continue;
            var state = status(channel, from, to, component, task);
            if (state.isEmpty())
                return uncertain(operation, task, "DEPLOYMENT_RETAINED_FINISH_STATUS_UNKNOWN");
            if (state.orElseThrow().snapshot().equals("absent") && state.orElseThrow().marker().equals("absent"))
                continue;
            var saved = operation.facts().path("components").path(component.componentId());
            if (saved.path("finishAttempted").asBoolean(false))
                return uncertain(operation, task, "DEPLOYMENT_RETAINED_FINISH_UNKNOWN");
            var facts = DeploymentManagementJournal.copy(operation);
            facts.withObject("components").withObject(component.componentId()).put("finishAttempted", true);
            operation = journal.advance(operation, "EFFECTING", facts, task);
            var args = arguments(from, to, component, task);
            args.add(task.id().toString());
            if (!DeploymentHandoffChannel.receipt(
                channel.execute("finish-retained", args, new byte[0], Duration.ofMinutes(10), 1024),
                "FINISHED=1\n"))
                return uncertain(operation, task, "DEPLOYMENT_RETAINED_FINISH_UNKNOWN");
        }
        journal.advance(operation, "FINISHED",
            DeploymentManagementJournal.copy(operation).put("verifiedRelease", target.identity().sha256()), task);
        return success(target.identity().sha256());
    }

    /** Reconstructs exact retained typed configurations and secret identities. / 重建精确保留类型化配置及秘密身份。
     * @param target immutable selected release / 不可变所选发布
     * @param snapshot complete canonical target / 完整规范目标
     * @param task authenticated original task / 已认证原任务
     * @return complete original target publications / 完整原目标发布
     * @throws SQLException if retained configuration differs / 保留配置不符时
     * @throws ApiException if original configuration access fails / 原配置访问失败时 */
    private List<DeploymentUnitCommit> publications(DeploymentReleaseRecord target, DeploymentReleaseSnapshot snapshot,
                                                    TaskContext task) throws SQLException, ApiException {
        var configurations = new DeploymentConfigurationRepository(context.db());
        var result = new ArrayList<DeploymentUnitCommit>();
        for (var component : snapshot.graph().components()) {
            var binding = binding(snapshot, component.componentId());
            var config = configurations.find(task, component.unit().id(), binding.configurationRevision())
                .orElseThrow();
            if (!config.sha256().equals(binding.configurationSha256()))
                throw new SQLException("Retained configuration differs");
            result.add(new DeploymentUnitCommit(component.unit(), component.runtime(),
                new DeploymentCurrentRelease(component.unit().id(), binding.releaseSha256(), target.createdAt()),
                config, binding.secrets()));
        }
        return List.copyOf(result);
    }

    /** Requires identical component-to-unit mappings before any effect. / 任何副作用前要求相同组件到单元映射。
     * @param source complete original source / 完整原源
     * @param target complete selected target / 完整所选目标 */
    private static void requireSameUnits(DeploymentReleaseSnapshot source, DeploymentReleaseSnapshot target) {
        var from = source.graph().components().stream().collect(java.util.stream.Collectors
            .toMap(DeploymentApplicationGraph.Component::componentId, DeploymentApplicationGraph.Component::unit));
        var to = target.graph().components().stream().collect(java.util.stream.Collectors
            .toMap(DeploymentApplicationGraph.Component::componentId, DeploymentApplicationGraph.Component::unit));
        if (!from.equals(to))
            throw new IllegalArgumentException("Retained switch requires identical original unit identities");
    }

    /** Compensates a known target failure through only the original snapshots. / 仅通过原快照补偿已知目标失败。
     * @param operation original attempted transition / 原已尝试转换
     * @param source complete original source / 完整原源
     * @param target complete selected target / 完整所选目标
     * @param channel original retained helper channel / 原保留helper频道
     * @param publication original runtime observer / 原运行观察器
     * @param task authenticated original task / 已认证原任务
     * @param failureEvent original known failure / 原已知失败
     * @return verified compensated failure or uncertainty / 已核实补偿失败或未知
     * @throws ApiException if original compensation cannot be confirmed / 原补偿无法确认时 */
    private OperationResult<String> rollback(DeploymentManagementJournal.Entry operation,
                                             DeploymentReleaseSnapshot source, DeploymentReleaseSnapshot target, DeploymentHandoffChannel channel,
                                             DeploymentPublicationChannel publication, TaskContext task, String failureEvent) throws ApiException {
        operation = journal.advance(operation, "EFFECTING", DeploymentManagementJournal.copy(operation)
            .put("compensationAttempted", true).put("failureEvent", failureEvent), task);
        for (var component : ordered(target.graph()).reversed()) {
            if (!changed(source, target, component.componentId()))
                continue;
            var facts = DeploymentManagementJournal.copy(operation);
            facts.withObject("components").withObject(component.componentId()).put("rollbackAttempted", true);
            operation = journal.advance(operation, "EFFECTING", facts, task);
            var args = arguments(source, target, component, task);
            args.add(task.id().toString());
            if (!DeploymentHandoffChannel.receipt(
                channel.execute("rollback-retained", args, new byte[0], Duration.ofMinutes(30), 1024),
                "ROLLED_BACK=1\n"))
                return uncertain(operation, task, "DEPLOYMENT_RETAINED_ROLLBACK_UNKNOWN");
            facts = DeploymentManagementJournal.copy(operation);
            facts.withObject("components").withObject(component.componentId()).put("rolledBack", true);
            operation = journal.advance(operation, "EFFECTING", facts, task);
        }
        return verifyRollback(operation, source, target, channel, publication, task);
    }

    /** Observes original digest, runtime, health and cleanup without replaying compensation. / 不重发补偿地观察原摘要、运行、健康及清理。
     * @param operation original compensation attempts / 原补偿尝试
     * @param source complete original source / 完整原源
     * @param target complete selected target / 完整所选目标
     * @param channel original retained helper channel / 原保留helper频道
     * @param publication original runtime observer / 原运行观察器
     * @param task authenticated original task / 已认证原任务
     * @return verified compensated failure or uncertainty / 已核实补偿失败或未知
     * @throws ApiException if original evidence cannot be confirmed / 原依据无法确认时 */
    private OperationResult<String> verifyRollback(DeploymentManagementJournal.Entry operation,
                                                   DeploymentReleaseSnapshot source, DeploymentReleaseSnapshot target, DeploymentHandoffChannel channel,
                                                   DeploymentPublicationChannel publication, TaskContext task) throws ApiException {
        boolean allRunning = true;
        for (var component : ordered(source.graph())) {
            if (channel.current(component, binding(source, component.componentId()).releaseSha256())
                .outcome() != Outcome.SUCCEEDED)
                return uncertain(operation, task, "DEPLOYMENT_RETAINED_OLD_CURRENT_UNKNOWN");
            if (changed(source, target, component.componentId())) {
                var saved = operation.facts().path("components").path(component.componentId());
                if (!saved.path("rollbackAttempted").asBoolean())
                    return uncertain(operation, task, "DEPLOYMENT_RETAINED_ROLLBACK_NOT_ATTEMPTED");
                var observed = status(channel, source, target, component, task);
                if (observed.isEmpty()
                    || !observed.orElseThrow().equals(new RetainedStatus("source", "absent", "absent")))
                    return uncertain(operation, task, "DEPLOYMENT_RETAINED_ROLLBACK_CLEANUP_UNKNOWN");
            }
            var state = publication.observe(component.unit(), component.reviewedRuntime());
            String expected = operation.facts().path("components").path(component.componentId()).path("beforeState")
                .asText();
            if (state.outcome() != Outcome.SUCCEEDED || state.value().isEmpty()
                || !state.value().orElseThrow().state().name().equals(expected))
                return uncertain(operation, task, "DEPLOYMENT_RETAINED_OLD_RUNTIME_UNKNOWN");
            if (!expected.equals("RUNNING")) {
                allRunning = false;
                continue;
            }
            if (publication.healthCheck(component.unit(), component.reviewedRuntime().healthCheck())
                .outcome() != Outcome.SUCCEEDED)
                return uncertain(operation, task, "DEPLOYMENT_RETAINED_OLD_HEALTH_UNKNOWN");
        }
        var healthOwner = source.graph().components().stream()
            .filter(value -> value.componentId().equals(source.graph().healthComponentId())).findFirst()
            .orElseThrow();
        if (allRunning && publication.healthCheck(healthOwner.unit(), source.graph().healthCheck())
            .outcome() != Outcome.SUCCEEDED)
            return uncertain(operation, task, "DEPLOYMENT_RETAINED_OLD_APPLICATION_HEALTH_UNKNOWN");
        journal.advance(operation, "FINISHED",
            DeploymentManagementJournal.copy(operation).put("terminalOutcome", "FAILED"), task);
        return failed(operation.facts().path("failureEvent").asText());
    }

    /** Preserves the original verified running state under the selected workload. / 在所选工作负载下保留原已核实运行状态。
     * @param operation original before-state evidence / 原前态依据
     * @param component selected target component / 所选目标组件
     * @return exact required target state / 精确所需目标状态 */
    private static String targetState(DeploymentManagementJournal.Entry operation,
                                      DeploymentApplicationGraph.Component component) {
        if (component.reviewedRuntime().workload().mode() == DeploymentWorkload.ExecutionMode.ON_DEMAND)
            return "INSTALLED";
        return operation.facts().path("components").path(component.componentId()).path("beforeState").asText()
            .equals("RUNNING") ? "RUNNING" : "STOPPED";
    }

    /** Resolves one component through its verified original unit mapping. / 通过已核实原单元映射解析一个组件。
     * @param snapshot complete canonical release / 完整规范发布
     * @param component exact component identity / 精确组件身份
     * @return exact original unit binding / 精确原单元绑定 */
    private static DeploymentReleaseSnapshot.Binding binding(DeploymentReleaseSnapshot snapshot, String component) {
        var unit = snapshot.graph().components().stream().filter(value -> value.componentId().equals(component))
            .findFirst().orElseThrow().unit().id();
        return snapshot.bindings().stream().filter(value -> value.unitId().equals(unit)).findFirst().orElseThrow();
    }

    /** Compares immutable unit release digests for one exact component. / 比较一个精确组件的不可变单元发布摘要。
     * @param source complete original source / 完整原源
     * @param target complete selected target / 完整所选目标
     * @param component exact component identity / 精确组件身份
     * @return whether the component release differs / 组件发布是否不同 */
    private static boolean changed(DeploymentReleaseSnapshot source, DeploymentReleaseSnapshot target,
                                   String component) {
        return !binding(source, component).releaseSha256().equals(binding(target, component).releaseSha256());
    }

    /** Builds the exact original retained-operation helper identity. / 构造精确原保留操作helper身份。
     * @param source complete original source / 完整原源
     * @param target complete selected target / 完整所选目标
     * @param component exact target component / 精确目标组件
     * @param task authenticated original task / 已认证原任务
     * @return original mutable argument list / 原可变参数列表 */
    private static ArrayList<String> arguments(DeploymentReleaseSnapshot source, DeploymentReleaseSnapshot target,
                                               DeploymentApplicationGraph.Component component, TaskContext task) {
        return new ArrayList<>(List.of(component.unit().id(), binding(source, component.componentId()).releaseSha256(),
            binding(target, component.componentId()).releaseSha256(), component.unit().ownershipManifestSha256(),
            task.id().toString()));
    }

    /** Exact read-only retained status receipt. / 精确只读保留状态回执。
     * @param current source or target digest identity / 源或目标摘要身份
     * @param snapshot original snapshot ownership / 原快照归属
     * @param marker original maintenance ownership / 原维护归属 */
    private record RetainedStatus(String current, String snapshot, String marker) {
    }

    /** Reads only an exact complete status for the original transition. / 仅读取原转换的精确完整状态。
     * @param channel original retained helper channel / 原保留helper频道
     * @param source complete original source / 完整原源
     * @param target complete selected target / 完整所选目标
     * @param component exact target component / 精确目标组件
     * @param task authenticated original task / 已认证原任务
     * @return exact status or absence of complete evidence / 精确状态或无完整依据
     * @throws ApiException if original observation approval fails / 原观察审批失败时 */
    private static Optional<RetainedStatus> status(DeploymentHandoffChannel channel, DeploymentReleaseSnapshot source,
                                                   DeploymentReleaseSnapshot target, DeploymentApplicationGraph.Component component, TaskContext task)
        throws ApiException {
        var result = channel.execute("retained-status", arguments(source, target, component, task), new byte[0],
            Duration.ofMinutes(2), 1024);
        if (result.outcome() != Outcome.SUCCEEDED || result.value().isEmpty()
            || result.value().orElseThrow().truncated()
            || !result.value().orElseThrow().exitCode().equals(Optional.of(0)))
            return Optional.empty();
        var match = java.util.regex.Pattern.compile(
                "RETAINED_STATUS=1\\nCURRENT=(source|target)\\nSNAPSHOT=(owned|absent)\\nMARKER=(owned|absent)\\n")
            .matcher(result.value().orElseThrow().stdout());
        return match.matches()
            ? Optional.of(new RetainedStatus(match.group(1), match.group(2), match.group(3)))
            : Optional.empty();
    }

    /** Orders complete components by their declared dependencies. / 按声明依赖排列完整组件。
     * @param graph complete canonical graph / 完整规范图
     * @return dependency order / 依赖顺序 */
    private static List<DeploymentApplicationGraph.Component> ordered(DeploymentApplicationGraph graph) {
        var result = new ArrayList<DeploymentApplicationGraph.Component>();
        var done = new HashSet<String>();
        while (result.size() < graph.components().size()) {
            int before = result.size();
            for (var value : graph.components())
                if (!done.contains(value.componentId()) && done.containsAll(value.dependencies())) {
                    result.add(value);
                    done.add(value.componentId());
                }
            if (before == result.size())
                throw new IllegalArgumentException("Graph cycle");
        }
        return result;
    }

    /** Retains an attempted original transition for later read-only observation. / 留存已尝试原转换供后续只读观察。
     * @param entry original durable operation / 原持久操作
     * @param task authenticated original task / 已认证原任务
     * @param event stable uncertainty identity / 稳定未知身份
     * @return retained uncertainty / 留存未知
     * @throws ApiException if uncertainty cannot be persisted / 未知无法持久化时 */
    private OperationResult<String> uncertain(DeploymentManagementJournal.Entry entry, TaskContext task, String event)
        throws ApiException {
        if (!entry.phase().equals("UNKNOWN") && !entry.phase().equals("FINISHED"))
            journal.advance(entry, "UNKNOWN", DeploymentManagementJournal.copy(entry).put("uncertainEvent", event),
                task);
        return unknown(event);
    }

    /** Preserves an upstream conclusion without fabricating a value. / 保留上游结论且不捏造值。
     * @param <T> result type / 结果类型
     * @param result upstream original result / 上游原结果
     * @return same conclusion without a value / 无值的相同结论 */
    private static <T> OperationResult<T> copy(OperationResult<?> result) {
        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
    }

    /** Returns a verified complete transition. / 返回已核实完整转换。
     * @param <T> result type / 结果类型
     * @param value verified original value / 已核实原值
     * @return successful original result / 成功原结果 */
    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    /** Returns a known identity or compensated failure. / 返回已知身份或已补偿失败。
     * @param <T> result type / 结果类型
     * @param event stable known failure / 稳定已知失败
     * @return known original failure / 已知原失败 */
    private static <T> OperationResult<T> failed(String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(problem(event).problem()));
    }

    /** Keeps an unconfirmed original transition pending. / 保持未确认原转换待核对。
     * @param <T> result type / 结果类型
     * @param event stable uncertainty identity / 稳定未知身份
     * @return unknown original result / 未知原结果 */
    private static <T> OperationResult<T> unknown(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Original retained operation requires reconciliation", Map.of(), Optional.empty())));
    }

    /** Describes retained evidence conflict without revealing payloads. / 不暴露载荷地描述保留依据冲突。
     * @param event stable evidence failure / 稳定依据失败
     * @return bounded original evidence exception / 有界原依据异常 */
    private static ApiException problem(String event) {
        return new ApiException(new Problem(ProblemCode.INCOMPATIBLE, event, "Retained release evidence differs",
            Map.of(), Optional.empty()));
    }
}
